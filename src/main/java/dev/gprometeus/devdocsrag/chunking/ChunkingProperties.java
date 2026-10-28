package dev.gprometeus.devdocsrag.chunking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Chunk size limits, in tokens of the embedding model's tokenizer. The tokenizer file is not checked
 * on startup: models/ is not in git, and the application context must start without it.
 */
@Validated
@ConfigurationProperties(prefix = "devdocs.chunking")
public record ChunkingProperties(
        @NotNull Path tokenizerPath,
        @Positive int targetTokens,
        @Positive int maxTokens,
        @Positive int minTokens,
        @PositiveOrZero int overlapTokens
) {

    // Bean Validation treats isXxx() as a property getter, so the cross-field rule runs with the others
    @AssertTrue(message = "required: overlap-tokens < min-tokens < target-tokens < max-tokens")
    boolean isConsistent() {
        return overlapTokens < minTokens && minTokens < targetTokens && targetTokens < maxTokens;
    }
}
