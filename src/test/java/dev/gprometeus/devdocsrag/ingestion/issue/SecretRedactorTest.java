package dev.gprometeus.devdocsrag.ingestion.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    // --- must be redacted ---

    @Test
    void redactsOpenAiProjectKey() {
        String key = "sk-proj-" + "A1b2C3d4".repeat(5);
        String result = redactor.redact("my key is " + key + " ok");
        assertThat(result).doesNotContain(key).contains("[REDACTED_OPENAI_KEY]");
    }

    @Test
    void redactsAnthropicKeyWithItsOwnLabel() {
        String key = "sk-ant-api03-" + "x".repeat(40);
        String result = redactor.redact("key " + key);
        assertThat(result).doesNotContain(key).contains("[REDACTED_ANTHROPIC_KEY]");
    }

    @Test
    void redactsClassicGithubToken() {
        String token = "ghp_" + "a".repeat(36);
        assertThat(redactor.redact("token " + token)).doesNotContain(token);
    }

    @Test
    void redactsFineGrainedGithubToken() {
        String token = "github_pat_" + "b".repeat(40);
        assertThat(redactor.redact("use " + token)).doesNotContain(token);
    }

    @Test
    void redactsAwsAccessKey() {
        String key = "AKIA" + "ABCDEFGHIJKLMNOP";
        assertThat(redactor.redact("aws " + key)).contains("[REDACTED_AWS_KEY]");
    }

    @Test
    void redactsBearerToken() {
        String token = "eyJ" + "c".repeat(40);
        String result = redactor.redact("Authorization: Bearer " + token);
        assertThat(result).isEqualTo("Authorization: Bearer [REDACTED_TOKEN]");
    }

    @Test
    void redactsPasswordInYaml() {
        assertThat(redactor.redact("password: qwerty123"))
                .isEqualTo("password: [REDACTED]");
    }

    @Test
    void redactsPasswordCaseInsensitive() {
        assertThat(redactor.redact("DB_PASSWORD=qwerty123"))
                .isEqualTo("DB_PASSWORD=[REDACTED]");
    }

    @Test
    void redactsEmail() {
        assertThat(redactor.redact("write to john.doe@example.com please"))
                .isEqualTo("write to [REDACTED_EMAIL] please");
    }

    // --- must not be redacted (false positives) ---

    @Test
    void keepsPlaceholder() {
        String text = "spring.ai.openai.api-key: ${OPENAI_API_KEY}";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsMaxTokensSetting() {
        String text = "spring.ai.openai.chat.options.max-tokens: 512";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsShortSkWord() {
        String text = "Compare with sk-learn embeddings";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsSkInsideLongerIdentifier() {
        // Without the NOT_AFTER_ID guard "sk-assessment-..." would pass the 20-character key length
        String text = "See risk-assessment-framework-guidelines-v2 for details";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsAngleBracketPlaceholder() {
        String text = "api-key: <your-key>\npassword=<db-password>";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsLogPlaceholderAfterPassword() {
        String text = "log.debug(\"password: {}\", masked);";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsOrdinaryText() {
        String text = "Set spring.ai.ollama.chat.options.model to qwen3:4b";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    // --- false positives found in the real snapshot ---

    @Test
    void keepsJavaCode() {
        String text = """
                String token = McpUserHolder.get();
                token= request.getHeader("token");
                if (token == null || token.isBlank()) {
                logger.info("Received token: {}", token);
                return "Token: " + token;
                System.setProperty("spring.ai.openai.apiKey=" + System.getenv("OPENAI_API_KEY"));""";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsBooleanFlagsInJson() {
        String text = "\"password\": true, \"requires_api_key\": False";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsGitSshUrlAndObjectToString() {
        String text = "git clone git@github.com:spring-projects/spring-ai.git\n"
                + "No annotated methods found in org.example.WeatherService@37b52340.Did you mean";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }

    @Test
    void redactsQuotedValueUpToClosingQuote() {
        assertThat(redactor.redact("\"token\":\"abc def\"}"))
                .isEqualTo("\"token\":\"[REDACTED]\"}");
    }

    // --- properties ---

    @Test
    void isIdempotent() {
        String text = "api-key: sk-proj-" + "Z9y8X7w6".repeat(5) + "\npassword: secret123";
        String once = redactor.redact(text);
        assertThat(redactor.redact(once)).isEqualTo(once);
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(redactor.redact("")).isEmpty();
    }
}
