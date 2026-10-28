package dev.gprometeus.devdocsrag.chunking;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@link TokenCounter} backed by a HuggingFace tokenizer.json (the Rust tokenizers library via JNI).
 * Not tied to bge-m3: the model is whatever {@code devdocs.chunking.tokenizer-path} points to.
 *
 * <p>The tokenizer is loaded on first use, not on startup: models/ is not in git, and the application
 * context must start without it (one-shot tools and context tests never count tokens).
 *
 * <p>Thread-safe: encoding a text does not mutate the native tokenizer.
 */
@Component
public class HuggingFaceTokenCounter implements TokenCounter, AutoCloseable {

    private final Path tokenizerPath;
    // volatile only publishes the reference, which is all we need: the tokenizer is fully built before
    // the write and never mutated afterwards, and encoding is thread-safe on the native side
    @SuppressWarnings("java:S3077")
    private volatile HuggingFaceTokenizer tokenizer;
    private boolean closed;

    public HuggingFaceTokenCounter(ChunkingProperties properties) {
        this.tokenizerPath = properties.tokenizerPath();
    }

    // The tokenizer is shared and owned by this bean (released in close()), not by a single call
    @SuppressWarnings("java:S2095")
    @Override
    public int count(String text) {
        Objects.requireNonNull(text, "text");
        return tokenizer().encode(text).getIds().length;
    }

    /** Releases the native tokenizer; Spring calls it on context shutdown. */
    @Override
    public synchronized void close() {
        closed = true;
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
    }

    // Double-checked locking: after the first call the hot path is a single volatile read.
    // synchronized does not pin virtual threads since JDK 24 (JEP 491)
    private HuggingFaceTokenizer tokenizer() {
        HuggingFaceTokenizer result = tokenizer;
        if (result == null) {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Token counter is closed");
                }
                result = tokenizer;
                if (result == null) {
                    tokenizer = result = load(tokenizerPath);
                }
            }
        }
        return result;
    }

    private static HuggingFaceTokenizer load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Tokenizer file not found: " + path.toAbsolutePath()
                    + " (check devdocs.chunking.tokenizer-path, download it with scripts/download-models.sh)");
        }
        try {
            return HuggingFaceTokenizer.builder()
                    .optTokenizerPath(path)
                    // Count only the text itself: <s> and </s> are added once per input by the model, not per chunk
                    .optAddSpecialTokens(false)
                    // Without this a 5000-token text may be silently cut to max length and counted as 512
                    .optTruncation(false)
                    .optPadding(false)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load tokenizer: " + path.toAbsolutePath(), e);
        }
    }
}
