package dev.gprometeus.devdocsrag.ingestion.issue;

import dev.gprometeus.devdocsrag.ingestion.SourceDocument;
import dev.gprometeus.devdocsrag.ingestion.SourceType;
import dev.gprometeus.devdocsrag.ingestion.github.IssueSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** Turns the issue snapshot into pipeline documents: read, filter, render text, redact secrets. */
@Component
public class IssueDocumentLoader {

    private final IssueSnapshotReader reader;
    private final IssueFilter filter;
    private final IssueTextBuilder textBuilder;
    private final SecretRedactor redactor;
    private final IssueProperties props;

    public IssueDocumentLoader(IssueSnapshotReader reader, IssueFilter filter,
                               IssueTextBuilder textBuilder, SecretRedactor redactor,
                               IssueProperties props) {
        this.reader = reader;
        this.filter = filter;
        this.textBuilder = textBuilder;
        this.redactor = redactor;
        this.props = props;
    }

    public List<SourceDocument> load() {
        String snapshotRef = "snapshot:" + props.snapshotFile().getFileName();
        return reader.read(props.snapshotFile()).stream()
                .filter(filter::accept)
                // Optional.stream() yields zero or one element, so issues without text drop out here
                .flatMap(issue -> textBuilder.build(issue)
                        .map(text -> toDocument(issue, text, snapshotRef))
                        .stream())
                .toList();
    }

    private SourceDocument toDocument(IssueSnapshot issue, String text, String snapshotRef) {
        return new SourceDocument(
                SourceType.ISSUE,
                String.valueOf(issue.number()),
                issue.htmlUrl(),
                issue.title() == null ? null : redactor.redact(issue.title()),
                snapshotRef,
                redactor.redact(text));
    }
}
