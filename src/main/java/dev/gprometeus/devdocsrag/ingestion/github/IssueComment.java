package dev.gprometeus.devdocsrag.ingestion.github;

import java.time.Instant;

/**
 * Issue comment in a snapshot.
 *
 * @param authorType        user.type: "User" or "Bot"
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
