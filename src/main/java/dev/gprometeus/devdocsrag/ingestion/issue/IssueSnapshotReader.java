package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads a JSONL issue snapshot: one line per {@link IssueSnapshot}. */
@Component
public class IssueSnapshotReader {

    private final JsonMapper mapper;

    public IssueSnapshotReader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public List<IssueSnapshot> read(Path file) {
        try (var lines = Files.lines(file)) {
            return lines
                    .filter(line -> !line.isBlank())
                    .map(line -> mapper.readValue(line, IssueSnapshot.class))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read snapshot " + file, e);
        }
    }
}
