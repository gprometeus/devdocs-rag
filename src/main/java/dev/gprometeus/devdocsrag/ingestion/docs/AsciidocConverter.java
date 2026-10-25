package dev.gprometeus.devdocsrag.ingestion.docs;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts AsciiDoc markup to the Markdown subset the chunker understands:
 * "#" headings, fenced code blocks and fenced tables.
 * <p>
 * A line-by-line state machine: every source line is classified exactly once, so a line produced by one
 * rule (e.g. "[image]") is never seen by another (e.g. "drop attribute lines").
 * The output must be deterministic: it is hashed into {@code content_hash}.
 */
@Component
public class AsciidocConverter {

    private enum State { TEXT, CODE, TABLE, COMMENT }

    private static final String FENCE = "```";
    private static final String IMAGE_MARKER = "[image]";

    private static final Pattern COMMENT_BLOCK = Pattern.compile("/{4,}");
    // Listing (----) or literal (....) block
    private static final Pattern CODE_DELIMITER = Pattern.compile("-{4,}|\\.{4,}");
    // Asciidoctor accepts Markdown fences as well; the docs use them for ~20 code samples
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```\\s*(\\S*)");
    private static final Pattern TABLE_DELIMITER = Pattern.compile("\\|={3,}");
    // Example (====), sidebar (****) and open (--) blocks: only the content matters
    private static final Pattern MEANINGLESS_DELIMITER = Pattern.compile("={4,}|\\*{4,}|--");
    private static final Pattern HEADING = Pattern.compile("(={1,6})\\s+(.+)");
    private static final Pattern HEADING_ANCHOR = Pattern.compile("\\s*(?:\\[\\[[^]]*]]|\\[#[^]]*])\\s*$");
    // Any block attribute line: [source,java], [cols="2,5"], [[anchor]], [#id], [tabs], [NOTE]
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("\\[.*]");
    // The language is the first positional attribute after "source"; "[source, xml]" occurs too.
    // Possessive *+: the language never gives characters back to ".*" (which can match them anyway),
    // so an unclosed "[source,..." fails in linear time instead of trying every split
    private static final Pattern SOURCE_ATTRIBUTE = Pattern.compile("\\[source(?:,([^,\\]]*+))?.*]");
    private static final Pattern BLOCK_ADMONITION = Pattern.compile("\\[(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]");
    private static final Pattern INLINE_ADMONITION = Pattern.compile("(NOTE|TIP|IMPORTANT|WARNING|CAUTION):\\s+(.*)");
    // ".Title" but not ". list item" and not "...." (handled earlier as a literal block)
    private static final Pattern BLOCK_TITLE = Pattern.compile("\\.([^.\\s].*)");
    private static final Pattern PAGE_ATTRIBUTE = Pattern.compile(":!?[\\w-]+!?:.*");
    // "image::", then up to the first "[", then anything up to a final "]". "(?:(?!\[).)*+" is "." that stops
    // at the first "[": unlike [^\[] it still refuses line terminators, exactly as ".*" did
    private static final Pattern IMAGE_BLOCK = Pattern.compile("image::(?:(?!\\[).)*+\\[.*]");
    private static final Pattern TOC = Pattern.compile("toc::\\[.*]");
    // "term::" or "term:: text". The term has no ':' and "::" is followed by whitespace or the line end,
    // so "String::valueOf" and "include::x[]" stay untouched
    private static final Pattern DESCRIPTION_TERM = Pattern.compile("([^:\\s][^:]*?)::(?:\\s+(.*))?");

    // image:file.png[...] inside text, and image::file.png[...] after a cell style in a table ("^a| image::...");
    // "the test.png image:" in prose has no target and does not match
    private static final Pattern INLINE_IMAGE = Pattern.compile("image::?[^\\s\\[]+\\[[^]]*]");
    private static final Pattern XREF = Pattern.compile("xref:([^\\[\\s]+)\\[([^]]*)]");
    // <<id>> or <<id,text>>. The target may be a section title with spaces ("<<Structured Outputs>>"),
    // so it is "a letter or _ (generated ids like _known_limitations), then anything but < > ,";
    // "cat <<EOF" has no closing >> and does not match.
    // Possessive quantifiers: the id can only end at ",", "<" or ">", and the text only at ">", so giving
    // characters back never helps. Spaces after the comma belong to the text, which is stripped on use
    private static final Pattern INTERNAL_XREF = Pattern.compile("<<([A-Za-z_][^,<>]*+)(?:,([^>]++))?>>");
    // The target of link: may be relative ("link:../chat/ollama-chat.html[...]"), so any scheme is optional
    private static final Pattern LINK_MACRO = Pattern.compile("link:([^\\[\\s]+)\\[([^]]*)]");
    // A bare url needs a scheme; a url without brackets does not match and stays as is
    private static final Pattern BARE_URL = Pattern.compile("((?:https?|ftp|mailto):[^\\[\\s]+)\\[([^]]*)]");

    // `+value+` renders value verbatim; the pluses would end up in LLM answers as part of the value.
    // Only inside backticks: a bare + is C++, a + b or Ctrl+C
    private static final Pattern PASSTHROUGH = Pattern.compile("`\\+([^`]+)\\+`");

    // A backtick fence inside code, e.g. a Markdown sample in a listing; CommonMark allows up to 3 spaces before it
    private static final Pattern BACKTICK_RUN = Pattern.compile(" {0,3}(`{3,})");

    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\n{3,}");

    // (?s): same as startsWith("//"), a stray '\r' inside the line must not stop the match
    private static final Pattern COMMENT_LINE = Pattern.compile("(?s)//.*");
    private static final Pattern LIST_CONTINUATION = Pattern.compile("\\+");

    /** A TEXT line rule: the first rule whose pattern matches the whole line decides what the line becomes. */
    private record LineRule(Pattern pattern, Function<Matcher, Optional<String>> convert) {

        static LineRule drop(Pattern pattern) {
            return new LineRule(pattern, m -> Optional.empty());
        }

        static LineRule replace(Pattern pattern, Function<Matcher, String> convert) {
            return new LineRule(pattern, convert.andThen(Optional::of));
        }
    }

    // Must stay below the patterns: static fields are initialized in textual order, a rule declared earlier
    // would hold a null pattern.
    // Order matters: "...." (a code block, see Conversion) before ".Title", "[source]" (Conversion) and
    // "[NOTE]" before any "[...]", "toc::[]" before "term::". Lines that change the state are handled
    // by Conversion before these rules.
    private static final List<LineRule> TEXT_RULES = List.of(
            LineRule.drop(COMMENT_LINE),
            LineRule.replace(HEADING, AsciidocConverter::heading),
            // Example, sidebar and open block delimiters and list continuations carry no content
            LineRule.drop(MEANINGLESS_DELIMITER),
            LineRule.drop(LIST_CONTINUATION),
            LineRule.replace(BLOCK_ADMONITION, m -> capitalize(m.group(1)) + ":"),
            // [cols=...], [[anchor]], [#id], [tabs], [.lead]
            LineRule.drop(ATTRIBUTE_LINE),
            LineRule.replace(BLOCK_TITLE, m -> "**" + convertInline(m.group(1)) + "**"),
            // :toc:, :toclevels: 3, toc::[]
            LineRule.drop(PAGE_ATTRIBUTE),
            LineRule.drop(TOC),
            LineRule.replace(IMAGE_BLOCK, m -> IMAGE_MARKER),
            LineRule.replace(INLINE_ADMONITION, m -> capitalize(m.group(1)) + ": " + convertInline(m.group(2))),
            LineRule.replace(DESCRIPTION_TERM, AsciidocConverter::descriptionTerm));

    /**
     * Converts AsciiDoc markup to Markdown. Includes are already resolved by the loader. Curly braces are
     * never touched: in this documentation they are prompt template placeholders, not attributes.
     */
    public String convert(String asciidoc) {
        Conversion conversion = new Conversion();
        for (String line : asciidoc.replace("\r\n", "\n").split("\n", -1)) {
            conversion.accept(line.stripTrailing());
        }
        return conversion.finish();
    }

    /** Mutable state of one {@link #convert} call; the component itself stays stateless and thread-safe. */
    private static final class Conversion {

        private final List<String> out = new ArrayList<>();
        // Code is buffered until the block ends: the fence length depends on what is inside
        private final List<String> codeLines = new ArrayList<>();
        private State state = State.TEXT;
        private String closingDelimiter;   // exact line that ends the current block
        private String language = "";      // remembered from [source,...] until the next code delimiter
        private String codeLanguage = "";  // language of the current code block

        void accept(String line) {
            switch (state) {
                case CODE -> inCode(line);
                case TABLE -> inTable(line);
                case COMMENT -> inComment(line);
                case TEXT -> inText(line);
            }
        }

        String finish() {
            // A file that ends inside a block still yields a balanced fence for the chunker
            if (state == State.CODE) {
                addCodeBlock();
            } else if (state == State.TABLE) {
                out.add(FENCE);
            }
            String result = String.join("\n", out);
            result = EXTRA_BLANK_LINES.matcher(result).replaceAll("\n\n");
            return result.strip();
        }

        private void inCode(String line) {
            if (line.equals(closingDelimiter)) {
                addCodeBlock();
                state = State.TEXT;
            } else {
                codeLines.add(line);
            }
        }

        private void inTable(String line) {
            if (line.equals(closingDelimiter)) {
                out.add(FENCE);
                state = State.TEXT;
            } else if (!line.isBlank()) {
                // A table is documentation text laid out in cells, not code: inline rules apply,
                // line rules do not ("== x" or "// x" in a cell is cell content)
                out.add(convertInline(line));
            }
        }

        private void inComment(String line) {
            if (line.equals(closingDelimiter)) {
                state = State.TEXT;
            }
        }

        private void inText(String line) {
            if (startBlock(line) || rememberSourceLanguage(line)) {
                return;
            }
            convertTextLine(line).ifPresent(out::add);
        }

        /** Comment, code (----, ...., ```) or table start: switches the state and returns true. */
        private boolean startBlock(String line) {
            // "////" before the "//" line comment in TEXT_RULES
            if (COMMENT_BLOCK.matcher(line).matches()) {
                enter(State.COMMENT, line);
                return true;
            }
            if (CODE_DELIMITER.matcher(line).matches()) {
                openCode(language, line);
                return true;
            }
            Matcher fence = MARKDOWN_FENCE.matcher(line);
            if (fence.matches()) {
                openCode(fence.group(1), FENCE);
                return true;
            }
            if (TABLE_DELIMITER.matcher(line).matches()) {
                out.add(FENCE + "table");
                enter(State.TABLE, line);
                return true;
            }
            return false;
        }

        private boolean rememberSourceLanguage(String line) {
            Matcher source = SOURCE_ATTRIBUTE.matcher(line);
            if (!source.matches()) {
                return false;
            }
            language = source.group(1) == null ? "" : source.group(1).strip();
            return true;
        }

        private void openCode(String blockLanguage, String closing) {
            codeLanguage = blockLanguage;
            // A [source] line applies to the next code block only
            language = "";
            codeLines.clear();
            enter(State.CODE, closing);
        }

        private void enter(State blockState, String closing) {
            state = blockState;
            closingDelimiter = closing;
        }

        /**
         * Emits the buffered code block. CommonMark closes a fence only with one at least as long, so when
         * the code itself contains a ``` line (a Markdown sample), the fence is made one backtick longer than
         * the longest inner run.
         */
        private void addCodeBlock() {
            int longestRun = 0;
            for (String line : codeLines) {
                Matcher run = BACKTICK_RUN.matcher(line);
                if (run.lookingAt()) {
                    longestRun = Math.max(longestRun, run.group(1).length());
                }
            }
            String fence = "`".repeat(Math.max(FENCE.length(), longestRun + 1));
            out.add(fence + codeLanguage);
            out.addAll(codeLines);
            out.add(fence);
        }

        /** First matching rule; a line no rule knows is plain text with inline markup. */
        private static Optional<String> convertTextLine(String line) {
            for (LineRule rule : TEXT_RULES) {
                Matcher m = rule.pattern().matcher(line);
                if (m.matches()) {
                    return rule.convert().apply(m);
                }
            }
            return Optional.of(convertInline(line));
        }
    }

    private static String heading(Matcher m) {
        String title = HEADING_ANCHOR.matcher(m.group(2)).replaceAll("");
        return "#".repeat(m.group(1).length()) + " " + convertInline(title);
    }

    private static String descriptionTerm(Matcher m) {
        String text = m.group(2) == null ? "" : " " + convertInline(m.group(2));
        return m.group(1) + ":" + text;
    }

    /** Keeps link text only; an empty text falls back to the target. */
    private static String convertInline(String text) {
        String result = unwrapPassthrough(text);
        // Images before links: in image:https://host/x.png[] the url would otherwise be taken by BARE_URL
        result = INLINE_IMAGE.matcher(result).replaceAll(Matcher.quoteReplacement(IMAGE_MARKER));
        result = XREF.matcher(result).replaceAll(m -> Matcher.quoteReplacement(
                m.group(2).isBlank() ? xrefTarget(m.group(1)) : m.group(2)));
        result = INTERNAL_XREF.matcher(result).replaceAll(m -> Matcher.quoteReplacement(
                m.group(2) == null ? m.group(1) : m.group(2).strip()));
        // link: goes first, otherwise BARE_URL would leave "link:" in front of the text
        result = LINK_MACRO.matcher(result).replaceAll(AsciidocConverter::textOrTarget);
        return BARE_URL.matcher(result).replaceAll(AsciidocConverter::textOrTarget);
    }

    private static String textOrTarget(MatchResult m) {
        String text = m.group(2);
        // quoteReplacement: link text such as "costs $1" must not be read as a group reference
        return Matcher.quoteReplacement(text.isBlank() ? m.group(1) : text);
    }

    private static String unwrapPassthrough(String text) {
        // "$1" is safe here: the group text is inserted as is, quoteReplacement only matters for literal strings
        return PASSTHROUGH.matcher(text).replaceAll("`$1`");
    }

    /** "api/chat.adoc#_options" → "api/chat.adoc"; a pure "#anchor" target is kept as is. */
    private static String xrefTarget(String target) {
        int hash = target.indexOf('#');
        return hash > 0 ? target.substring(0, hash) : target;
    }

    private static String capitalize(String word) {
        // Locale.ROOT: the default locale must not change the output (e.g. Turkish dotless i)
        return word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT);
    }
}
