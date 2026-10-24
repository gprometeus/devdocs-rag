package dev.gprometeus.devdocsrag.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Markdown into lines and tells for each one whether it belongs to a fenced code block,
 * following the CommonMark rules for fences:
 * <ul>
 *   <li>an opening fence is up to 3 spaces, then 3+ backticks or tildes, then an optional info string
 *       (a backtick info string must not contain a backtick);</li>
 *   <li>a closing fence uses the same character, is at least as long as the opening one and has nothing
 *       but spaces after it;</li>
 *   <li>everything else inside a block is content, including lines like "```java";</li>
 *   <li>a block that is not closed runs to the end of the text.</li>
 * </ul>
 * One shared parser, because every consumer that decides "is this line code?" on its own gets
 * nested fences ("````" around "```java") wrong in its own way.
 */
public final class MarkdownFences {

    public enum Kind { TEXT, FENCE_OPEN, CODE, FENCE_CLOSE }

    /**
     * @param language first word of the info string of the enclosing block, "" for a block without one,
     *                 null for {@link Kind#TEXT} lines
     */
    public record Line(String text, Kind kind, String language) {

        /** Fence lines and content alike: everything that is not plain text. */
        public boolean inBlock() {
            return kind != Kind.TEXT;
        }
    }

    // Group 1: the fence run, group 2: the info string. A tab is not allowed in the indentation:
    // CommonMark counts it as 4 spaces, and a 4-space indent is an indented code line, not a fence
    private static final Pattern OPENING_FENCE = Pattern.compile(" {0,3}(`{3,}|~{3,})(.*)");

    private MarkdownFences() {
    }

    /** One {@link Line} per line of {@code text}; joining their texts with "\n" gives {@code text} back. */
    public static List<Line> scan(String text) {
        List<Line> lines = new ArrayList<>();
        String fence = null;      // opening run of the current block, null outside a block
        String language = null;
        for (String line : text.split("\n", -1)) {
            if (fence == null) {
                Matcher opening = OPENING_FENCE.matcher(line);
                if (opening.matches() && isValidInfo(opening.group(1), opening.group(2))) {
                    fence = opening.group(1);
                    language = firstWord(opening.group(2));
                    lines.add(new Line(line, Kind.FENCE_OPEN, language));
                } else {
                    lines.add(new Line(line, Kind.TEXT, null));
                }
            } else if (isClosing(line, fence)) {
                lines.add(new Line(line, Kind.FENCE_CLOSE, language));
                fence = null;
                language = null;
            } else {
                lines.add(new Line(line, Kind.CODE, language));
            }
        }
        return lines;
    }

    /** "``` a`b" is inline code that happens to start a line, not a fence. Tildes allow any info string. */
    private static boolean isValidInfo(String fence, String info) {
        return fence.charAt(0) != '`' || info.indexOf('`') < 0;
    }

    private static boolean isClosing(String line, String openingFence) {
        int i = 0;
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        char fenceChar = openingFence.charAt(0);
        int runStart = i;
        while (i < line.length() && line.charAt(i) == fenceChar) {
            i++;
        }
        // Only spaces may follow; an info string after the run makes it content ("```java" inside "````")
        return i - runStart >= openingFence.length() && line.substring(i).isBlank();
    }

    private static String firstWord(String info) {
        String stripped = info.strip();
        int space = 0;
        while (space < stripped.length() && !Character.isWhitespace(stripped.charAt(space))) {
            space++;
        }
        return stripped.substring(0, space);
    }
}
