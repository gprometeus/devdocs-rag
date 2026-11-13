package dev.gprometeus.devdocsrag.ingestion.chunking;

import java.util.Objects;

/**
 * A paragraph or a whole fenced block of one section.
 *
 * @param text      source lines joined with "\n"; for CODE and TABLE including both fence lines
 * @param fenceInfo first word of the fence info string ("" for a bare fence), null for PARAGRAPH
 * @param tokens    token count of {@code text}, computed once when the block is created
 */
public record Block(BlockType type, String text, String fenceInfo, int tokens) {

    public Block {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        if ((type == BlockType.PARAGRAPH) != (fenceInfo == null)) {
            throw new IllegalArgumentException("fenceInfo must be null exactly for PARAGRAPH, got " + type + "/" + fenceInfo);
        }
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must be >= 0: " + tokens);
        }
    }
}
