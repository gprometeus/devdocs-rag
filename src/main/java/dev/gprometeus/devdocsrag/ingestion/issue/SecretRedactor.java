package dev.gprometeus.devdocsrag.ingestion.issue;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials and e-mail addresses in text before it is stored in the knowledge base.
 * <p>
 * Rules run in order, most specific first, and the result is idempotent: labels inserted by one
 * rule never match another rule, so redacting already redacted text changes nothing.
 */
@Component
public class SecretRedactor {

    // A key must not be glued to a preceding identifier, otherwise "risk-assessment-..." contains "sk-..."
    private static final String NOT_AFTER_ID = "(?<![A-Za-z0-9_-])";

    private static final List<Rule> RULES = List.of(
            // Before the OpenAI rule: every Anthropic key also starts with "sk-"
            Rule.literal(NOT_AFTER_ID + "sk-ant-[A-Za-z0-9_-]{20,}", "[REDACTED_ANTHROPIC_KEY]"),
            Rule.literal(NOT_AFTER_ID + "sk-(?:proj-)?[A-Za-z0-9_-]{20,}", "[REDACTED_OPENAI_KEY]"),
            Rule.literal(NOT_AFTER_ID + "gh[pousr]_[A-Za-z0-9]{36,}", "[REDACTED_GITHUB_TOKEN]"),
            Rule.literal(NOT_AFTER_ID + "github_pat_[A-Za-z0-9_]{20,}", "[REDACTED_GITHUB_TOKEN]"),
            Rule.literal("(?<![A-Z0-9])AKIA[A-Z0-9]{16}(?![A-Z0-9])", "[REDACTED_AWS_KEY]"),
            // Token alphabet covers JWT and base64; "[" is outside it, so a redacted label is never matched again
            Rule.template("\\b((?i:bearer)[ \\t]+)[A-Za-z0-9._~+/=-]{20,}", "$1[REDACTED_TOKEN]"),
            Rule.template(keyValuePattern(), "$1[REDACTED]"),
            // The domain must start with a letter, which skips Object.toString() output like "Service@37b52340",
            // and git@ is the SSH user of clone URLs, not a person
            Rule.literal("(?<![A-Za-z0-9._%+-])(?!git@)[A-Za-z0-9._%+-]+@[A-Za-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}",
                    "[REDACTED_EMAIL]"));

    /** Replaces detected secrets with {@code [REDACTED_...]} labels. */
    public String redact(String text) {
        Objects.requireNonNull(text, "text");
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /**
     * Matches config-style {@code key: value} and {@code key=value} pairs for secret-like keys and keeps
     * the key, the separator and an opening quote.
     * <ul>
     *   <li>The key may follow "_", "." or "-" ({@code DB_PASSWORD}, {@code client-secret}) but must be
     *       followed directly by the separator, optionally through a closing quote as in JSON
     *       ({@code "password": "x"}), so {@code max-tokens: 512} is left alone.</li>
     *   <li>"=" must not be preceded by a space: {@code password=x} is config, {@code String token = get()}
     *       and {@code token == null} are code.</li>
     *   <li>A quoted value is masked up to the closing quote and must not start with a space: in
     *       {@code "Token: " + token} the quote closes a string literal rather than opening a value.
     *       An unquoted value must not be a call
     *       ({@code request.getHeader(...)}), a log placeholder ({@code {}}) or a boolean/null literal.</li>
     *   <li>Placeholders ({@code ${OPENAI_API_KEY}}, {@code <your-key>}) and existing labels are not secrets.</li>
     * </ul>
     */
    private static String keyValuePattern() {
        return "(?i)"
                + "((?<![A-Za-z0-9])(?:password|passwd|secret|api[-_]?key|token)[\"']?(?::[ \\t]*|=[ \\t]*)[\"']?)"
                + "(?!\\$\\{|<|\\[REDACTED|=|(?:true|false|null)\\b)"
                + "(?:(?<=\")[^\\s\"][^\"\\n]*(?=\")|(?<=')[^\\s'][^'\\n]*(?=')|(?<![\"'])[^\\s\"'`,;(){}]++(?!\\())";
    }

    private record Rule(Pattern pattern, String replacement) {

        /** The replacement is inserted as is: "$" and "\" have no special meaning. */
        static Rule literal(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), Matcher.quoteReplacement(replacement));
        }

        /** The replacement may reference groups as {@code $1}. */
        static Rule template(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), replacement);
        }
    }
}
