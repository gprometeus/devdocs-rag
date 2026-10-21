package dev.gprometeus.devdocsrag.ingestion.issue;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

/**
 * Issue source settings. The snapshot and the creation cutoff date are mandatory; an absent label list
 * is a valid configuration that excludes nothing.
 */
@Validated
@ConfigurationProperties(prefix = "devdocs.issues")
public record IssueProperties(
        @NotNull Path snapshotFile,
        @NotNull LocalDate createdAfter,
        Set<String> excludedLabels
) {
    public IssueProperties {
        excludedLabels = excludedLabels == null ? Set.of() : Set.copyOf(excludedLabels);
    }
}
