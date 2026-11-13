package dev.gprometeus.devdocsrag.ingestion.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.CODE;
import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.PARAGRAPH;
import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.TABLE;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStructureParserTest {

    // Whitespace-separated words: deterministic and needs no native tokenizer
    private static final TokenCounter WORDS = text -> text.isBlank() ? 0 : text.strip().split("\\s+").length;

    private final MarkdownStructureParser parser = new MarkdownStructureParser(WORDS);

    @Test
    void headingPathsFollowNesting() {
        List<Section> sections = parser.parse("Doc", """
                # A
                a
                ## B
                b
                ### C
                c
                ## D
                d""");

        assertThat(sections).extracting(Section::headingPath).containsExactly(
                List.of("Doc", "A"),
                List.of("Doc", "A", "B"),
                List.of("Doc", "A", "B", "C"),
                List.of("Doc", "A", "D"));
        assertThat(sections).extracting(Section::level).containsExactly(1, 2, 3, 2);
        assertThat(sections).extracting(s -> s.blocks().getFirst().text()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void skippedLevelIsPoppedByShallowerHeading() {
        List<Section> sections = parser.parse("Doc", "# A\n### C\n## B\n# E");

        assertThat(sections).extracting(Section::headingPath).containsExactly(
                List.of("Doc", "A"),
                List.of("Doc", "A", "C"),
                List.of("Doc", "A", "B"),
                List.of("Doc", "E"));
    }

    @Test
    void headingInsideCodeDoesNotStartSection() {
        List<Section> sections = parser.parse("Doc", """
                # A
                ```bash
                # install

                ## not a heading
                ```
                after""");

        assertThat(sections).hasSize(1);
        List<Block> blocks = sections.getFirst().blocks();
        assertThat(blocks).extracting(Block::type).containsExactly(CODE, PARAGRAPH);
        assertThat(blocks.getFirst().text()).isEqualTo("```bash\n# install\n\n## not a heading\n```");
        assertThat(blocks.getFirst().fenceInfo()).isEqualTo("bash");
        assertThat(blocks.get(1).fenceInfo()).isNull();
    }

    @Test
    void textBeforeFirstHeadingIsRootSection() {
        List<Section> sections = parser.parse("Doc", "intro line\n\n# A\na");

        assertThat(sections.getFirst()).isEqualTo(new Section(List.of("Doc"), 0,
                List.of(new Block(PARAGRAPH, "intro line", null, 2))));
        assertThat(sections.get(1).headingPath()).containsExactly("Doc", "A");
    }

    @Test
    void noRootSectionWhenDocumentStartsWithHeading() {
        assertThat(parser.parse("Doc", "\n\n# A\na")).extracting(Section::level).containsExactly(1);
    }

    @Test
    void tableFenceGivesTableBlock() {
        Block block = parser.parse("Doc", "```table\n| a | b |\n|---|---|\n```").getFirst().blocks().getFirst();

        assertThat(block.type()).isEqualTo(TABLE);
        assertThat(block.fenceInfo()).isEqualTo("table");
    }

    @Test
    void emptyParentSectionIsKept() {
        List<Section> sections = parser.parse("Doc", "# Parent\n\n## Child\ntext");

        assertThat(sections).hasSize(2);
        assertThat(sections.getFirst().headingPath()).containsExactly("Doc", "Parent");
        assertThat(sections.getFirst().blocks()).isEmpty();
        assertThat(sections.get(1).blocks()).hasSize(1);
    }

    @Test
    void blankLinesSeparateParagraphsAndListIsOneParagraph() {
        List<Block> blocks = parser.parse("Doc", """
                first line
                second line


                - item one
                - item two
                """).getFirst().blocks();

        assertThat(blocks).extracting(Block::text).containsExactly("first line\nsecond line", "- item one\n- item two");
        assertThat(blocks).extracting(Block::tokens).containsExactly(4, 6);
    }

    @Test
    void fenceAndHeadingInterruptParagraphWithoutBlankLine() {
        List<Section> sections = parser.parse("Doc", "text\n```\ncode\n```\nmore\n# H\nh");

        assertThat(sections.getFirst().blocks()).extracting(Block::type).containsExactly(PARAGRAPH, CODE, PARAGRAPH);
        assertThat(sections.getFirst().blocks().get(1).fenceInfo()).isEmpty();
        assertThat(sections.get(1).headingPath()).containsExactly("Doc", "H");
    }

    @Test
    void unclosedFenceRunsToEnd() {
        List<Block> blocks = parser.parse("Doc", "```java\nint x;\n\n# not heading").getFirst().blocks();

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.type()).isEqualTo(CODE);
            // Closed with a fence as long as the opening one, so the block stays complete inside a chunk
            assertThat(block.text()).isEqualTo("```java\nint x;\n\n# not heading\n```");
        });
    }

    @Test
    void unclosedLongerIndentedFenceIsClosedWithSameRun() {
        Block block = parser.parse("Doc", "   ````yaml\nkey: v").getFirst().blocks().getFirst();

        assertThat(block.text()).isEqualTo("   ````yaml\nkey: v\n````");
    }

    @Test
    void h1EqualToTitleReplacesIt() {
        // Docs page: AsciidocDocumentLoader takes the title from this very h1
        List<Section> sections = parser.parse("Ollama Chat", """
                # Ollama Chat
                intro
                ## Prerequisites
                text""");

        assertThat(sections).extracting(Section::headingPath).containsExactly(
                List.of("Ollama Chat"),
                List.of("Ollama Chat", "Prerequisites"));
    }

    @Test
    void h1WithPrefixedTitleReplacesIt() {
        // Issue: IssueTextBuilder writes "# Issue #<number>: <title>"
        List<Section> sections = parser.parse("Migrate to OpenAI  java", """
                # Issue #3368: Migrate to OpenAI Java
                ## Description
                text""");

        assertThat(sections).extracting(Section::headingPath).containsExactly(
                List.of("Issue #3368: Migrate to OpenAI Java"),
                List.of("Issue #3368: Migrate to OpenAI Java", "Description"));
    }

    @Test
    void textBeforeH1KeepsTitle() {
        List<Section> sections = parser.parse("Doc", "intro\n# Doc\ntext");

        assertThat(sections).extracting(Section::headingPath).containsExactly(List.of("Doc"), List.of("Doc"));
    }

    @Test
    void differentH1OrNonH1KeepsTitle() {
        assertThat(parser.parse("Chat", "# Chat Memory\ntext").getFirst().headingPath())
                .containsExactly("Chat", "Chat Memory");
        assertThat(parser.parse("Doc", "## Doc\ntext").getFirst().headingPath())
                .containsExactly("Doc", "Doc");
    }

    @Test
    void repeatsTitleRules() {
        assertThat(MarkdownStructureParser.repeatsTitle("Ollama  Chat", "ollama chat")).isTrue();
        assertThat(MarkdownStructureParser.repeatsTitle("Issue #1: Fix it", "Fix it")).isTrue();
        assertThat(MarkdownStructureParser.repeatsTitle("Issue #1", "  ")).isTrue();
        assertThat(MarkdownStructureParser.repeatsTitle("Chat Memory", "Chat")).isFalse();
        assertThat(MarkdownStructureParser.repeatsTitle("Memory: Chat", "Memory")).isFalse();
    }

    @Test
    void atxHeadingSyntax() {
        assertThat(MarkdownStructureParser.heading("## Title ##")).isEqualTo(new MarkdownStructureParser.Heading(2, "Title"));
        assertThat(MarkdownStructureParser.heading("   # C#")).isEqualTo(new MarkdownStructureParser.Heading(1, "C#"));
        assertThat(MarkdownStructureParser.heading("#")).isEqualTo(new MarkdownStructureParser.Heading(1, ""));
        assertThat(MarkdownStructureParser.heading("#5 not heading")).isNull();
        assertThat(MarkdownStructureParser.heading("####### seven")).isNull();
        assertThat(MarkdownStructureParser.heading("    # indented code")).isNull();
    }
}
