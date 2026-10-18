package dev.gprometeus.devdocsrag.ingestion;

import java.util.Objects;

/**
 * Source-agnostic document produced by loaders and consumed by the chunker.
 * Fields map to the columns of the {@code document} table.
 */
public record SourceDocument(
        SourceType sourceType,
        String sourceId,    // .adoc file path or issue number
        String sourceUrl,   // link used to cite the source in answers
        String title,
        String sourceRef,   // git tag or snapshot date
        String text         // cleaned text; this is what gets hashed and chunked
) {
    public SourceDocument {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Empty text for " + sourceType + ":" + sourceId);
        }
    }
}