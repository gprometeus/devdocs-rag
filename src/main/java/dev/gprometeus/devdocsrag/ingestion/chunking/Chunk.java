package dev.gprometeus.devdocsrag.ingestion.chunking;

import java.util.Objects;

/**
 * One unit of retrieval: a row of the {@code chunk} table without the embedding.
 *
 * @param index      position within the document, from 0 without gaps (chunk.chunk_index)
 * @param section    heading path joined with " › ", e.g. "Ollama Chat › Configuration › Runtime Options" (chunk.section)
 * @param content    exactly what is embedded and stored: section + "\n\n" + body (chunk.content)
 * @param tokenCount tokens of {@code content} as the embedding model counts them (chunk.token_count)
 */
public record Chunk(int index, String section, String content, int tokenCount) {

    // Same rules as the CHECK constraints of the chunk table: fail here, with the document at hand,
    // not later in a batch INSERT where the offending row is hard to find
    public Chunk {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
        Objects.requireNonNull(section, "section");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (tokenCount <= 0) {
            throw new IllegalArgumentException("tokenCount must be > 0: " + tokenCount);
        }
    }
}
