package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.github.IssueComment;
import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueTextBuilderTest {

    private static final Instant CLOSED_AT = Instant.parse("2025-08-12T23:30:00Z");

    private final IssueTextBuilder builder = new IssueTextBuilder();

    @Test
    void rendersHeaderBodyAndCommentsSeparatedByBlankLines() {
        IssueSnapshot issue = issue("Starter docs are unclear\r\n\r\nWhich starter should I use?", List.of("documentation", "mcp"),
                comment("MEMBER", "User", "Use the WebMVC starter for servlet apps.", "2025-08-02T10:00:00Z"),
                comment("NONE", "User", "Thanks, that solved my problem completely!", "2025-08-03T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).isEqualTo("""
                Issue #3511: MCP Server starter selection is confusing
                Labels: documentation, mcp
                Closed: 2025-08-12

                Starter docs are unclear

                Which starter should I use?

                Comment (maintainer):
                Use the WebMVC starter for servlet apps.

                Comment (user):
                Thanks, that solved my problem completely!""");
    }

    @Test
    void omitsLabelsLineWhenIssueHasNoLabels() {
        IssueSnapshot issue = issue("Steps to reproduce the problem", List.of());

        assertThat(builder.build(issue).orElseThrow()).isEqualTo("""
                Issue #3511: MCP Server starter selection is confusing
                Closed: 2025-08-12

                Steps to reproduce the problem""");
    }

    @Test
    void sortsLabelsSoOrderInSnapshotDoesNotAffectText() {
        IssueSnapshot issue = issue("Body", List.of("mcp", "documentation", "bug"));

        assertThat(builder.build(issue).orElseThrow()).contains("\nLabels: bug, documentation, mcp\n");
    }

    @Test
    void emptyWhenBodyIsOnlyTemplateHints() {
        IssueSnapshot issue = issue("<!-- Please describe the bug -->\n   \n", List.of());

        assertThat(builder.build(issue)).isEmpty();
    }

    @Test
    void removesPlainTextTemplatePromptsButKeepsHeadersAndAuthorText() {
        IssueSnapshot issue = issue("""
                Please do a quick search on GitHub issues first, there might be already a duplicate issue for the one you are about to create.
                If the bug is trivial, just go ahead and create the issue. Otherwise, please take a few moments and fill in the following sections:

                **Bug description**
                A clear and concise description of what the bug is about.
                Two system messages are sent

                **Steps to reproduce**
                  Steps to reproduce the issue.\t
                1. Augment the system message
                """, List.of());

        assertThat(builder.build(issue).orElseThrow()).isEqualTo("""
                Issue #3511: MCP Server starter selection is confusing
                Closed: 2025-08-12

                **Bug description**
                Two system messages are sent

                **Steps to reproduce**
                1. Augment the system message""");
    }

    @Test
    void keepsLineThatOnlyStartsLikeTemplatePrompt() {
        IssueSnapshot issue = issue("A clear and concise description of what the bug is about: NPE on startup", List.of());

        assertThat(builder.build(issue).orElseThrow())
                .endsWith("\n\nA clear and concise description of what the bug is about: NPE on startup");
    }

    @Test
    void replacesMarkdownAndHtmlImagesWithMarker() {
        IssueSnapshot issue = issue("""
                I get this error:
                ![Image](https://github.com/user-attachments/assets/ca13550e-42c3-4e3b-8823-20c440aea9dd)
                Logs: <img width="790" height="101" alt="Image" src="https://github.com/user-attachments/assets/1" /> see above
                <IMG
                  alt="Image" src="https://github.com/user-attachments/assets/2">
                """, List.of());

        assertThat(builder.build(issue).orElseThrow()).endsWith("""


                I get this error:
                [image]
                Logs: [image] see above
                [image]""");
    }

    @Test
    void keepsRegularLinksAndTagsThatOnlyStartWithImg() {
        String body = "See [the docs](https://docs.spring.io/spring-ai) and List<Image> or <imgSource> in the API";
        IssueSnapshot issue = issue(body, List.of());

        assertThat(builder.build(issue).orElseThrow()).endsWith("\n\n" + body);
    }

    @Test
    void emptyWhenOnlyNoiseAndNoBody() {
        IssueSnapshot issue = issue(null, List.of(),
                comment("NONE", "User", "+1", "2025-08-01T10:00:00Z"),
                comment("NONE", "Bot", "Closed automatically because of inactivity", "2025-08-02T10:00:00Z"));

        assertThat(builder.build(issue)).isEmpty();
    }

    @Test
    void notEmptyWhenOnlyMaintainerShortAnswer() {
        IssueSnapshot issue = issue(null, List.of(),
                comment("MEMBER", "User", "Fixed in 1.0.1", "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue)).isPresent();
    }

    @Test
    void ordersCommentsByCreationTime() {
        IssueSnapshot issue = issue(null, List.of(),
                comment("CONTRIBUTOR", "User", "Second comment, posted later", "2025-08-05T10:00:00Z"),
                comment("COLLABORATOR", "User", "First comment, posted earlier", "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).endsWith("""
                Comment (maintainer):
                First comment, posted earlier

                Comment (contributor):
                Second comment, posted later""");
    }

    @Test
    void skipsBotsAndShortNonMaintainerComments() {
        IssueSnapshot issue = issue(null, List.of(),
                comment("NONE", "Bot", "This issue has been automatically marked as stale.", "2025-08-01T10:00:00Z"),
                comment("NONE", "User", "+1", "2025-08-02T10:00:00Z"),
                comment("CONTRIBUTOR", "User", "any update?", "2025-08-03T10:00:00Z"),
                comment("OWNER", "User", "Fixed in 1.0.1", "2025-08-04T10:00:00Z"),
                comment("MEMBER", "User", "<!-- empty -->", "2025-08-05T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).isEqualTo("""
                Issue #3511: MCP Server starter selection is confusing
                Closed: 2025-08-12

                Comment (maintainer):
                Fixed in 1.0.1""");
    }

    @Test
    void treatsCommentWithoutAssociationAsUser() {
        IssueSnapshot issue = issue(null, List.of(),
                comment(null, "User", "Same here, downgrading to 1.0.0 helped.", "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).endsWith("""
                Comment (user):
                Same here, downgrading to 1.0.0 helped.""");
    }

    @Test
    void skipsShortCommentWithoutAssociation() {
        IssueSnapshot issue = issue("Body", List.of(),
                comment(null, "User", "+1", "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).doesNotContain("Comment");
    }

    @Test
    void removesEachHtmlCommentSeparately() {
        IssueSnapshot issue = issue("<!-- multi\nline -->Keep this<!-- second -->", List.of());

        assertThat(builder.build(issue).orElseThrow()).endsWith("\n\nKeep this");
    }

    @Test
    void trimsEachStackTraceGroupToThreeFrames() {
        String trace = """
                java.lang.RuntimeException: boom
                    at a.A.m(A.java:1)
                    at b.B.m(B.java:2)
                    at c.C.m(C.java:3)
                    at d.D.m(D.java:4)
                    at e.E.m(E.java:5)
                Caused by: java.net.SocketTimeoutException
                    at f.F.m(F.java:6)""";

        assertThat(builder.build(issue(trace, List.of())).orElseThrow()).endsWith("""
                java.lang.RuntimeException: boom
                    at a.A.m(A.java:1)
                    at b.B.m(B.java:2)
                    at c.C.m(C.java:3)
                    ... 2 more lines trimmed
                Caused by: java.net.SocketTimeoutException
                    at f.F.m(F.java:6)""");
    }

    @Test
    void trimsStackTraceWithoutIndentation() {
        String trace = """
                at a.A.m(A.java:1)
                at b.B.m(B.java:2)
                at c.C.m(C.java:3)
                at d.D.m(D.java:4)""";

        assertThat(builder.build(issue(trace, List.of())).orElseThrow()).endsWith("""
                at c.C.m(C.java:3)
                ... 1 more lines trimmed""");
    }

    @Test
    void omitsColonWhenTitleIsBlank() {
        IssueSnapshot issue = new IssueSnapshot(3511, "  ", "Body text", null, "completed", List.of(),
                null, CLOSED_AT, List.of());

        assertThat(builder.build(issue).orElseThrow()).startsWith("Issue #3511\nClosed: 2025-08-12");
    }

    @Test
    void removesQuoteButKeepsOwnText() {
        String reply = """
                > @someone, this works for me.
                >
                > Sometimes I get timeout exceptions like this:
                > ```
                > java.net.SocketTimeoutException: Read timed out
                >     at java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)
                > ```

                Increase spring.ai.openai.read-timeout to fix it.""";
        IssueSnapshot issue = issue(null, List.of(),
                comment("NONE", "User", reply, "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).endsWith("""
                Comment (user):
                Increase spring.ai.openai.read-timeout to fix it.""");
    }

    @Test
    void keepsAngleBracketLinesInsideCodeBlocks() {
        String body = """
                Build fails:
                ```
                FAILURE: Build failed with an exception.
                > Could not resolve all files for configuration ':compileClasspath'.
                ```""";

        assertThat(builder.build(issue(body, List.of())).orElseThrow())
                .contains("> Could not resolve all files for configuration ':compileClasspath'.");
    }

    @Test
    void dropsCommentThatIsOnlyAQuote() {
        IssueSnapshot issue = issue("Body", List.of(),
                comment("MEMBER", "User", "> Quoted text only, nothing new", "2025-08-01T10:00:00Z"));

        assertThat(builder.build(issue).orElseThrow()).doesNotContain("Comment");
    }

    @Test
    void collapsesBlankLinesAndKeepsFormatSpecifiers() {
        IssueSnapshot issue = issue("Expected %d items, got %s\n\n\n \n\nDone", List.of());

        assertThat(builder.build(issue).orElseThrow()).endsWith("\n\nExpected %d items, got %s\n\nDone");
    }

    @Test
    void removesTrailingWhitespaceOnEveryLine() {
        IssueSnapshot issue = issue("Adjust the hints if needed. \t\n - [x] OpenAI  \n - [ ] Ollama", List.of());

        assertThat(builder.build(issue).orElseThrow()).endsWith("""

                Adjust the hints if needed.
                 - [x] OpenAI
                 - [ ] Ollama""");
    }

    private static IssueSnapshot issue(String body, List<String> labels, IssueComment... comments) {
        return new IssueSnapshot(3511, "MCP Server starter selection is confusing", body,
                "https://github.com/spring-projects/spring-ai/issues/3511", "completed", labels,
                Instant.parse("2025-07-30T10:00:00Z"), CLOSED_AT, List.of(comments));
    }

    private static IssueComment comment(String association, String type, String body, String createdAt) {
        return new IssueComment("someone", type, association, body, Instant.parse(createdAt));
    }
}
