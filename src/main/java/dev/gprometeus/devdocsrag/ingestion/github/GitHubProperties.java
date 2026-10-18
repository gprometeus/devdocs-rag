package dev.gprometeus.devdocsrag.ingestion.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "devdocs.github")
public record GitHubProperties(
        @DefaultValue("spring-projects") String owner,
        @DefaultValue("spring-ai") String repo,
        String token,
        @DefaultValue("data/snapshots") Path snapshotDir
) {
    boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /** Сгенерированный toString() записи вывел бы токен в лог при любом log.info("{}", props). */
    @Override
    public String toString() {
        return "GitHubProperties[owner=" + owner + ", repo=" + repo
                + ", token=" + (hasToken() ? "****" : "<empty>")
                + ", snapshotDir=" + snapshotDir + "]";
    }
}
