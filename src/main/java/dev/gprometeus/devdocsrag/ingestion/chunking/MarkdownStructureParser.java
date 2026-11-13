package dev.gprometeus.devdocsrag.ingestion.chunking;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Line;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown document into sections by ATX headings and each section into blocks:
 * paragraphs (runs of non-blank lines, lists included) and fenced blocks (code, or TABLE for {@code ```table}).
 * Which lines are code is decided only by {@link MarkdownFences}, so a "# comment" inside a block is never a heading.
 *
 * <p>Not supported, on purpose: setext headings ("Title\n====="), indented code blocks, block quotes.
 * Our Markdown is produced by AsciidocConverter and IssueTextBuilder, which emit only ATX headings and fences.
 *
 * <p>An h1 that repeats the title replaces it at the start of the path instead of following it:
 * docs pages take their title from the h1 ("# Ollama Chat"), and issue texts start with
 * "# Issue #3368: &lt;title&gt;". Without this every path would begin with the same name twice,
 * and the duplicate would cost tokens in every chunk header for nothing.
 *
 * <p>Stateless and thread-safe: all parsing state lives in a per-call {@link Builder}.
 */
@Component
public class MarkdownStructureParser {

    static final String TABLE_LANGUAGE = "table";

    // CommonMark ATX heading: up to 3 spaces, 1-6 '#', then a space/tab or end of line ("#5" is not a heading).
    // Group 1: the hashes, group 2: the raw heading content, still with an optional closing "###" sequence
    private static final Pattern ATX_HEADING = Pattern.compile(" {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*");
    // Closing sequence: hashes preceded by a space/tab (or making up the whole content: "# ###" is an empty heading)
    private static final Pattern CLOSING_HASHES = Pattern.compile("(?:^|[ \\t]+)#+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // "Issue #3368: <title>": a prefix before ": " still makes the h1 a copy of the title
    private static final String PREFIX_SEPARATOR = ": ";

    private final TokenCounter tokenCounter;

    public MarkdownStructureParser(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    /**
     * @param title    document title, the root of every heading path
     * @param markdown document text; "\n" line endings expected (a trailing "\r" is tolerated, not stripped from code)
     * @return sections in document order; the level-0 section of the text before the first heading is
     *         present only if that text has blocks, every heading section is present even without blocks
     */
    public List<Section> parse(String title, String markdown) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(markdown, "markdown");
        Builder builder = new Builder(title);
        for (Line line : MarkdownFences.scan(markdown)) {
            builder.accept(line);
        }
        return builder.finish();
    }

    /** Heading text without the hashes, the surrounding spaces and the optional closing sequence; null if not a heading. */
    static Heading heading(String line) {
        Matcher m = ATX_HEADING.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String content = m.group(2) == null ? "" : m.group(2);
        String text = CLOSING_HASHES.matcher(content).replaceFirst("").strip();
        return new Heading(m.group(1).length(), text);
    }

    record Heading(int level, String text) {
    }

    /** "   ````java title=x" -> "````": the fence characters only, as long as the opening run, without indentation. */
    static String fenceRun(String openingLine) {
        String stripped = openingLine.stripLeading();
        char fenceChar = stripped.charAt(0);
        int end = 0;
        while (end < stripped.length() && stripped.charAt(end) == fenceChar) {
            end++;
        }
        return stripped.substring(0, end);
    }

    /**
     * True if {@code h1} says nothing beyond {@code title}: equal to it, or "&lt;prefix&gt;: &lt;title&gt;".
     * Compared case-insensitively with whitespace collapsed. A blank title is always replaced: the h1 is better than nothing.
     * Only the exact suffix after ": " counts, not any substring: "Chat Memory" must not swallow the title "Chat".
     */
    static boolean repeatsTitle(String h1, String title) {
        String h = normalize(h1);
        String t = normalize(title);
        return t.isEmpty() || h.equals(t) || h.endsWith(PREFIX_SEPARATOR + t);
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Mutable state of one parse call. */
    private final class Builder {

        private final String title;
        private final List<Section> sections = new ArrayList<>();
        // Open headings from the outermost; levels strictly increase from bottom to top, but may skip ("#" then "###")
        private final Deque<Heading> headings = new ArrayDeque<>();

        private int level;                                        // of the section being filled, 0 = before first heading
        private List<Block> blocks = new ArrayList<>();
        private final List<String> pending = new ArrayList<>();   // lines of the paragraph or fenced block being read
        private String fenceInfo;                                  // non-null while reading a fenced block

        Builder(String title) {
            this.title = title;
        }

        void accept(Line line) {
            switch (line.kind()) {
                case FENCE_OPEN -> {
                    flush();   // a fence interrupts a paragraph even without a blank line before it
                    fenceInfo = line.language();
                    pending.add(line.text());
                }
                case CODE -> pending.add(line.text());
                case FENCE_CLOSE -> {
                    pending.add(line.text());
                    flush();
                }
                case TEXT -> acceptText(line.text());
            }
        }

        private void acceptText(String text) {
            if (text.isBlank()) {
                flush();
                return;
            }
            Heading heading = heading(text);
            if (heading == null) {
                pending.add(text);
                return;
            }
            flush();
            closeSection();
            // Trim the stack to level N-1: pop every heading of level >= N. Comparing levels rather than
            // stack depth handles skipped levels: after "#", "###" the stack is [h1, h3], and "##" pops only h3
            while (!headings.isEmpty() && headings.peekLast().level() >= heading.level()) {
                headings.removeLast();
            }
            headings.addLast(heading);
            level = heading.level();
        }

        List<Section> finish() {
            // An unclosed fenced block runs to the end of the text, as in MarkdownFences. It gets a closing fence,
            // so every CODE/TABLE block is a complete fenced block: a chunk quoted as Markdown in an answer
            // must not turn everything after it into code
            if (fenceInfo != null && !pending.isEmpty()) {
                pending.add(fenceRun(pending.getFirst()));
            }
            flush();
            closeSection();
            return sections;
        }

        /** Turns the pending lines into one block; a no-op if there are none. */
        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            String text = String.join("\n", pending);
            BlockType type = fenceInfo == null ? BlockType.PARAGRAPH
                    : TABLE_LANGUAGE.equals(fenceInfo) ? BlockType.TABLE
                    : BlockType.CODE;
            blocks.add(new Block(type, text, fenceInfo, tokenCounter.count(text)));
            pending.clear();
            fenceInfo = null;
        }

        private void closeSection() {
            // The level-0 section has no heading of its own: without text it carries nothing, since its path
            // [title] is already the prefix of every other path. A heading section is kept even when empty
            if (level > 0 || !blocks.isEmpty()) {
                sections.add(new Section(currentPath(), level, blocks));
            }
            blocks = new ArrayList<>();
        }

        private List<String> currentPath() {
            List<String> path = new ArrayList<>(headings.size() + 1);
            Heading outermost = headings.peekFirst();
            // Only an h1 at the bottom of the stack: a "## Overview" under no h1 never replaces the title
            if (outermost == null || outermost.level() != 1 || !repeatsTitle(outermost.text(), title)) {
                path.add(title);
            }
            for (Heading h : headings) {   // ArrayDeque iterates from first (outermost) to last
                path.add(h.text());
            }
            return path;
        }
    }
}
