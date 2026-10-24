package dev.gprometeus.devdocsrag.ingestion.docs;

import dev.gprometeus.devdocsrag.ingestion.MarkdownFences;
import dev.gprometeus.devdocsrag.ingestion.MarkdownFences.Kind;
import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Writes doc pages to a text file and logs conversion statistics, then exits the application.
 * <p>
 * "AsciiDoc traces" are lines outside code blocks that still look like AsciiDoc markup: each of them
 * means the converter met a construct it does not know.
 * <p>
 * Usage: {@code ./gradlew bootRun --args='--spring.profiles.active=preview-docs'}
 * (no database needed, see application-preview-docs.yml)
 */
@Component
@Profile("preview-docs")
public class DocsPreviewRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocsPreviewRunner.class);

    private static final Path PREVIEW_FILE = Path.of("build", "preview", "docs-preview.txt");
    private static final String FEATURED_SOURCE_ID = "api/chat/ollama-chat.adoc";
    private static final int PREVIEW_LIMIT = 5;
    private static final int LONGEST_LIMIT = 5;
    private static final int TRACE_EXAMPLES = 3;
    private static final String TABLE_LANGUAGE = "table";

    /** A kind of AsciiDoc markup that should not survive conversion. */
    private record Trace(String label, Predicate<String> matches) {

        static Trace startsWith(String prefix) {
            return new Trace(prefix, line -> line.startsWith(prefix));
        }

        static Trace contains(String fragment) {
            return new Trace(fragment, line -> line.contains(fragment));
        }
    }

    // Immutable and ordered: traces are logged in this order on every run
    private static final List<Trace> TRACES = List.of(
            Trace.startsWith("----"),
            Trace.startsWith("...."),
            Trace.startsWith("|==="),
            Trace.startsWith("[source"),
            Trace.startsWith("include::"),
            Trace.startsWith("image::"),
            Trace.contains("xref:"),
            Trace.contains("<<"));

    // Ties are broken by sourceId so the "longest" list is the same on every run
    private static final Comparator<SourceDocument> LONGEST_FIRST =
            Comparator.comparingInt((SourceDocument doc) -> doc.text().length()).reversed()
                    .thenComparing(SourceDocument::sourceId);

    private final AsciidocDocumentLoader loader;
    private final IncludeResolver includeResolver;
    private final ConfigurableApplicationContext context;

    public DocsPreviewRunner(AsciidocDocumentLoader loader, IncludeResolver includeResolver,
                             ConfigurableApplicationContext context) {
        this.loader = loader;
        this.includeResolver = includeResolver;
        this.context = context;
    }

    @Override
    public void run(String... args) throws IOException {
        List<SourceDocument> documents = loader.load();
        writePreview(documents);
        logStatistics(documents);
        // One-shot profile: shut down instead of keeping the context alive.
        // On failure Spring Boot closes the context and exits with a non-zero code.
        System.exit(SpringApplication.exit(context));
    }

    private void writePreview(List<SourceDocument> documents) throws IOException {
        List<SourceDocument> preview = new ArrayList<>();
        documents.stream()
                .filter(doc -> doc.sourceId().equals(FEATURED_SOURCE_ID))
                .findFirst()
                .ifPresentOrElse(preview::add, () -> log.warn("{} not found among the documents", FEATURED_SOURCE_ID));
        preview.addAll(documents.subList(0, Math.min(PREVIEW_LIMIT, documents.size())));

        Files.createDirectories(PREVIEW_FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(PREVIEW_FILE, StandardCharsets.UTF_8)) {
            for (SourceDocument doc : preview) {
                writer.write("===== " + doc.sourceId() + " | " + doc.title() + " | " + doc.sourceUrl() + " =====\n");
                writer.write(doc.text());
                writer.write("\n\n");
            }
        }
        log.info("Preview of {} documents written to {}", preview.size(), PREVIEW_FILE.toAbsolutePath());
    }

    private void logStatistics(List<SourceDocument> documents) {
        log.info("Includes resolved: {}, missing: {}, skipped (cycle or depth): {}",
                includeResolver.resolvedCount(), includeResolver.missingCount(), includeResolver.skippedCount());
        if (documents.isEmpty()) {
            log.warn("No documents loaded, nothing to analyze");
            return;
        }
        IntSummaryStatistics lengths = documents.stream()
                .mapToInt(doc -> doc.text().length())
                .summaryStatistics();
        long tables = documents.stream()
                .flatMap(doc -> MarkdownFences.scan(doc.text()).stream())
                .filter(line -> line.kind() == Kind.FENCE_OPEN && TABLE_LANGUAGE.equals(line.language()))
                .count();
        log.info("Documents: {}, text length min/avg/max: {}/{}/{} chars, ```table blocks: {}",
                lengths.getCount(), lengths.getMin(), Math.round(lengths.getAverage()), lengths.getMax(), tables);

        String longest = documents.stream()
                .sorted(LONGEST_FIRST)
                .limit(LONGEST_LIMIT)
                .map(doc -> doc.sourceId() + " (" + doc.text().length() + ")")
                .collect(Collectors.joining(", "));
        log.info("Longest documents: {}", longest);

        logTraces(documents);
    }

    private static void logTraces(List<SourceDocument> documents) {
        // trace -> sourceId -> number of trace lines in that document; TreeMap keeps sourceIds sorted for ties
        Map<String, Map<String, Integer>> byTrace = new LinkedHashMap<>();
        TRACES.forEach(trace -> byTrace.put(trace.label(), new TreeMap<>()));
        long documentsWithTraces = 0;
        for (SourceDocument doc : documents) {
            boolean found = false;
            for (String line : linesToInspect(doc.text())) {
                for (Trace trace : TRACES) {
                    if (trace.matches().test(line)) {
                        byTrace.get(trace.label()).merge(doc.sourceId(), 1, Integer::sum);
                        found = true;
                    }
                }
            }
            if (found) {
                documentsWithTraces++;
            }
        }
        log.info("Documents with AsciiDoc traces outside code blocks: {}", documentsWithTraces);
        byTrace.forEach((trace, counts) -> {
            if (counts.isEmpty()) {
                return;
            }
            String top = counts.entrySet().stream()
                    // Stable sort over a TreeMap: equal counts stay in sourceId order
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(TRACE_EXAMPLES)
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .collect(Collectors.joining(", "));
            log.info("  trace \"{}\": {} documents, top: {}", trace, counts.size(), top);
        });
    }

    /** Text outside fenced blocks plus the rows of ```table blocks: a table is text, only code is skipped. */
    private static List<String> linesToInspect(String markdown) {
        return MarkdownFences.scan(markdown).stream()
                .filter(line -> line.kind() == Kind.TEXT
                        || (line.kind() == Kind.CODE && TABLE_LANGUAGE.equals(line.language())))
                .map(MarkdownFences.Line::text)
                .toList();
    }
}
