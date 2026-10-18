package dev.gprometeus.devdocsrag.ingestion.github;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure functions that filter GitHub API JSON and map it to snapshot records. */
final class IssueMapper {

    enum Verdict { PULL_REQUEST, NOT_COMPLETED, ACCEPTED }

    private IssueMapper() {
    }

    static Verdict classify(JsonNode item) {
        // The /issues endpoint also returns pull requests, which carry a pull_request key
        if (item.has("pull_request")) {
            return Verdict.PULL_REQUEST;
        }
        return "completed".equals(text(item, "state_reason")) ? Verdict.ACCEPTED : Verdict.NOT_COMPLETED;
    }

    static int commentCount(JsonNode issue) {
        return issue.path("comments").asInt(0);
    }

    static IssueSnapshot toSnapshot(JsonNode issue, List<IssueComment> comments) {
        return new IssueSnapshot(
                issue.path("number").asInt(),
                text(issue, "title"),
                text(issue, "body"),
                text(issue, "html_url"),
                text(issue, "state_reason"),
                labels(issue.path("labels")),
                instant(issue, "created_at"),
                instant(issue, "closed_at"),
                comments);
    }

    static IssueComment toComment(JsonNode comment) {
        JsonNode user = comment.path("user");   // null for deleted accounts
        return new IssueComment(
                text(user, "login"),
                text(user, "type"),
                text(comment, "author_association"),
                text(comment, "body"),
                instant(comment, "created_at"));
    }

    private static List<String> labels(JsonNode labels) {
        List<String> result = new ArrayList<>();
        for (JsonNode label : labels) {
            // The API returns {name: ...} objects but also allows plain strings
            String name = label.isString() ? label.stringValue() : text(label, "name");
            if (name != null) {
                result.add(name);
            }
        }
        return result;
    }

    /** Returns null for missing fields, JSON null and non-string values rather than the string "null". */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }
}
