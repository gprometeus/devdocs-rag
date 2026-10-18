package dev.gprometeus.devdocsrag.ingestion.github;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Чистые функции над JSON из GitHub API: фильтрация и маппинг в записи снимка. Без сети. */
final class IssueMapper {

    enum Verdict { PULL_REQUEST, NOT_COMPLETED, ACCEPTED }

    private IssueMapper() {
    }

    static Verdict classify(JsonNode item) {
        // /issues возвращает и PR тоже; у PR есть ключ pull_request
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
        JsonNode user = comment.path("user");   // null у удалённых аккаунтов
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
            // API отдаёт объекты {name: ...}, но формально допускает и строки
            String name = label.isString() ? label.stringValue() : text(label, "name");
            if (name != null) {
                result.add(name);
            }
        }
        return result;
    }

    /** null для отсутствующего поля, JSON null и не-строк, вместо исключения или строки "null". */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }
}
