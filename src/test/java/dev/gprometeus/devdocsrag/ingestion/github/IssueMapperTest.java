package dev.gprometeus.devdocsrag.ingestion.github;

import dev.gprometeus.devdocsrag.ingestion.github.IssueMapper.Verdict;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueMapperTest {

    private static final String PAGE = """
            [
              {
                "number": 1, "title": "Add streaming support", "state_reason": null,
                "html_url": "https://github.com/spring-projects/spring-ai/pull/1",
                "pull_request": {"url": "https://api.github.com/repos/spring-projects/spring-ai/pulls/1"},
                "labels": [], "comments": 0,
                "created_at": "2024-01-01T10:00:00Z", "closed_at": "2024-01-02T10:00:00Z"
              },
              {
                "number": 2, "title": "NPE in OllamaChatModel", "body": "Steps to reproduce...",
                "state_reason": "completed",
                "html_url": "https://github.com/spring-projects/spring-ai/issues/2",
                "labels": [{"name": "bug"}, {"name": "ollama"}], "comments": 3,
                "created_at": "2024-01-03T10:00:00Z", "closed_at": "2024-01-05T12:30:00Z"
              },
              {
                "number": 3, "title": "Support for my favourite model", "body": null,
                "state_reason": "not_planned",
                "html_url": "https://github.com/spring-projects/spring-ai/issues/3",
                "labels": [], "comments": 1,
                "created_at": "2024-01-04T10:00:00Z", "closed_at": "2024-01-06T10:00:00Z"
              },
              {
                "number": 4, "title": "NPE in Ollama chat", "body": "Same as #2",
                "state_reason": "duplicate",
                "html_url": "https://github.com/spring-projects/spring-ai/issues/4",
                "labels": [{"name": "duplicate"}], "comments": 0,
                "created_at": "2024-01-05T10:00:00Z", "closed_at": "2024-01-05T11:00:00Z"
              }
            ]
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void keepsOnlyCompletedIssues() {
        List<Verdict> verdicts = new ArrayList<>();
        for (JsonNode item : jsonMapper.readTree(PAGE)) {
            verdicts.add(IssueMapper.classify(item));
        }

        assertThat(verdicts).containsExactly(
                Verdict.PULL_REQUEST,
                Verdict.ACCEPTED,
                Verdict.NOT_COMPLETED,
                Verdict.NOT_COMPLETED);
    }

    @Test
    void mapsCompletedIssueToSnapshot() {
        JsonNode issue = jsonMapper.readTree(PAGE).get(1);
        JsonNode comment = jsonMapper.readTree("""
                {
                  "user": {"login": "maintainer", "type": "User"},
                  "author_association": "MEMBER",
                  "body": "Fixed in main",
                  "created_at": "2024-01-05T12:00:00Z"
                }
                """);

        IssueSnapshot snapshot = IssueMapper.toSnapshot(issue, List.of(IssueMapper.toComment(comment)));

        assertThat(IssueMapper.commentCount(issue)).isEqualTo(3);
        assertThat(snapshot.number()).isEqualTo(2);
        assertThat(snapshot.title()).isEqualTo("NPE in OllamaChatModel");
        assertThat(snapshot.stateReason()).isEqualTo("completed");
        assertThat(snapshot.labels()).containsExactly("bug", "ollama");
        assertThat(snapshot.createdAt()).isEqualTo(Instant.parse("2024-01-03T10:00:00Z"));
        assertThat(snapshot.closedAt()).isEqualTo(Instant.parse("2024-01-05T12:30:00Z"));
        assertThat(snapshot.comments()).containsExactly(new IssueComment(
                "maintainer", "User", "MEMBER", "Fixed in main", Instant.parse("2024-01-05T12:00:00Z")));
    }

    @Test
    void nullBodyStaysNullInsteadOfStringNull() {
        JsonNode issue = jsonMapper.readTree(PAGE).get(2);

        assertThat(IssueMapper.toSnapshot(issue, List.of()).body()).isNull();
    }
}
