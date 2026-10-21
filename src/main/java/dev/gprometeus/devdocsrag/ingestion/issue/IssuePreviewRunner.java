package dev.gprometeus.devdocsrag.ingestion.issue;

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
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes the first issue documents to a text file and logs text statistics, then exits the application.
 * <p>
 * Usage: {@code ./gradlew bootRun --args='--spring.profiles.active=preview-issues'}
 */
@Component
@Profile("preview-issues")
public class IssuePreviewRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IssuePreviewRunner.class);

    private static final Path PREVIEW_FILE = Path.of("build", "preview", "issues-preview.txt");
    private static final int PREVIEW_LIMIT = 20;
    private static final int LONGEST_LIMIT = 5;
    private static final String REDACTED_MARKER = "[REDACTED";

    // Ties are broken by sourceId so the "longest" list is the same on every run
    private static final Comparator<SourceDocument> LONGEST_FIRST =
            Comparator.comparingInt((SourceDocument doc) -> doc.text().length()).reversed()
                    .thenComparing(SourceDocument::sourceId);

    private final IssueDocumentLoader loader;
    private final ConfigurableApplicationContext context;

    public IssuePreviewRunner(IssueDocumentLoader loader, ConfigurableApplicationContext context) {
        this.loader = loader;
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
        Files.createDirectories(PREVIEW_FILE.getParent());
        try (Writer writer = Files.newBufferedWriter(PREVIEW_FILE, StandardCharsets.UTF_8)) {
            for (SourceDocument doc : documents.subList(0, Math.min(PREVIEW_LIMIT, documents.size()))) {
                writer.write("===== " + doc.sourceId() + " | " + (doc.title() == null ? "" : doc.title())
                        + " | " + doc.sourceUrl() + " =====\n");
                writer.write(doc.text());
                writer.write("\n\n");
            }
        }
        log.info("Preview of {} documents written to {}", Math.min(PREVIEW_LIMIT, documents.size()),
                PREVIEW_FILE.toAbsolutePath());
    }

    private void logStatistics(List<SourceDocument> documents) {
        if (documents.isEmpty()) {
            log.warn("No documents loaded, nothing to analyze");
            return;
        }
        IntSummaryStatistics lengths = documents.stream()
                .mapToInt(doc -> doc.text().length())
                .summaryStatistics();
        long documentsWithRedactions = documents.stream()
                .filter(doc -> doc.text().contains(REDACTED_MARKER))
                .count();
        long redactionMarkers = documents.stream()
                .mapToLong(doc -> countOccurrences(doc.text(), REDACTED_MARKER))
                .sum();
        log.info("Documents: {}, text length min/avg/max: {}/{}/{} chars, documents with redactions: {}, redaction markers: {}",
                lengths.getCount(), lengths.getMin(), Math.round(lengths.getAverage()), lengths.getMax(),
                documentsWithRedactions, redactionMarkers);

        String longest = documents.stream()
                .sorted(LONGEST_FIRST)
                .limit(LONGEST_LIMIT)
                .map(doc -> "#" + doc.sourceId() + " (" + doc.text().length() + ")")
                .collect(Collectors.joining(", "));
        log.info("Longest documents: {}", longest);
    }

    private static long countOccurrences(String text, String marker) {
        long count = 0;
        for (int from = text.indexOf(marker); from >= 0; from = text.indexOf(marker, from + marker.length())) {
            count++;
        }
        return count;
    }
}
