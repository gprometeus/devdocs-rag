package dev.gprometeus.devdocsrag.ingestion.github;

import java.time.Instant;

/**
 * Комментарий к issue в снимке.
 *
 * @param authorType        user.type: "User" или "Bot"
 * @param authorAssociation author_association: OWNER, MEMBER, CONTRIBUTOR, NONE...
 */
public record IssueComment(
        String author,
        String authorType,
        String authorAssociation,
        String body,
        Instant createdAt
) {
}
