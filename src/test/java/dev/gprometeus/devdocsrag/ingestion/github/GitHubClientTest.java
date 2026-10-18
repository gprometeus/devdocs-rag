package dev.gprometeus.devdocsrag.ingestion.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Rate-limit and retry logic with a fixed clock, recorded sleeps and a mocked server. */
class GitHubClientTest {

    private static final String URL = "https://api.github.com/items";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        // bindTo replaces the builder's request factory, so build() must be called afterwards
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder.build(), JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);
    }

    @Test
    void waitsRetryAfterOn429AndRepeatsRequest() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30"));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("[1]", MediaType.APPLICATION_JSON));

        assertThat(client.getArray("/items")).hasSize(1);

        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
        assertThat(client.requestCount()).isEqualTo(2);
    }

    @Test
    void sleepsUntilResetWhenRemainingIsLow() {
        long reset = NOW.plusSeconds(60).getEpochSecond();
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON)
                        .header("x-ratelimit-remaining", "3")
                        .header("x-ratelimit-reset", String.valueOf(reset)));

        assertThat(client.getArray("/items")).isEmpty();

        // 60 s until reset + 5 s RESET_MARGIN
        assertThat(sleeps).containsExactly(Duration.ofSeconds(65));
    }

    @Test
    void retriesServerErrorsWithExponentialBackoff() {
        server.expect(times(3), requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("[1, 2]", MediaType.APPLICATION_JSON));

        assertThat(client.getArray("/items")).hasSize(2);

        server.verify();
        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void givesUpAfterMaxServerRetries() {
        server.expect(times(4), requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("unavailable"));

        assertThatThrownBy(() -> client.getArray("/items"))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e).hasMessageContaining("unavailable");
                });

        server.verify();
        assertThat(sleeps).hasSize(3);
        assertThat(client.requestCount()).isEqualTo(4);
    }

    @Test
    void retriesNetworkErrorsThenRethrowsOriginalException() {
        server.expect(times(4), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.getArray("/items"))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        server.verify();
        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void forbiddenWithoutRateLimitHeadersFailsImmediately() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("Resource not accessible by token"));

        assertThatThrownBy(() -> client.getArray("/items"))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> assertThat(e.status()).isEqualTo(403));

        server.verify();
        assertThat(sleeps).isEmpty();
    }
}
