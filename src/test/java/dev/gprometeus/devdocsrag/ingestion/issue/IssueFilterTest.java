package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IssueFilterTest {

    private final IssueFilter filter = new IssueFilter(new IssueProperties(
            Path.of("snapshot.jsonl"), LocalDate.parse("2025-05-20"), Set.of("type: backport")));

    @Test
    void decidesByCreationDateNotByClosingDate() {
        // Closed before the cutoff, which cannot happen for real data, so only createdAt can make it pass
        IssueSnapshot issue = issue("2025-05-20T00:00:00Z", "2025-05-01T00:00:00Z", "bug");

        assertThat(filter.accept(issue)).isTrue();
    }

    @Test
    void rejectsIssueCreatedBeforeCutoffAndClosedAfterIt() {
        IssueSnapshot issue = issue("2025-05-19T23:59:59Z", "2025-09-01T00:00:00Z", "bug");

        assertThat(filter.accept(issue)).isFalse();
    }

    @Test
    void rejectsExcludedLabel() {
        IssueSnapshot issue = issue("2025-08-01T00:00:00Z", "2025-08-12T00:00:00Z", "type: backport");

        assertThat(filter.accept(issue)).isFalse();
    }

    private static IssueSnapshot issue(String createdAt, String closedAt, String label) {
        return new IssueSnapshot(3511, "Title", "Body", "https://github.com/spring-projects/spring-ai/issues/3511",
                "completed", List.of(label), Instant.parse(createdAt), Instant.parse(closedAt), List.of());
    }
}
