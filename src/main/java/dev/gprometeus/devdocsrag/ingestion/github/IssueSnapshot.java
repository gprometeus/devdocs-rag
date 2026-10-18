package dev.gprometeus.devdocsrag.ingestion.github;

import java.time.Instant;
import java.util.List;

/** Closed issue in a JSONL snapshot; each record is serialized as one line. */
public record IssueSnapshot(
        int number,
        String title,
        String body,            // null if the issue has no description
        String htmlUrl,
        String stateReason,
        List<String> labels,
        Instant createdAt,
        Instant closedAt,
        List<IssueComment> comments
) {
    public IssueSnapshot {
        labels = List.copyOf(labels);
        comments = List.copyOf(comments);
    }
}
