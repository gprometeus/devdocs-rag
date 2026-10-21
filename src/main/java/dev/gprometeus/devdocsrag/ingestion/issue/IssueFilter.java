package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Selects issues by metadata: creation date and labels.
 * <p>
 * The cutoff applies to the creation date, not the closing date: an issue opened before the cutoff
 * discusses an older API even if it was closed later, for example in a bulk cleanup.
 * Whether an issue has any useful text is decided by {@link IssueTextBuilder}.
 */
@Component
public class IssueFilter {

    private final Instant createdAfter;
    private final Set<String> excludedLabels;

    public IssueFilter(IssueProperties props) {
        this.createdAfter = props.createdAfter().atStartOfDay(ZoneOffset.UTC).toInstant();
        this.excludedLabels = props.excludedLabels();
    }

    public boolean accept(IssueSnapshot issue) {
        return isCreatedAfterCutoff(issue) && hasNoExcludedLabels(issue);
    }

    private boolean isCreatedAfterCutoff(IssueSnapshot issue) {
        return issue.createdAt() != null && !issue.createdAt().isBefore(createdAfter);
    }

    private boolean hasNoExcludedLabels(IssueSnapshot issue) {
        return issue.labels().stream().noneMatch(excludedLabels::contains);
    }
}
