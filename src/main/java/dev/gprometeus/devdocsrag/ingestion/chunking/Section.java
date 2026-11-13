package dev.gprometeus.devdocsrag.ingestion.chunking;

import java.util.List;

/**
 * Content between a heading and the next heading of any level.
 *
 * @param headingPath document title followed by the headings from the outermost one down to this section's own;
 *                    an h1 that repeats the title takes the title's place instead of following it
 * @param level       ATX level of the section's heading (1..6), 0 for the text before the first heading
 * @param blocks      in document order; empty for a heading directly followed by a subheading
 */
public record Section(List<String> headingPath, int level, List<Block> blocks) {

    public Section {
        // Immutable copies: the parser's working lists must not leak, and List.copyOf rejects null elements
        headingPath = List.copyOf(headingPath);
        blocks = List.copyOf(blocks);
        if (level < 0 || level > 6) {
            throw new IllegalArgumentException("level must be 0..6: " + level);
        }
    }
}
