package dev.gprometeus.devdocsrag.ingestion.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsciidocConverterTest {

    private final AsciidocConverter converter = new AsciidocConverter();

    // --- headings ---

    @Test
    void convertsHeadingsAndDropsAnchors() {
        String md = converter.convert("""
                = Ollama Chat
                [[prereq]]
                == Prerequisites [[prereq]]
                === Options [#options]""");
        assertThat(md).isEqualTo("""
                # Ollama Chat
                ## Prerequisites
                ### Options""");
    }

    // --- code blocks ---

    @Test
    void convertsSourceBlockWithLanguageAndOptions() {
        String md = converter.convert("""
                [source,yaml,indent=0]
                ----
                spring:
                  ai:
                ----""");
        assertThat(md).isEqualTo("""
                ```yaml
                spring:
                  ai:
                ```""");
    }

    @Test
    void stripsSpaceBeforeLanguage() {
        String md = converter.convert("""
                [source, xml]
                ----
                <a/>
                ----""");
        assertThat(md).isEqualTo("""
                ```xml
                <a/>
                ```""");
    }

    @Test
    void listingAndLiteralBlocksWithoutLanguage() {
        String md = converter.convert("""
                ----
                plain listing
                ----

                ....
                literal text
                ....""");
        assertThat(md).isEqualTo("""
                ```
                plain listing
                ```

                ```
                literal text
                ```""");
    }

    @Test
    void keepsCodeBlockContentUntouched() {
        String md = converter.convert("""
                [source,java]
                ----
                // == not a heading
                String s = "{not-an-attribute}";
                // xref:not-a-link.adoc[]
                ----""");
        assertThat(md)
                .contains("// == not a heading")
                .contains("{not-an-attribute}")
                .contains("// xref:not-a-link.adoc[]");
    }

    @Test
    void keepsMarkdownFencedCodeUntouched() {
        String md = converter.convert("""
                ```java
                // a comment, not dropped
                [source,java]
                ```""");
        assertThat(md).isEqualTo("""
                ```java
                // a comment, not dropped
                [source,java]
                ```""");
    }

    @Test
    void closingDelimiterMustHaveSameLength() {
        String md = converter.convert("""
                [source,text]
                ------
                ----
                still code
                ------""");
        assertThat(md).isEqualTo("""
                ```text
                ----
                still code
                ```""");
    }

    @Test
    void blockTitleBetweenSourceAndDelimiterBecomesBold() {
        String md = converter.convert("""
                [source,yaml]
                .application.yml
                ----
                key: value
                ----""");
        assertThat(md).isEqualTo("""
                **application.yml**
                ```yaml
                key: value
                ```""");
    }

    @Test
    void usesLongerFenceWhenCodeContainsBackticks() {
        String md = converter.convert("""
                [source,text]
                ----
                ```java
                code
                ```
                ----""");
        assertThat(md).isEqualTo("""
                ````text
                ```java
                code
                ```
                ````""");
    }

    @Test
    void closesUnterminatedCodeBlockWithLongerFence() {
        String md = converter.convert("""
                ----
                ````
                inner""");
        assertThat(md).isEqualTo("""
                `````
                ````
                inner
                `````""");
    }

    @Test
    void closesUnterminatedCodeBlock() {
        String md = converter.convert("""
                [source,java]
                ----
                int x = 1;""");
        assertThat(md).isEqualTo("""
                ```java
                int x = 1;
                ```""");
    }

    // --- tables ---

    @Test
    void convertsTableToFencedBlockWithoutBlankLines() {
        String md = converter.convert("""
                [cols="2,5"]
                |===
                | Property | Description

                | spring.ai.ollama.base-url | Base URL
                |===""");
        assertThat(md).isEqualTo("""
                ```table
                | Property | Description
                | spring.ai.ollama.base-url | Base URL
                ```""");
    }

    @Test
    void tableDelimiterMayHaveFourEquals() {
        String md = converter.convert("""
                [cols="6,3,1", stripes=even]
                |====
                | Property | Description | Default
                | spring.ai.chat.observations.log-prompt | Log the prompt | false
                |====""");
        assertThat(md).isEqualTo("""
                ```table
                | Property | Description | Default
                | spring.ai.chat.observations.log-prompt | Log the prompt | false
                ```""");
    }

    @Test
    void convertsLinksAndImagesInTableCells() {
        String md = converter.convert("""
                |===
                | link:https://x.io/client[MCP Client] | xref:#tool[Declarative]
                ^a| image::mcp/arch.jpg[Architecture, width=500]
                |===""");
        assertThat(md).isEqualTo("""
                ```table
                | MCP Client | Declarative
                ^a| [image]
                ```""");
    }

    @Test
    void appliesNoLineRulesInsideTable() {
        String md = converter.convert("""
                |===
                == not a heading
                // not a comment
                |===""");
        assertThat(md).isEqualTo("""
                ```table
                == not a heading
                // not a comment
                ```""");
    }

    @Test
    void blockTitleBeforeTableBecomesBold() {
        String md = converter.convert("""
                .Low Cardinality Keys
                [cols="a,a", stripes=even]
                |===
                |Name | Description
                |`gen_ai.system` | Always `spring_ai`.
                |===""");
        assertThat(md).isEqualTo("""
                **Low Cardinality Keys**
                ```table
                |Name | Description
                |`gen_ai.system` | Always `spring_ai`.
                ```""");
    }

    // --- tabs and description lists ---

    @Test
    void flattensTabs() {
        String md = converter.convert("""
                [tabs]
                ======
                Maven::
                +
                [source,xml]
                ----
                <dependency/>
                ----

                Gradle::
                +
                [source,gradle]
                ----
                implementation 'x'
                ----
                ======""");
        assertThat(md).isEqualTo("""
                Maven:
                ```xml
                <dependency/>
                ```

                Gradle:
                ```gradle
                implementation 'x'
                ```""");
    }

    @Test
    void convertsDescriptionListTerm() {
        assertThat(converter.convert("base-url:: The Ollama server URL."))
                .isEqualTo("base-url: The Ollama server URL.");
    }

    @Test
    void keepsMethodReferencesInText() {
        String text = "Use String::valueOf here.";
        assertThat(converter.convert(text)).isEqualTo(text);
    }

    // --- admonitions ---

    @Test
    void convertsInlineAdmonition() {
        assertThat(converter.convert("NOTE: Requires Ollama 0.5."))
                .isEqualTo("Note: Requires Ollama 0.5.");
    }

    @Test
    void convertsBlockAdmonition() {
        String md = converter.convert("""
                [WARNING]
                ====
                Do not expose port 11434.
                ====""");
        assertThat(md).isEqualTo("""
                Warning:
                Do not expose port 11434.""");
    }

    // --- links and images ---

    @Test
    void keepsLinkTextOnly() {
        String md = converter.convert(
                "See xref:api/chat.adoc[Chat API], link:https://ollama.com[Ollama site] "
                        + "and https://docs.spring.io[Spring docs].");
        assertThat(md).isEqualTo("See Chat API, Ollama site and Spring docs.");
    }

    @Test
    void emptyLinkTextFallsBackToTarget() {
        assertThat(converter.convert("See xref:api/chat.adoc#_options[] and link:https://ollama.com[]."))
                .isEqualTo("See api/chat.adoc and https://ollama.com.");
    }

    @Test
    void convertsLinkWithRelativeTarget() {
        assertThat(converter.convert("Refer to the link:../chat/ollama-chat.html[Ollama Chat Client] section."))
                .isEqualTo("Refer to the Ollama Chat Client section.");
    }

    @Test
    void keepsPlainUrlsWithoutBrackets() {
        String text = "Ollama listens on `http://localhost:11434` by default.";
        assertThat(converter.convert(text)).isEqualTo(text);
    }

    @Test
    void linkTextWithDollarSignIsKeptLiterally() {
        assertThat(converter.convert("link:https://x.io[costs $1]"))
                .isEqualTo("costs $1");
    }

    @Test
    void convertsInternalCrossReferences() {
        assertThat(converter.convert("See <<metadata-filters,Metadata Filters>> and <<low-level-api>>."))
                .isEqualTo("See Metadata Filters and low-level-api.");
        assertThat(converter.convert("See <<_known_limitations,Known Limitations>>."))
                .isEqualTo("See Known Limitations.");
    }

    @Test
    void convertsInternalCrossReferencesToSectionTitles() {
        assertThat(converter.convert("See <<Structured Outputs>> and <<run Chroma Locally, setup local ChromaDB>>."))
                .isEqualTo("See Structured Outputs and setup local ChromaDB.");
    }

    @Test
    void keepsShiftOperatorsInsideCode() {
        String md = converter.convert("""
                [source,bash]
                ----
                cat <<EOF
                ----""");
        assertThat(md).contains("cat <<EOF");
    }

    @Test
    void keepsHeredocMarkerInText() {
        String text = "Run `cat <<EOF` to start a heredoc.";
        assertThat(converter.convert(text)).isEqualTo(text);
    }

    @Test
    void replacesImagesWithPlaceholder() {
        assertThat(converter.convert("image::rag-flow.png[RAG flow, 600]"))
                .isEqualTo("[image]");
    }

    @Test
    void replacesInlineImagesWithPlaceholder() {
        assertThat(converter.convert("Click the image:icon-settings.png[Settings] button."))
                .isEqualTo("Click the [image] button.");
    }

    // --- passthrough ---

    @Test
    void unwrapsInlinePassthrough() {
        assertThat(converter.convert("Default: `+http://localhost:11434+`"))
                .isEqualTo("Default: `http://localhost:11434`");
    }

    @Test
    void unwrapsPassthroughInTableCells() {
        String md = converter.convert("""
                |===
                | spring.ai.ollama.base-url | Base URL | `+http://localhost:11434+`
                |===""");
        assertThat(md).contains("| `http://localhost:11434`");
    }

    @Test
    void keepsPassthroughInsideCode() {
        String md = converter.convert("""
                ----
                `+literal+`
                ----""");
        assertThat(md).contains("`+literal+`");
    }

    @Test
    void keepsPlusSignsOutsidePassthrough() {
        String text = "Works with C++ and `a + b`, press Ctrl+C to stop.";
        assertThat(converter.convert(text)).isEqualTo(text);
    }

    // --- things to drop ---

    @Test
    void dropsCommentsAndPageAttributes() {
        String md = converter.convert("""
                :toc:
                :toclevels: 3
                // internal note
                ////
                hidden block
                // with a comment inside
                ////
                Visible text
                toc::[]""");
        assertThat(md).isEqualTo("Visible text");
    }

    @Test
    void dropsSidebarAndOpenBlockDelimiters() {
        String md = converter.convert("""
                ****
                Sidebar text
                ****
                --
                Open block text
                --""");
        assertThat(md).isEqualTo("""
                Sidebar text
                Open block text""");
    }

    // --- things to keep ---

    @Test
    void keepsPromptTemplatePlaceholders() {
        String text = "Use `{context_str}` and `{query}` in the template.";
        assertThat(converter.convert(text)).isEqualTo(text);
    }

    @Test
    void keepsNumberedListItemsButBoldsBlockTitles() {
        String md = converter.convert("""
                . First step
                .Example
                Some text""");
        assertThat(md).isEqualTo("""
                . First step
                **Example**
                Some text""");
    }

    // --- whitespace and determinism ---

    @Test
    void collapsesBlankLinesAndTrailingSpaces() {
        String md = converter.convert("First   \n\n\n\n  \nSecond");
        assertThat(md).isEqualTo("First\n\nSecond");
    }

    @Test
    void sameInputGivesSameOutput() {
        String adoc = """
                = Title
                == Section
                [source,java]
                ----
                code
                ----""";
        assertThat(converter.convert(adoc)).isEqualTo(converter.convert(adoc));
    }
}
