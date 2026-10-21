package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import dev.gprometeus.devdocsrag.ingestion.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueDocumentLoaderTest {

    // Assembled from parts so push protection does not mistake the test for a leaked key
    private static final String FAKE_KEY = "sk-proj-" + "A1b2C3d4".repeat(5);

    @TempDir
    Path dir;

    @Test
    void keepsOnlySelectedIssuesWithTextAndRedactsThem() throws IOException {
        Path snapshot = dir.resolve("spring-ai-issues-2026-10-17.jsonl");
        Files.write(snapshot, List.of(
                issue(1, "Key leaked by john.doe@example.com", "My key is " + FAKE_KEY, "2025-08-12T10:00:00Z", "bug"),
                issue(2, "Created before cutoff", "Old but valid text", "2025-05-19T23:59:59Z", "bug"),
                issue(3, "Backport", "Valid text", "2025-08-12T10:00:00Z", "type: backport"),
                issue(4, "No content", null, "2025-08-12T10:00:00Z", "bug")));

        List<SourceDocument> documents = loader(snapshot).load();

        assertThat(documents).singleElement().satisfies(doc -> {
            assertThat(doc.sourceType()).isEqualTo(SourceType.ISSUE);
            assertThat(doc.sourceId()).isEqualTo("1");
            assertThat(doc.sourceUrl()).isEqualTo("https://github.com/spring-projects/spring-ai/issues/1");
            assertThat(doc.sourceRef()).isEqualTo("snapshot:spring-ai-issues-2026-10-17.jsonl");
            assertThat(doc.title()).isEqualTo("Key leaked by [REDACTED_EMAIL]");
            assertThat(doc.text())
                    .startsWith("Issue #1: Key leaked by [REDACTED_EMAIL]")
                    .contains("My key is [REDACTED_OPENAI_KEY]")
                    .doesNotContain(FAKE_KEY);
        });
    }

    private static IssueDocumentLoader loader(Path snapshot) {
        IssueProperties props = new IssueProperties(snapshot, LocalDate.parse("2025-05-20"), Set.of("type: backport"));
        return new IssueDocumentLoader(new IssueSnapshotReader(JsonMapper.builder().build()), new IssueFilter(props),
                new IssueTextBuilder(), new SecretRedactor(), props);
    }

    private static String issue(int number, String title, String body, String createdAt, String label) {
        String bodyJson = body == null ? "null" : "\"" + body + "\"";
        return """
                {"number":%d,"title":"%s","body":%s,"htmlUrl":"https://github.com/spring-projects/spring-ai/issues/%d",\
                "stateReason":"completed","labels":["%s"],"createdAt":"%s","closedAt":"2025-09-01T10:00:00Z",\
                "comments":[]}""".formatted(number, title, bodyJson, number, label, createdAt);
    }
}
