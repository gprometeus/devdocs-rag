package dev.gprometeus.devdocsrag.ingestion.docs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code include::target[options]} lines with the content of the target file.
 * <p>
 * Options such as {@code tag=} or {@code lines=} are ignored: the whole file is inserted.
 * Lines inside code blocks are left untouched. A missing file inserts nothing and is logged,
 * so a broken include is visible in the preview statistics instead of silently shrinking the index.
 * <p>
 * The counters accumulate across calls until {@link #resetCounters()}; the loader resets them per load.
 */
@Component
public class IncludeResolver {

    private static final Logger log = LoggerFactory.getLogger(IncludeResolver.class);

    // A page's own includes are level 1, so a partial may include a partial that includes one more
    private static final int MAX_DEPTH = 3;
    private static final String PARTIAL_PREFIX = "partial$";
    private static final String MARKDOWN_FENCE = "```";

    private static final Pattern INCLUDE = Pattern.compile("include::([^\\[]+)\\[.*]");
    // Same delimiters as in AsciidocConverter: listing, literal and Markdown fences
    private static final Pattern CODE_DELIMITER = Pattern.compile("-{4,}|\\.{4,}|```.*");

    private final AtomicInteger resolved = new AtomicInteger();
    private final AtomicInteger missing = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    /**
     * @param file the file {@code text} was read from; relative targets are resolved against its folder
     * @param root the Antora module root; {@code partial$x.adoc} is resolved to {@code root/partials/x.adoc}
     */
    public String resolve(Path file, String text, Path root) {
        // Membership checks only, the iteration order never reaches the output
        Set<Path> openFiles = new HashSet<>();
        return resolve(normalize(file), text, normalize(root), 1, openFiles);
    }

    public int resolvedCount() {
        return resolved.get();
    }

    public int missingCount() {
        return missing.get();
    }

    /** Includes not inserted because of a cycle or the depth limit. */
    public int skippedCount() {
        return skipped.get();
    }

    public void resetCounters() {
        resolved.set(0);
        missing.set(0);
        skipped.set(0);
    }

    private String resolve(Path file, String text, Path root, int depth, Set<Path> openFiles) {
        openFiles.add(file);
        List<String> out = new ArrayList<>();
        String closingDelimiter = null;   // non-null while inside a code block
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (closingDelimiter != null) {
                if (trimmed.equals(closingDelimiter)) {
                    closingDelimiter = null;
                }
                out.add(line);
            } else if (CODE_DELIMITER.matcher(trimmed).matches()) {
                // "```java" is closed by a bare "```", the other delimiters by the same line
                closingDelimiter = trimmed.startsWith(MARKDOWN_FENCE) ? MARKDOWN_FENCE : trimmed;
                out.add(line);
            } else {
                Matcher include = INCLUDE.matcher(trimmed);
                if (include.matches()) {
                    String content = include(file, include.group(1).strip(), root, depth, openFiles);
                    if (content != null) {
                        out.add(content);
                    }
                } else {
                    out.add(line);
                }
            }
        }
        // Removed on the way out: the same partial may be included twice in a row, that is not a cycle
        openFiles.remove(file);
        return String.join("\n", out);
    }

    /** Returns the resolved content of the target, or null when nothing is inserted. */
    private String include(Path from, String target, Path root, int depth, Set<Path> openFiles) {
        Path path = target.startsWith(PARTIAL_PREFIX)
                ? root.resolve("partials").resolve(target.substring(PARTIAL_PREFIX.length())).normalize()
                : from.getParent().resolve(target).normalize();
        if (depth > MAX_DEPTH) {
            skipped.incrementAndGet();
            log.warn("Include depth limit {} reached in {}: include::{}[] skipped", MAX_DEPTH, from, target);
            return null;
        }
        if (openFiles.contains(path)) {
            skipped.incrementAndGet();
            log.warn("Include cycle in {}: include::{}[] skipped", from, target);
            return null;
        }
        // "../../.." in a target must not read files outside the docs module
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            missing.incrementAndGet();
            log.warn("Include not found in {}: include::{}[] -> {}", from, target, path);
            return null;
        }
        resolved.incrementAndGet();
        return resolve(path, stripFinalNewline(read(path)), root, depth + 1, openFiles);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read include " + file, e);
        }
    }

    /** A file normally ends with "\n"; inserted as is, it would add an empty line after every include. */
    private static String stripFinalNewline(String text) {
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
