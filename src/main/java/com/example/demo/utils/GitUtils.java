package com.example.demo.utils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class GitUtils {

    private static final Logger log = LoggerFactory.getLogger(GitUtils.class);

    @Value("${evaluator.git.username:}")
    private String gitUsername;

    @Value("${evaluator.git.passwordOrToken:}")
    private String gitPasswordOrToken;

    public Path cloneRepository(String rawUrl) {
        String repoUrl = normalizeUrl(rawUrl);
        ensureHttpOrSshUrl(repoUrl);

        try {
            Path tempDir = Files.createTempDirectory("repo-eval-");
            log.info("Cloning {} into {}", repoUrl, tempDir.toAbsolutePath());

            CredentialsProvider creds = null;
            if (!gitUsername.isBlank() && !gitPasswordOrToken.isBlank()) {
                creds = new UsernamePasswordCredentialsProvider(gitUsername, gitPasswordOrToken);
            }

            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir.toFile())
                    .setCloneAllBranches(false)
                    .setDepth(1)
                    .setCredentialsProvider(creds)
                    .call()) {
                // Clone complete
            }

            // Sanity check
            if (!Files.isDirectory(tempDir)) {
                throw new GitServiceException("Clone succeeded but directory missing: " + tempDir);
            }
            return tempDir;
        } catch (Exception e) {
            throw new GitServiceException("Failed to clone " + repoUrl + ": " + e.getMessage(), e);
        }
    }

    public void cleanupRepository(Path repoDir) {
        if (repoDir == null) return;
        try {
            // Recursively delete the temp directory
            Files.walk(repoDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("Failed to cleanup repo dir {}: {}", repoDir, e.getMessage());
        }
    }

    private String normalizeUrl(String input) {
        if (input == null) throw new GitServiceException("Repository URL is null");
        if (input.startsWith("https:/") && !input.startsWith("https://")) {
            String fixed = input.replaceFirst("^https:/+", "https://");
            log.warn("Normalized malformed URL from {} to {}", input, fixed);
            input = fixed;
        }
        return input;
    }

    private void ensureHttpOrSshUrl(String url) {
        if (url.startsWith("http")) {
            try { new URL(url); } catch (MalformedURLException e) {
                throw new GitServiceException("Malformed repository URL: " + url, e);
            }
        } else if (url.matches("^[^@\\s]+@[^:\\s]+:.*$")) {
            // ssh style is fine
        } else {
            throw new GitServiceException("Unsupported repository URL scheme: " + url);
        }
    }

    public static class GitServiceException extends RuntimeException {
        public GitServiceException(String msg) { super(msg); }
        public GitServiceException(String msg, Throwable cause) { super(msg, cause); }
    }
}