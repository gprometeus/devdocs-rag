package dev.gprometeus.devdocsrag.ingestion.chunking;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences;
import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import dev.gprometeus.devdocsrag.ingestion.SourceType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.CODE;
import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.PARAGRAPH;
import static dev.gprometeus.devdocsrag.ingestion.chunking.BlockType.TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@ExtendWith(OutputCaptureExtension.class)
class MarkdownChunkerTest {

    // Whitespace-separated words: separators ("\n\n", " ") cost 0, so expected sizes are easy to compute by hand
    private static final TokenCounter WORDS = text -> text.isBlank() ? 0 : text.strip().split("\\s+").length;
    // 4 characters per token: a long line without spaces is expensive, as with a real tokenizer
    private static final TokenCounter CHARS = text -> (text.length() + 3) / 4;

    // overlap < min < target < max, as ChunkingProperties requires
    private static final ChunkingProperties PROPS = new ChunkingProperties(Path.of("unused"), 20, 30, 8, 5);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MarkdownChunker chunker = chunker(WORDS);

    // --- chunk(): paths, numbering, merging ---------------------------------------------------------------------

    @Test
    void chunkContentIsPathPlusBodyAndTokenCountIsOfContent() {
        List<Chunk> chunks = chunker.chunk(doc("Guide", "# Guide\n## Setup\n" + words("s", 10) + "\n## Usage\n" + words("u", 10)));

        assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
        assertThat(chunks).extracting(Chunk::section).containsExactly("Guide › Setup", "Guide › Usage");
        assertThat(chunks.getFirst().content()).isEqualTo("Guide › Setup\n\n" + words("s", 10));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isEqualTo(WORDS.count(chunk.content())));
    }

    @Test
    void smallSiblingsMergeUnderParentPathWithTheirHeadingsInBody() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", """
                ## Options
                ### TopK
                k1 k2
                ### TopP
                p1 p2
                ### Seed
                """ + words("s", 10)));

        // Options has no blocks of its own: no chunk, but its name lives on in the children's path
        assertThat(chunks).extracting(Chunk::section).containsExactly("Doc › Options", "Doc › Options › Seed");
        assertThat(chunks.getFirst().content()).isEqualTo("Doc › Options\n\n### TopK\n\nk1 k2\n\n### TopP\n\np1 p2");
    }

    @Test
    void sectionWithChildrenAndLastSmallSectionStayAlone() {
        // A's own text is at the min-tokens limit, so it is not glued into A1
        List<Chunk> chunks = chunker.chunk(doc("Doc", "## A\n" + words("a", 8) + "\n### A1\n" + words("x", 10) + "\n## B\nb1"));

        assertThat(chunks).extracting(Chunk::section).containsExactly("Doc › A", "Doc › A › A1", "Doc › B");
    }

    // --- rule 1: small parent text goes into the first body of the first child ---------------------------------------------------

    @Test
    void smallParentTextIsGluedIntoFirstChildBeforeItsHeading() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", """
                ## Options
                Shared options below.
                ### TopK
                """ + words("k", 10) + """

                ### Seed
                """ + words("s", 10)));

        assertThat(chunks).extracting(Chunk::section).containsExactly("Doc › Options › TopK", "Doc › Options › Seed");
        assertThat(chunks.getFirst().content())
                .isEqualTo("Doc › Options › TopK\n\nShared options below.\n\n### TopK\n\n" + words("k", 10));
    }

    @Test
    void parentTextStaysAloneWhenFirstBodyCannotTakeIt() {
        // path 5 + parent 3 + "### TopK" 2 + first body 21 = 31 > 30
        List<Chunk> chunks = chunker.chunk(doc("Doc", "## Options\nShared options below.\n### TopK\n" + words("k", 21)));

        assertThat(chunks).extracting(Chunk::section).containsExactly("Doc › Options", "Doc › Options › TopK");
    }

    @Test
    void parentTextGoesIntoFirstBodyOfLargeChildOnly() {
        // The child as a whole (36 tokens) is above max-tokens, but its first body (12) has room
        String child = words("k", 12) + "\n\n" + words("m", 12) + "\n\n" + words("n", 12);

        List<Chunk> chunks = chunker.chunk(doc("Doc", "## Options\nShared options below.\n### TopK\n" + child));

        assertThat(chunks).extracting(Chunk::section).containsOnly("Doc › Options › TopK").hasSize(3);
        assertThat(chunks.getFirst().content())
                .isEqualTo("Doc › Options › TopK\n\nShared options below.\n\n### TopK\n\n" + words("k", 12));
        assertThat(chunks.get(1).content()).isEqualTo("Doc › Options › TopK\n\n" + words("m", 12));
    }

    @Test
    void parentTextAtMinTokensIsNotGlued() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", "## Options\n" + words("o", 8) + "\n### TopK\n" + words("k", 10)));

        assertThat(chunks).extracting(Chunk::section).containsExactly("Doc › Options", "Doc › Options › TopK");
    }

    @Test
    void parentTextTravelsDownTheChainOfFirstChildren() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", "## A\na1 a2\n### B\nb1\n#### C\n" + words("c", 5)));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.section()).isEqualTo("Doc › A › B › C");
            assertThat(chunk.content()).isEqualTo("Doc › A › B › C\n\na1 a2\n\n### B\n\nb1\n\n#### C\n\n" + words("c", 5));
        });
    }

    @Test
    void textBeforeFirstHeadingIsGluedIntoIt() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", "Intro line.\n# Setup\n" + words("s", 10)));

        assertThat(chunks).singleElement().extracting(Chunk::section).isEqualTo("Doc › Setup");
        assertThat(chunks.getFirst().content()).startsWith("Doc › Setup\n\nIntro line.\n\n# Setup\n\n");
    }

    @Test
    void parentTextGoesBeforeMergedGroupOfItsChildrenWithoutExtraHeading() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", "## P\np1\n### X\nx1\n### Y\ny1"));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.section()).isEqualTo("Doc › P");
            assertThat(chunk.content()).isEqualTo("Doc › P\n\np1\n\n### X\n\nx1\n\n### Y\n\ny1");
        });
    }

    @Test
    void mergedSectionGetsLevelOfParentEvenWhenLevelsSkip() {
        List<Section> merged = chunker.mergeSmallSiblings(new MarkdownStructureParser(WORDS)
                .parse("Doc", "# Top\n### X\nx\n### Y\ny"));

        assertThat(merged).extracting(Section::headingPath, Section::level).containsExactly(
                tuple(List.of("Doc", "Top"), 1),
                tuple(List.of("Doc", "Top"), 1));
    }

    @Test
    void tinyDocumentGivesOneChunk() {
        List<Chunk> chunks = chunker.chunk(doc("Azure OpenAI", "As of 2.0.0-M5, use OpenAI Chat."));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.section()).isEqualTo("Azure OpenAI");
            assertThat(chunk.content()).isEqualTo("Azure OpenAI\n\nAs of 2.0.0-M5, use OpenAI Chat.");
        });
    }

    @Test
    void headingsOnlyDocumentGivesOneChunkWithWholeText() {
        List<Chunk> chunks = chunker.chunk(doc("Doc", "# Doc\n## Empty"));

        assertThat(chunks).singleElement().extracting(Chunk::content).isEqualTo("Doc\n\n# Doc\n## Empty");
    }

    @Test
    void untitledIssueTakesPathFromItsH1() {
        SourceDocument issue = new SourceDocument(SourceType.ISSUE, "7", "https://example/7", null, "2026-10-17",
                "# Issue #7\nSomething broke.");

        assertThat(chunker.chunk(issue)).singleElement().extracting(Chunk::section).isEqualTo("Issue #7");
    }

    @Test
    void longPathShrinksBodyBudget() {
        String title = words("t", 15);   // 15 of 30 tokens go to the path, body target falls to min-tokens (8)
        String text = IntStream.range(0, 4).mapToObj(i -> words("p" + i, 6)).collect(Collectors.joining("\n\n"));

        List<Chunk> chunks = chunker.chunk(doc(title, text));

        // Bodies of one paragraph each (target 8), the 6-token tail joins the last one (12 <= budget 15).
        // With the full target of 20 it would be one chunk of 15 + 24 = 39 tokens, above max-tokens
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(PROPS.maxTokens()));
    }

    @Test
    void chunkAboveMaxTokensIsCountedAndLogged(CapturedOutput output) {
        chunker.chunk(doc(words("t", 40), "a b"));   // the path alone is above max-tokens

        assertThat(meters.counter("devdocs.chunking.oversized").count()).isEqualTo(1);
        assertThat(output).contains("WARN").contains("doc.adoc").contains("above max-tokens 30");
    }

    // --- pack() ------------------------------------------------------------------------------------------------

    @Test
    void packFillsBodiesUpToTarget() {
        assertThat(chunker.pack(List.of(para("a", 8), para("b", 8), para("c", 8)), 30, 20, "id"))
                .containsExactly(words("a", 8) + "\n\n" + words("b", 8), words("c", 8));
    }

    @Test
    void smallLastParagraphOverlapsIntoNextBody() {
        assertThat(chunker.pack(List.of(para("a", 8), para("s", 4), para("c", 12)), 30, 20, "id"))
                .containsExactly(words("a", 8) + "\n\n" + words("s", 4), words("s", 4) + "\n\n" + words("c", 12));
    }

    @Test
    void overlapIsDroppedWhenItDoesNotFitWithNextBlock() {
        assertThat(chunker.pack(List.of(para("a", 8), para("s", 4), para("c", 27)), 30, 20, "id"))
                .containsExactly(words("a", 8) + "\n\n" + words("s", 4), words("c", 27));
    }

    @Test
    void codeIsNeverOverlapped() {
        Block code = code("```java\nint x = 1;\n```");

        assertThat(chunker.pack(List.of(para("a", 10), code, para("c", 10)), 30, 20, "id"))
                .containsExactly(words("a", 10) + "\n\n" + code.text(), words("c", 10));
    }

    @Test
    void introWithColonMovesWithTheCode() {
        Block intro = paragraph("Add the following dependency:");
        Block code = code("```xml\n<dependency> a b c d e f g </dependency>\n```");

        assertThat(chunker.pack(List.of(para("a", 12), intro, code), 30, 20, "id"))
                .containsExactly(words("a", 12), intro.text() + "\n\n" + code.text());
    }

    @Test
    void introAloneIsNotLeftAsItsOwnBody() {
        Block intro = paragraph("Run this:");
        Block code = code("```bash\n" + words("c", 20) + "\n```");

        assertThat(chunker.pack(List.of(intro, code), 30, 20, "id"))
                .containsExactly(intro.text() + "\n\n" + code.text());
    }

    @Test
    void oversizedCodeIsSplitAndIntroRidesWithFirstPiece() {
        Block intro = paragraph("Configure it:");
        Block code = code("```yaml\n" + IntStream.range(0, 15).mapToObj(i -> "key" + i + ": v w").collect(Collectors.joining("\n")) + "\n```");

        List<String> bodies = chunker.pack(List.of(para("a", 5), intro, code), 30, 20, "id");

        assertThat(bodies.getFirst()).isEqualTo(words("a", 5));
        assertThat(bodies.get(1)).startsWith("Configure it:\n\n```yaml\nkey0: v w");
        assertThat(bodies.subList(1, bodies.size())).hasSizeGreaterThan(1)
                .allSatisfy(body -> assertThat(WORDS.count(body)).isLessThanOrEqualTo(30));
    }

    // --- pack(): small tail ---

    @Test
    void smallTailIsAppendedToPreviousBody() {
        // a + b = 19, c would make 22 > target 20: c is cut off as a 3-token tail, and 22 <= budget 30
        assertThat(chunker.pack(List.of(para("a", 10), para("b", 9), para("c", 3)), 30, 20, "id"))
                .containsExactly(words("a", 10) + "\n\n" + words("b", 9) + "\n\n" + words("c", 3));
    }

    @Test
    void smallTailStaysWhenTogetherAboveBudget() {
        assertThat(chunker.pack(List.of(para("a", 25), para("c", 6)), 30, 20, "id"))
                .containsExactly(words("a", 25), words("c", 6));
    }

    @Test
    void tailOverlapIsNotDuplicatedWhenMerged() {
        // [a, s] closed at 18 + 3 > 20, the tail is [s (overlap), c]: merged back it adds only c
        assertThat(chunker.pack(List.of(para("a", 14), para("s", 4), para("c", 3)), 30, 20, "id"))
                .containsExactly(words("a", 14) + "\n\n" + words("s", 4) + "\n\n" + words("c", 3));
    }

    // --- splitOversized() -------------------------------------------------------------------------------------

    @Test
    void codePiecesAreValidFencedBlocksWithOriginalFenceAndUntouchedLines() {
        List<String> content = List.of("spring:", "  ai:", "", "    ollama: a b", "```", "    model: c d", "  x: y");
        Block block = code("````yaml\n" + String.join("\n", content) + "\n````");

        List<Block> pieces = chunker.splitOversized(block, 6, "id");

        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(piece -> {
            assertThat(piece.type()).isEqualTo(CODE);
            assertThat(piece.fenceInfo()).isEqualTo("yaml");
            assertThat(piece.text()).startsWith("````yaml\n").endsWith("\n````");
            assertThat(piece.tokens()).isLessThanOrEqualTo(6);
            // The inner ``` stays content: the piece is one block for MarkdownFences, closed by its own fence
            assertThat(MarkdownFences.scan(piece.text()).getLast().kind()).isEqualTo(MarkdownFences.Kind.FENCE_CLOSE);
        });
        assertThat(innerLines(pieces)).isEqualTo(content);
    }

    @Test
    void unclosedCodeBlockPiecesGetClosingFence() {
        List<Block> pieces = chunker.splitOversized(code("```java\na b c\nd e f\ng h i"), 5, "id");

        assertThat(pieces).extracting(Block::text)
                .containsExactly("```java\na b c\n```", "```java\nd e f\n```", "```java\ng h i\n```");
    }

    @Test
    void tablePiecesRepeatHeaderRow() {
        Block table = new Block(TABLE, "```table\n| H1 | H2\n| a | b\n| c | d\n| e | f\n```", "table", 18);

        List<Block> pieces = chunker.splitOversized(table, 10, "id");

        assertThat(pieces).extracting(Block::text).containsExactly(
                "```table\n| H1 | H2\n| a | b\n```",
                "```table\n| H1 | H2\n| c | d\n```",
                "```table\n| H1 | H2\n| e | f\n```");
    }

    @Test
    void singleLineParagraphIsSplitBySentences() {
        Block block = paragraph("One two three. Four five six! Seven eight nine?");

        assertThat(chunker.splitOversized(block, 4, "id")).extracting(Block::text)
                .containsExactly("One two three.", "Four five six!", "Seven eight nine?");
    }

    @Test
    void hugeLineIsCutByCharactersWithWarning(CapturedOutput output) {
        MarkdownChunker byChars = chunker(CHARS);
        String json = "{\"k\":\"" + "v".repeat(400) + "\"}";
        Block block = new Block(CODE, "```json\n" + json + "\n```", "json", CHARS.count("```json\n" + json + "\n```"));

        List<Block> pieces = byChars.splitOversized(block, 20, "4884");

        assertThat(pieces).hasSizeGreaterThan(1)
                .allSatisfy(piece -> assertThat(piece.tokens()).isLessThanOrEqualTo(20));
        assertThat(String.join("", innerLines(pieces))).isEqualTo(json);
        assertThat(output).contains("WARN").contains("4884").contains("cutting it by characters");
    }

    @Test
    void characterCutNeverSplitsSurrogatePair() {
        String emoji = "😀".repeat(100);   // 😀 is two chars in UTF-16

        List<Block> pieces = chunker(CHARS).splitOversized(paragraph(emoji, CHARS), 7, "id");

        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(piece -> {
            assertThat(Character.isLowSurrogate(piece.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(piece.text().charAt(piece.text().length() - 1))).isFalse();
        });
        assertThat(pieces.stream().map(Block::text).collect(Collectors.joining())).isEqualTo(emoji);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private MarkdownChunker chunker(TokenCounter counter) {
        return new MarkdownChunker(new MarkdownStructureParser(counter), counter, PROPS, meters);
    }

    private static SourceDocument doc(String title, String text) {
        return new SourceDocument(SourceType.DOC, "doc.adoc", "https://example/doc.html", title, "v1", text);
    }

    /** "p1 p2 ... pN" */
    private static String words(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i).collect(Collectors.joining(" "));
    }

    private static Block para(String prefix, int count) {
        return paragraph(words(prefix, count));
    }

    private static Block paragraph(String text) {
        return paragraph(text, WORDS);
    }

    private static Block paragraph(String text, TokenCounter counter) {
        return new Block(PARAGRAPH, text, null, counter.count(text));
    }

    private static Block code(String text) {
        return new Block(CODE, text, MarkdownFences.scan(text).getFirst().language(), WORDS.count(text));
    }

    /** Content lines of all pieces, without their fence lines, in order. */
    private static List<String> innerLines(List<Block> pieces) {
        List<String> lines = new ArrayList<>();
        for (Block piece : pieces) {
            List<String> all = List.of(piece.text().split("\n", -1));
            lines.addAll(all.subList(1, all.size() - 1));
        }
        return lines;
    }
}
