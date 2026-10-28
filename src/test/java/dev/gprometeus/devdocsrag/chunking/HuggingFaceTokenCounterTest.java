package dev.gprometeus.devdocsrag.chunking;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Runs against the real bge-m3 tokenizer: exact counts pin both the file and the encoding options. */
class HuggingFaceTokenCounterTest {

    private static final Path TOKENIZER = Path.of("models/bge-m3/tokenizer.json");

    // Loading the 17 MB tokenizer takes a while, so all tests share one instance
    private static HuggingFaceTokenCounter counter;

    @BeforeAll
    static void loadTokenizer() {
        if (!Files.isRegularFile(TOKENIZER)) {
            fail("Missing " + TOKENIZER + ": run scripts/download-models.sh");
        }
        counter = new HuggingFaceTokenCounter(props(TOKENIZER));
    }

    @AfterAll
    static void closeTokenizer() {
        if (counter != null) {
            counter.close();
        }
    }

    @Test
    void countsTextTokensWithoutSpecialTokens() {
        // ▁Spring ▁AI ▁provides ▁a ▁portable ▁API . — with special tokens it would be 9 (<s> ... </s>)
        assertThat(counter.count("Spring AI provides a portable API.")).isEqualTo(7);
    }

    @Test
    void emptyTextHasNoTokens() {
        assertThat(counter.count("")).isZero();
    }

    @Test
    void countsNonLatinText() {
        // ▁Spring ▁AI ▁поддерживает ▁ad visor s
        assertThat(counter.count("Spring AI поддерживает advisors")).isEqualTo(6);
    }

    @Test
    void doesNotTruncateLongText() {
        String longText = "Spring AI provides a portable API. ".repeat(500);

        // 7 tokens per sentence + a trailing "▁"; DJL's default truncation would return 512 here
        assertThat(counter.count(longText)).isEqualTo(500 * 7 + 1);
    }

    @Test
    void rejectsNullText() {
        assertThatNullPointerException().isThrownBy(() -> counter.count(null));
    }

    @Test
    void missingTokenizerFailsOnFirstUseNotOnCreation(@TempDir Path dir) {
        Path missing = dir.resolve("tokenizer.json");
        try (var lazy = new HuggingFaceTokenCounter(props(missing))) {
            assertThatThrownBy(() -> lazy.count("text"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(missing.toAbsolutePath().toString())
                    .hasMessageContaining("devdocs.chunking.tokenizer-path")
                    .hasMessageContaining("scripts/download-models.sh");
        }
    }

    @Test
    void closedCounterRejectsCounting() {
        var closed = new HuggingFaceTokenCounter(props(TOKENIZER));
        closed.count("load the native tokenizer");
        closed.close();
        closed.close(); // idempotent: Spring and try-with-resources may both close it

        assertThatThrownBy(() -> closed.count("text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    private static ChunkingProperties props(Path tokenizer) {
        return new ChunkingProperties(tokenizer, 450, 800, 80, 60);
    }
}
