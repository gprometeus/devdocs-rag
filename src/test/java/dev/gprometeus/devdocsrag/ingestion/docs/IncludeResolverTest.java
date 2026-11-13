package dev.gprometeus.devdocsrag.ingestion.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class IncludeResolverTest {

    @TempDir
    Path root;

    private final IncludeResolver resolver = new IncludeResolver();

    @Test
    void resolvesPartialFromPartialsFolder() throws IOException {
        write("partials/openai/http-client.adoc", "== HTTP client\nCustomize it.\n");
        Path page = write("pages/api/chat/openai-chat.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "Before\ninclude::partial$openai/http-client.adoc[]\nAfter", root);

        assertThat(result.text()).isEqualTo("Before\n== HTTP client\nCustomize it.\nAfter");
        assertThat(result.stats()).isEqualTo(new IncludeStats(1, 0, 0));
    }

    @Test
    void resolvesTargetRelativeToCurrentFileAndIgnoresOptions() throws IOException {
        write("pages/api/bedrock.adoc", "Bedrock common text");
        Path page = write("pages/api/bedrock-chat.adoc", "");

        assertThat(resolver.resolve(page, "include::bedrock.adoc[tag=intro]", root).text())
                .isEqualTo("Bedrock common text");
    }

    @Test
    void resolvesNestedIncludes() throws IOException {
        write("partials/outer.adoc", "outer start\ninclude::partial$inner.adoc[]\nouter end");
        write("partials/inner.adoc", "inner");
        Path page = write("pages/page.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "include::partial$outer.adoc[]", root);

        assertThat(result.text()).isEqualTo("outer start\ninner\nouter end");
        assertThat(result.stats()).isEqualTo(new IncludeStats(2, 0, 0));
    }

    @Test
    void stopsAtDepthLimit() throws IOException {
        write("partials/l1.adoc", "l1\ninclude::partial$l2.adoc[]");
        write("partials/l2.adoc", "l2\ninclude::partial$l3.adoc[]");
        write("partials/l3.adoc", "l3\ninclude::partial$l4.adoc[]");
        write("partials/l4.adoc", "l4");
        Path page = write("pages/page.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "include::partial$l1.adoc[]", root);

        assertThat(result.text()).isEqualTo("l1\nl2\nl3");
        assertThat(result.stats()).isEqualTo(new IncludeStats(3, 0, 1));
    }

    @Test
    void missingFileInsertsNothingCountsAndWarns(CapturedOutput output) throws IOException {
        Path page = write("pages/page.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "Before\ninclude::partial$nope.adoc[]\nAfter", root);

        assertThat(result.text()).isEqualTo("Before\nAfter");
        assertThat(result.stats()).isEqualTo(new IncludeStats(0, 1, 0));
        assertThat(output).contains("WARN").contains("page.adoc").contains("partial$nope.adoc");
    }

    @Test
    void targetOutsideRootCountsAsMissing() throws IOException {
        Files.writeString(root.getParent().resolve("outside.adoc"), "secret");
        Path page = write("pages/page.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "include::../../outside.adoc[]", root);

        assertThat(result.text()).isEmpty();
        assertThat(result.stats()).isEqualTo(new IncludeStats(0, 1, 0));
    }

    @Test
    void breaksCycleOfTwoFiles() throws IOException {
        write("partials/a.adoc", "a\ninclude::partial$b.adoc[]");
        write("partials/b.adoc", "b\ninclude::partial$a.adoc[]");
        Path page = write("pages/page.adoc", "");

        IncludeResolver.Result result = resolver.resolve(page, "include::partial$a.adoc[]", root);

        assertThat(result.text()).isEqualTo("a\nb");
        assertThat(result.stats()).isEqualTo(new IncludeStats(2, 0, 1));
    }

    @Test
    void samePartialTwiceIsNotACycle() throws IOException {
        write("partials/note.adoc", "note");
        Path page = write("pages/page.adoc", "");

        assertThat(resolver.resolve(page, "include::partial$note.adoc[]\ninclude::partial$note.adoc[]", root).text())
                .isEqualTo("note\nnote");
    }

    @Test
    void leavesIncludesInsideCodeBlocks() throws IOException {
        write("partials/x.adoc", "inserted");
        Path page = write("pages/page.adoc", "");
        String text = """
                ----
                include::partial$x.adoc[]
                ----
                ```
                include::partial$x.adoc[]
                ```""";

        assertThat(resolver.resolve(page, text, root)).isEqualTo(new IncludeResolver.Result(text, IncludeStats.EMPTY));
    }

    @Test
    void eachCallCountsOnlyItsOwnIncludes() throws IOException {
        Path page = write("pages/page.adoc", "");
        resolver.resolve(page, "include::partial$nope.adoc[]", root);

        assertThat(resolver.resolve(page, "no includes", root).stats()).isEqualTo(IncludeStats.EMPTY);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
