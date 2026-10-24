package dev.gprometeus.devdocsrag.ingestion;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind.CODE;
import static dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind.FENCE_CLOSE;
import static dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind.FENCE_OPEN;
import static dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownFencesTest {

    @Test
    void plainFencedBlock() {
        List<Line> lines = MarkdownFences.scan("""
                Text
                ```java
                int x = 1;
                ```
                After""");

        assertThat(lines).extracting(Line::kind).containsExactly(TEXT, FENCE_OPEN, CODE, FENCE_CLOSE, TEXT);
        assertThat(lines).extracting(Line::language).containsExactly(null, "java", "java", "java", null);
    }

    @Test
    void innerShorterFenceWithInfoIsContent() {
        assertThat(kinds("""
                ````markdown
                ```java
                code
                ```
                ````"""))
                .containsExactly(FENCE_OPEN, CODE, CODE, CODE, FENCE_CLOSE);
    }

    @Test
    void longerClosingFenceClosesBlock() {
        assertThat(kinds("""
                ```
                code
                `````
                text"""))
                .containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE, TEXT);
    }

    @Test
    void shorterRunDoesNotClose() {
        assertThat(kinds("""
                ````
                ```
                ````"""))
                .containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void fenceWithInfoStringInsideBlockDoesNotClose() {
        assertThat(kinds("""
                ```
                ```java
                ```"""))
                .containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void tildesAndBackticksDoNotCloseEachOther() {
        assertThat(kinds("""
                ~~~
                ```
                ~~~
                ```
                ~~~
                ```"""))
                .containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE, FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void unclosedBlockRunsToEndOfText() {
        assertThat(kinds("""
                ```yaml
                key: value

                # not a heading"""))
                .containsExactly(FENCE_OPEN, CODE, CODE, CODE);
    }

    @Test
    void upToThreeSpacesOfIndentationAreAllowed() {
        assertThat(kinds("""
                   ```
                   code
                   ```"""))
                .containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void fourSpacesOfIndentationIsNotAFence() {
        assertThat(kinds("    ```\n    code\n    ```")).containsOnly(TEXT);
    }

    @Test
    void trailingSpacesAfterClosingFenceAreAllowed() {
        assertThat(kinds("```\ncode\n```   ")).containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void backtickInInfoStringMeansNoFence() {
        assertThat(kinds("``` a`b")).containsExactly(TEXT);
        assertThat(kinds("~~~ a`b\ncode\n~~~")).containsExactly(FENCE_OPEN, CODE, FENCE_CLOSE);
    }

    @Test
    void languageIsFirstWordOfInfoString() {
        assertThat(MarkdownFences.scan("```  table extra\n| a |\n```"))
                .extracting(Line::language).containsExactly("table", "table", "table");
        assertThat(MarkdownFences.scan("```\nx\n```"))
                .extracting(Line::language).containsExactly("", "", "");
    }

    @Test
    void joiningLinesGivesTextBack() {
        String text = "a\n```\nb\n```\n\nc\n";
        List<String> texts = MarkdownFences.scan(text).stream().map(Line::text).toList();
        assertThat(String.join("\n", texts)).isEqualTo(text);
    }

    private static List<Kind> kinds(String text) {
        return MarkdownFences.scan(text).stream().map(Line::kind).toList();
    }
}
