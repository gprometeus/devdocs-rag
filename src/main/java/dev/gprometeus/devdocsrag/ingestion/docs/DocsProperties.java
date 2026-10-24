package dev.gprometeus.devdocsrag.ingestion.docs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where the AsciiDoc sources live and how to cite them. */
@Validated
@ConfigurationProperties(prefix = "devdocs.docs")
public record DocsProperties(
        @NotNull Path root,
        // Citation links are built by plain concatenation, so the slash must already be there
        @NotBlank @Pattern(regexp = ".*/", message = "must end with /") String baseUrl,
        @NotBlank String sourceRef
) {

    /**
     * Pages directory under {@link #root()}. Checked on use rather than on startup: data/sources is
     * not in git, and the application context must start without a docs checkout.
     */
    public Path pagesDir() {
        Path pages = root.resolve("pages");
        if (!Files.isDirectory(pages)) {
            throw new IllegalStateException("Docs pages directory not found: " + pages.toAbsolutePath()
                    + " (check devdocs.docs.root)");
        }
        return pages;
    }
}
