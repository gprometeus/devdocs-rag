package dev.gprometeus.devdocsrag.ingestion.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * GET-запросы к GitHub REST API с учётом rate limit и повторами на 5xx.
 * Не thread-safe: экспорт идёт последовательно, страница за страницей.
 */
final class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private static final String BASE_URL = "https://api.github.com";
    private static final String API_VERSION = "2026-03-10";

    private static final int LOW_REMAINING_THRESHOLD = 10;
    private static final Duration RESET_MARGIN = Duration.ofSeconds(5);
    private static final int MAX_SERVER_RETRIES = 3;
    private static final int MAX_RATE_LIMIT_WAITS = 5;
    private static final int ERROR_BODY_LIMIT = 500;

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Response(HttpStatusCode status, HttpHeaders headers, byte[] body) {
    }

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Sleeper sleeper;
    private int requestCount;

    GitHubClient(RestClient restClient, JsonMapper jsonMapper, Clock clock, Sleeper sleeper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    static RestClient createRestClient(String token) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .defaultHeaders(headers -> headers.setBearerAuth(token))
                .build();
    }

    int requestCount() {
        return requestCount;
    }

    /** GET, ожидающий JSON-массив. Сам ждёт при исчерпании лимита и повторяет запрос на 5xx. */
    JsonNode getArray(String uriTemplate, Object... uriVariables) {
        String uri = UriComponentsBuilder.fromUriString(uriTemplate).buildAndExpand(uriVariables).toUriString();
        int serverRetries = 0;
        int rateLimitWaits = 0;
        while (true) {
            Response response;
            try {
                response = execute(uriTemplate, uriVariables);
            } catch (ResourceAccessException e) {
                // Сетевой сбой (таймаут, обрыв соединения) лечится так же, как 5xx: повтором с паузой
                if (serverRetries >= MAX_SERVER_RETRIES) {
                    throw e;
                }
                backoff(++serverRetries, uri, e.getMessage());
                continue;
            }

            int status = response.status().value();
            if (response.status().is2xxSuccessful()) {
                throttleIfNearLimit(response.headers());
                return parseArray(response.body(), uri);
            }
            if (status == 403 || status == 429) {
                Optional<Duration> wait = rateLimitWait(status, response.headers());
                if (wait.isPresent() && rateLimitWaits < MAX_RATE_LIMIT_WAITS) {
                    rateLimitWaits++;
                    log.info("Rate limit hit (HTTP {}) on {}, sleeping {} s", status, uri, wait.get().toSeconds());
                    sleep(wait.get());
                    continue;
                }
            }
            if (response.status().is5xxServerError() && serverRetries < MAX_SERVER_RETRIES) {
                backoff(++serverRetries, uri, "HTTP " + status);
                continue;
            }
            throw new GitHubApiException(status, uri, errorSnippet(response.body()));
        }
    }

    private Response execute(String uriTemplate, Object... uriVariables) {
        requestCount++;
        // exchange() вместо retrieve(): не бросает исключение на 4xx/5xx и даёт заголовки любого ответа
        return restClient.get()
                .uri(uriTemplate, uriVariables)
                .exchange((request, response) -> new Response(
                        response.getStatusCode(),
                        response.getHeaders(),
                        response.getBody().readAllBytes()), true);
    }

    private JsonNode parseArray(byte[] body, String uri) {
        JsonNode node = jsonMapper.readTree(body);
        if (!node.isArray()) {
            throw new GitHubApiException(200, uri, "expected JSON array, got " + node.getNodeType());
        }
        return node;
    }

    /** Превентивно ждём сброса окна, пока лимит не кончился совсем и GitHub не начал отвечать 403. */
    private void throttleIfNearLimit(HttpHeaders headers) {
        Long remaining = longHeader(headers, "x-ratelimit-remaining");
        Long reset = longHeader(headers, "x-ratelimit-reset");
        if (remaining != null && reset != null && remaining < LOW_REMAINING_THRESHOLD) {
            Duration wait = untilReset(reset);
            log.info("Rate limit nearly exhausted (remaining={}), sleeping {} s until reset",
                    remaining, wait.toSeconds());
            sleep(wait);
        }
    }

    /**
     * Сколько ждать на 403/429. Пусто — это не лимит, а настоящая ошибка доступа:
     * ждать до reset бессмысленно, лучше упасть сразу.
     */
    private Optional<Duration> rateLimitWait(int status, HttpHeaders headers) {
        Long retryAfter = longHeader(headers, HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            return Optional.of(Duration.ofSeconds(retryAfter));
        }
        Long remaining = longHeader(headers, "x-ratelimit-remaining");
        Long reset = longHeader(headers, "x-ratelimit-reset");
        boolean exhausted = remaining != null && remaining == 0;
        if (reset != null && (exhausted || status == 429)) {
            return Optional.of(untilReset(reset));
        }
        return Optional.empty();
    }

    private Duration untilReset(long resetEpochSeconds) {
        Duration wait = Duration.between(Instant.now(clock), Instant.ofEpochSecond(resetEpochSeconds))
                .plus(RESET_MARGIN);
        return wait.isNegative() ? Duration.ZERO : wait;
    }

    private void backoff(int attempt, String uri, String reason) {
        Duration pause = Duration.ofSeconds(1L << attempt);   // 2, 4, 8 s
        log.warn("{} failed ({}), retry {}/{} in {} s", uri, reason, attempt, MAX_SERVER_RETRIES, pause.toSeconds());
        sleep(pause);
    }

    private void sleep(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for GitHub API", e);
        }
    }

    private static Long longHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;   // например, Retry-After в формате HTTP-date; GitHub так не делает
        }
    }

    private static String errorSnippet(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "...";
    }
}
