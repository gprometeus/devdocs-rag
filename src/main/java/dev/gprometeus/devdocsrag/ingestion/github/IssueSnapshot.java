package dev.gprometeus.devdocsrag.ingestion.github;

import java.time.Instant;
import java.util.List;

/** Закрытая issue в JSONL-снимке: одна запись = одна строка файла. */
public record IssueSnapshot(
        int number,
        String title,
        String body,            // null, если у issue нет описания
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
