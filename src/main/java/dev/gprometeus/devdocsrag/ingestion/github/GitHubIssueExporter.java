package dev.gprometeus.devdocsrag.ingestion.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exports completed issues of a repository to a JSONL snapshot, then exits the application.
 * <p>
 * Usage: {@code GITHUB_TOKEN=... ./gradlew bootRun --args='--spring.profiles.active=export-issues'}
 */
@Component
@Profile("export-issues")
public class GitHubIssueExporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueExporter.class);

    private static final int PAGE_SIZE = 100;
    private static final String ISSUES_URI = "/repos/{owner}/{repo}/issues"
            + "?state=closed&per_page=" + PAGE_SIZE + "&page={page}&sort=created&direction=asc";
    private static final String COMMENTS_URI = "/repos/{owner}/{repo}/issues/{number}/comments"
            + "?per_page=" + PAGE_SIZE + "&page={page}";

    private final GitHubProperties properties;
    private final GitHubClient client;
    private final ObjectWriter lineWriter;
    private final ConfigurableApplicationContext context;
    private final Clock clock = Clock.systemUTC();

    public GitHubIssueExporter(GitHubProperties properties, JsonMapper jsonMapper,
                               ConfigurableApplicationContext context) {
        if (!properties.hasToken()) {
            throw new IllegalStateException(
                    "GitHub token is empty: set the GITHUB_TOKEN environment variable (property devdocs.github.token)");
        }
        this.properties = properties;
        this.context = context;
        this.client = new GitHubClient(
                GitHubClient.createRestClient(properties.token()), jsonMapper, clock, Thread::sleep);
        // JSONL requires exactly one line per record, so indentation is disabled explicitly
        // regardless of the global spring.jackson.serialization.indent-output setting
        this.lineWriter = jsonMapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .without(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void run(String... args) throws IOException {
        export();
        // One-shot profile: shut down instead of keeping the context alive.
        // On failure Spring Boot closes the context and exits with a non-zero code.
        System.exit(SpringApplication.exit(context));
    }

    void export() throws IOException {
        String owner = properties.owner();
        String repo = properties.repo();
        log.info("Exporting closed issues of {}/{}", owner, repo);

        List<IssueSnapshot> snapshots = new ArrayList<>();
        int received = 0;
        int skippedPullRequests = 0;
        int skippedNotCompleted = 0;

        for (int page = 1; ; page++) {
            JsonNode items = client.getArray(ISSUES_URI, owner, repo, page);
            if (items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                received++;
                switch (IssueMapper.classify(item)) {
                    case PULL_REQUEST -> skippedPullRequests++;
                    case NOT_COMPLETED -> skippedNotCompleted++;
                    case ACCEPTED -> snapshots.add(IssueMapper.toSnapshot(item, fetchComments(owner, repo, item)));
                }
            }
            log.info("Progress: {} items received, {} issues accepted, {} requests",
                    received, snapshots.size(), client.requestCount());
        }

        // sort=created does not guarantee number order (e.g. transferred issues)
        snapshots.sort(Comparator.comparingInt(IssueSnapshot::number));
        Path target = write(snapshots, repo);

        log.info("Export finished: received={}, skippedPullRequests={}, skippedNotCompleted={}, written={}, requests={}, file={}",
                received, skippedPullRequests, skippedNotCompleted, snapshots.size(), client.requestCount(), target);
    }

    private List<IssueComment> fetchComments(String owner, String repo, JsonNode issue) {
        if (IssueMapper.commentCount(issue) == 0) {
            return List.of();
        }
        int number = issue.path("number").asInt();
        List<IssueComment> comments = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode items = client.getArray(COMMENTS_URI, owner, repo, number, page);
            for (JsonNode item : items) {
                comments.add(IssueMapper.toComment(item));
            }
            // A partial page is the last one; skip the request for an empty page
            if (items.size() < PAGE_SIZE) {
                return comments;
            }
        }
    }

    /**
     * Writes to a temp file and atomically renames it to the target.
     * The temp file lives in the target directory because ATOMIC_MOVE cannot cross file systems.
     */
    private Path write(List<IssueSnapshot> snapshots, String repo) throws IOException {
        Path dir = properties.snapshotDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(repo + "-issues-" + LocalDate.now(clock) + ".jsonl");
        Path temp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                 Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                for (IssueSnapshot snapshot : snapshots) {
                    writer.write(lineWriter.writeValueAsString(snapshot));
                    writer.write('\n');
                }
                writer.flush();
                // Flush to disk before the rename so a crash cannot leave an empty file under the final name
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            return target;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
