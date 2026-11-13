package dev.gprometeus.devdocsrag.ingestion.docs;

import dev.gprometeus.devdocsrag.ingestion.SourceDocument;

import java.util.List;
import java.util.Objects;

/**
 * @param documents pages in sourceId order
 * @param includes  include counts summed over all pages, skipped pages included
 */
public record DocsLoadResult(List<SourceDocument> documents, IncludeStats includes) {

    public DocsLoadResult {
        documents = List.copyOf(documents);
        Objects.requireNonNull(includes, "includes");
    }
}
