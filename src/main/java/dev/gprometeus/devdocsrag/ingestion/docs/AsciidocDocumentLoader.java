package dev.gprometeus.devdocsrag.ingestion.docs;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences;
import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import dev.gprometeus.devdocsrag.ingestion.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns the AsciiDoc pages into pipeline documents: resolve includes, convert to Markdown, derive title and link.
 * <p>
 * Curly braces are left as they are: in this documentation they are prompt template placeholders, not attributes.
 */
@Component
public class AsciidocDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(AsciidocDocumentLoader.class);

    private static final String ADOC = ".adoc";
    private static final String NAV_FILE = "nav.adoc";
    private static final String TITLE_PREFIX = "# ";

    private final DocsProperties props;
    private final IncludeResolver includeResolver;
    private final AsciidocConverter converter;

    public AsciidocDocumentLoader(DocsProperties props, IncludeResolver includeResolver, AsciidocConverter converter) {
        this.props = props;
        this.includeResolver = includeResolver;
        this.converter = converter;
    }

    /** Pages in sourceId order; include counters of {@link IncludeResolver} describe this load afterwards. */
    public List<SourceDocument> load() {
        Path pages = props.pagesDir();
        includeResolver.resetCounters();
        List<SourceDocument> documents = new ArrayList<>();
        int skipped = 0;
        for (Path file : pageFiles(pages)) {
            String sourceId = sourceId(pages, file);
            String text = converter.convert(includeResolver.resolve(file, read(file), props.root()));
            String titleLine = titleLine(text);
            // A page that only includes partials which were not found ends up empty or heading-only
            if (text.isBlank() || text.strip().equals(titleLine)) {
                skipped++;
                log.debug("Skipping {}: no content besides the title", sourceId);
                continue;
            }
            String title = titleLine == null
                    ? withoutExtension(file.getFileName().toString())
                    : titleLine.substring(TITLE_PREFIX.length()).strip();
            documents.add(new SourceDocument(
                    SourceType.DOC,
                    sourceId,
                    props.baseUrl() + withoutExtension(sourceId) + ".html",
                    title,
                    props.sourceRef(),
                    text));
        }
        log.info("Loaded {} doc pages from {}, skipped {} without content", documents.size(), pages, skipped);
        return documents;
    }

    private static List<Path> pageFiles(Path pages) {
        try (Stream<Path> walk = Files.walk(pages)) {
            // Files.walk order depends on the file system; sorting by sourceId keeps the output stable
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(ADOC))
                    .filter(file -> !file.getFileName().toString().equals(NAV_FILE))
                    .sorted(Comparator.comparing(file -> sourceId(pages, file)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list pages in " + pages, e);
        }
    }

    /** "api/chat/ollama-chat.adoc": always "/", also on Windows, because it becomes part of the url. */
    private static String sourceId(Path pages, Path file) {
        return pages.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * First "# " line outside code blocks, or null. Inside a block "# " is a shell or YAML comment,
     * not a heading, so a page without "= Title" must not get "# install the model" as its title.
     */
    private static String titleLine(String markdown) {
        return MarkdownFences.scan(markdown).stream()
                .filter(line -> line.kind() == MarkdownFences.Kind.TEXT)
                .map(MarkdownFences.Line::text)
                .filter(text -> text.startsWith(TITLE_PREFIX))
                .findFirst()
                .orElse(null);
    }

    /** Cuts only the trailing ".adoc": String.replace would also hit "x.adoc-notes.adoc" in the middle. */
    private static String withoutExtension(String name) {
        return name.substring(0, name.length() - ADOC.length());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read page " + file, e);
        }
    }
}
