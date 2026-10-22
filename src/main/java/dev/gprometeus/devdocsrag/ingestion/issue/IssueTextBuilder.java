package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.github.IssueComment;
import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders an issue snapshot as plain text for embedding and citation.
 * <p>
 * Returns empty when nothing but the header is left after cleaning, so header-only
 * documents never reach the index.
 * <p>
 * The output must be deterministic: it is hashed into {@code content_hash}, so any variation
 * between runs (time, ordering, locale) would trigger a re-index of every issue.
 * Role labels follow author_association as of the snapshot, so a commenter's promotion
 * legitimately re-indexes their issues.
 */
@Component
public class IssueTextBuilder {

    private static final int MAX_STACK_FRAMES = 3;
    private static final int MIN_COMMENT_LENGTH = 20;
    private static final String BOT = "Bot";
    private static final Set<String> MAINTAINER_ASSOCIATIONS = Set.of("MEMBER", "OWNER", "COLLABORATOR");

    // Reluctant .*? stops at the first -->, DOTALL lets a comment span multiple lines
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    // Indentation is optional: pasted traces often lose it. "\S+\(" keeps prose lines from matching
    private static final Pattern STACK_FRAME = Pattern.compile("([ \\t]*)at \\S+\\(.*\\).*");
    // Markdown allows up to three spaces of indentation before a quote marker or a code fence
    private static final Pattern QUOTE_LINE = Pattern.compile(" {0,3}>.*");
    private static final Pattern CODE_FENCE = Pattern.compile(" {0,3}(?:```|~~~).*");
    private static final Pattern TRAILING_WHITESPACE = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n{3,}");

    private static final Comparator<IssueComment> BY_CREATED_AT =
            Comparator.comparing(IssueComment::createdAt, Comparator.nullsLast(Comparator.naturalOrder()));

    public Optional<String> build(IssueSnapshot issue) {
        List<String> blocks = new ArrayList<>();

        String body = clean(issue.body());
        if (!body.isEmpty()) {
            blocks.add(body);
        }

        // List.sort is stable, so comments with equal timestamps keep their snapshot order
        List<IssueComment> comments = new ArrayList<>(issue.comments());
        comments.sort(BY_CREATED_AT);
        for (IssueComment comment : comments) {
            String block = commentBlock(comment);
            if (block != null) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        blocks.addFirst(header(issue));
        return Optional.of(String.join("\n\n", blocks));
    }

    private static String header(IssueSnapshot issue) {
        StringBuilder header = new StringBuilder("Issue #").append(issue.number());
        String title = issue.title() == null ? "" : issue.title().strip();
        if (!title.isEmpty()) {
            header.append(": ").append(title);
        }
        if (!issue.labels().isEmpty()) {
            // GitHub returns labels in assignment order; String order is locale-independent, so the hash stays stable
            header.append("\nLabels: ").append(issue.labels().stream().sorted().collect(Collectors.joining(", ")));
        }
        if (issue.closedAt() != null) {
            // LocalDate.toString() is always ISO yyyy-MM-dd, independent of the default locale
            header.append("\nClosed: ").append(issue.closedAt().atZone(ZoneOffset.UTC).toLocalDate());
        }
        return header.toString();
    }

    /** Returns null when the comment is skipped as bot output or noise. */
    private static String commentBlock(IssueComment comment) {
        if (BOT.equals(comment.authorType())) {
            return null;
        }
        String text = clean(comment.body());
        String role = role(comment.authorAssociation());
        if (text.isEmpty() || (text.length() < MIN_COMMENT_LENGTH && !"maintainer".equals(role))) {
            return null;
        }
        return "Comment (" + role + "):\n" + text;
    }

    private static String role(String authorAssociation) {
        if (authorAssociation == null) {
            return "user";
        }
        if (MAINTAINER_ASSOCIATIONS.contains(authorAssociation)) {
            return "maintainer";
        }
        return "CONTRIBUTOR".equals(authorAssociation) ? "contributor" : "user";
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace("\r\n", "\n");
        result = HTML_COMMENT.matcher(result).replaceAll("");
        // Quotes go first so that a frame run interrupted by a quoted line is trimmed as one run
        result = removeQuotes(result);
        result = trimStackTraces(result);
        // Before collapsing: whitespace-only lines must already be empty to count as blank
        result = TRAILING_WHITESPACE.matcher(result).replaceAll("");
        result = EXTRA_BLANK_LINES.matcher(result).replaceAll("\n\n");
        return result.strip();
    }

    /**
     * Drops Markdown quote lines: on GitHub they almost always repeat an earlier message that is
     * already part of the document. Lines inside code fences are kept, because there {@code >} starts
     * Gradle output ({@code > Could not resolve ...}) or curl request headers rather than a quote.
     */
    private static String removeQuotes(String text) {
        List<String> kept = new ArrayList<>();
        boolean inCodeBlock = false;
        for (String line : text.split("\n", -1)) {
            if (CODE_FENCE.matcher(line).matches()) {
                inCodeBlock = !inCodeBlock;
            } else if (!inCodeBlock && QUOTE_LINE.matcher(line).matches()) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    /** Keeps the first frames of each run of "at ..." lines and replaces the rest with a single marker line. */
    private static String trimStackTraces(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder(text.length());
        int frames = 0;
        int trimmed = 0;
        String indent = "";
        for (String line : lines) {
            Matcher frame = STACK_FRAME.matcher(line);
            if (frame.matches()) {
                frames++;
                if (frames <= MAX_STACK_FRAMES) {
                    indent = frame.group(1);
                    result.append(line).append('\n');
                } else {
                    trimmed++;
                }
                continue;
            }
            appendTrimmedMarker(result, indent, trimmed);
            frames = 0;
            trimmed = 0;
            result.append(line).append('\n');
        }
        appendTrimmedMarker(result, indent, trimmed);
        result.setLength(result.length() - 1);   // drop the separator after the last line
        return result.toString();
    }

    private static void appendTrimmedMarker(StringBuilder result, String indent, int trimmed) {
        if (trimmed > 0) {
            result.append(indent).append("... ").append(trimmed).append(" more lines trimmed\n");
        }
    }
}
