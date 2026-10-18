package dev.gprometeus.devdocsrag.ingestion.github;

/** GitHub API error response that persisted after rate-limit waits and retries. */
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
