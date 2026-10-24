package dev.gprometeus.devdocsrag.ingestion.docs;

import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import dev.gprometeus.devdocsrag.ingestion.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsciidocDocumentLoaderTest {

    @TempDir
    Path root;

    private final IncludeResolver includeResolver = new IncludeResolver();

    @Test
    void loadsPagesInSourceIdOrderWithIncludesResolved() throws IOException {
        write("pages/nav.adoc", "* xref:index.adoc[Home]");
        write("pages/index.adoc", """
                = Spring AI
                Welcome to {project-name} docs.""");
        write("pages/api/chat/ollama-chat.adoc", """
                = Ollama Chat
                == Setup
                include::partial$ollama/setup.adoc[]""");
        write("partials/ollama/setup.adoc", "Run `ollama serve` first.");

        List<SourceDocument> documents = loader().load();

        assertThat(documents).extracting(SourceDocument::sourceId)
                .containsExactly("api/chat/ollama-chat.adoc", "index.adoc");

        SourceDocument ollama = documents.getFirst();
        assertThat(ollama.sourceType()).isEqualTo(SourceType.DOC);
        assertThat(ollama.sourceUrl()).isEqualTo("https://docs.example/reference/api/chat/ollama-chat.html");
        assertThat(ollama.title()).isEqualTo("Ollama Chat");
        assertThat(ollama.sourceRef()).isEqualTo("v2.0.1");
        assertThat(ollama.text()).isEqualTo("""
                # Ollama Chat
                ## Setup
                Run `ollama serve` first.""");
        assertThat(includeResolver.resolvedCount()).isEqualTo(1);

        // Curly braces are prompt template placeholders, not attributes
        assertThat(documents.get(1).text()).contains("{project-name}");
    }

    @Test
    void titleFallsBackToFileNameAndIgnoresCodeComments() throws IOException {
        write("pages/no-title.adoc", """
                Some text.
                [source,bash]
                ----
                # install the model
                ----""");

        assertThat(loader().load()).singleElement()
                .extracting(SourceDocument::title).isEqualTo("no-title");
    }

    @Test
    void skipsPagesWithOnlyTitle() throws IOException {
        write("pages/empty.adoc", """
                = Only Title
                include::partial$missing.adoc[]""");
        write("pages/blank.adoc", ":toc:");
        write("pages/real.adoc", "= Real\nContent.");

        assertThat(loader().load()).extracting(SourceDocument::sourceId).containsExactly("real.adoc");
    }

    private AsciidocDocumentLoader loader() {
        DocsProperties props = new DocsProperties(root, "https://docs.example/reference/", "v2.0.1");
        return new AsciidocDocumentLoader(props, includeResolver, new AsciidocConverter());
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
