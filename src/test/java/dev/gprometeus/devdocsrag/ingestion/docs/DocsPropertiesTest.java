package dev.gprometeus.devdocsrag.ingestion.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocsPropertiesTest {

    @TempDir
    Path root;

    @Test
    void pagesDirResolvesUnderRoot() throws IOException {
        Files.createDirectory(root.resolve("pages"));

        assertThat(props(root).pagesDir()).isEqualTo(root.resolve("pages"));
    }

    @Test
    void missingPagesDirFailsWithAbsolutePathAndPropertyName() {
        assertThatThrownBy(() -> props(root).pagesDir())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(root.resolve("pages").toAbsolutePath().toString())
                .hasMessageContaining("devdocs.docs.root");
    }

    private static DocsProperties props(Path root) {
        return new DocsProperties(root, "https://docs.example/", "v1");
    }
}
