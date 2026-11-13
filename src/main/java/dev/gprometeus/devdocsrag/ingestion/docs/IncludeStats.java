package dev.gprometeus.devdocsrag.ingestion.docs;

/**
 * Include counts of one resolve call or, summed with {@link #plus}, of a whole load.
 *
 * @param resolved includes replaced by the file content, nested ones included
 * @param missing  targets that do not exist or lie outside the docs module
 * @param skipped  includes not inserted because of a cycle or the depth limit
 */
public record IncludeStats(int resolved, int missing, int skipped) {

    public static final IncludeStats EMPTY = new IncludeStats(0, 0, 0);

    public IncludeStats plus(IncludeStats other) {
        return new IncludeStats(resolved + other.resolved, missing + other.missing, skipped + other.skipped);
    }
}
