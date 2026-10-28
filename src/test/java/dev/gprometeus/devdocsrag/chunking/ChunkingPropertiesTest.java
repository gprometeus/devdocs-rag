package dev.gprometeus.devdocsrag.chunking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds and validates through Spring, the same way the application does on startup. */
class ChunkingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "devdocs.chunking.tokenizer-path=models/bge-m3/tokenizer.json",
                    "devdocs.chunking.target-tokens=450",
                    "devdocs.chunking.max-tokens=800",
                    "devdocs.chunking.min-tokens=80",
                    "devdocs.chunking.overlap-tokens=60");

    @Test
    void bindsConsistentSettings() {
        runner.run(context -> assertThat(context).getBean(ChunkingProperties.class)
                .isEqualTo(new ChunkingProperties(Path.of("models/bge-m3/tokenizer.json"), 450, 800, 80, 60)));
    }

    @Test
    void allowsZeroOverlap() {
        runner.withPropertyValues("devdocs.chunking.overlap-tokens=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "devdocs.chunking.overlap-tokens=80",   // overlap == min
            "devdocs.chunking.min-tokens=450",      // min == target
            "devdocs.chunking.target-tokens=800",   // target == max
            "devdocs.chunking.max-tokens=300"       // max < target
    })
    void rejectsBrokenOrdering(String override) {
        runner.withPropertyValues(override)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("overlap-tokens < min-tokens < target-tokens < max-tokens"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "devdocs.chunking.overlap-tokens=-1",
            "devdocs.chunking.min-tokens=0",
            "devdocs.chunking.tokenizer-path="
    })
    void rejectsInvalidValues(String override) {
        runner.withPropertyValues(override)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChunkingProperties.class)
    static class Config {
    }
}
