package dev.gprometeus.devdocsrag.ingestion.github;

/** Ответ GitHub API, который не удалось обработать даже после ожиданий и повторов. */
public class GitHubApiException extends RuntimeException {

    private final int status;

    public GitHubApiException(int status, String uri, String details) {
        super("GitHub API " + uri + " -> HTTP " + status + ": " + details);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
