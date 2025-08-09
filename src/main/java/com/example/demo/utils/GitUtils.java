package com.example.demo.utils;


import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class GitUtils {

    @Value("${repo.evaluator.clone.directory:#{systemProperties['java.io.tmpdir']}/repo-evaluator}")
    private String baseCloneDirectory;

    /**
     * Clone a Git repository from a URL to a directory
     *
     * @param repoUrl URL of the Git repository to clone
     * @return Path to the cloned repository
     * @throws GitServiceException if cloning fails
     */
    public Path cloneRepository(String repoUrl) throws GitServiceException {
        try {
            // Create base directory if it doesn't exist
            Path baseDir = Paths.get(baseCloneDirectory);
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
                log.info("Created base directory: {}", baseDir);
            }

            // Extract repo name from URL
            String repoName = extractRepoName(repoUrl);

            // Create timestamp for directory name
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

            // Create a unique directory with repo name and timestamp
            String dirName = String.format("%s-%s", repoName, timestamp);
            Path repoDir = baseDir.resolve(dirName);
            Files.createDirectories(repoDir);

            // Clone the repository
            log.info("Cloning repository: {} to {}", repoUrl, repoDir);
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir.toFile())
                    .setCloneAllBranches(false) // Only clone default branch
                    .setCloneSubmodules(false)  // Skip submodules for faster cloning
                    .call();

            log.info("Repository cloned to: {}", repoDir);
            return repoDir;

        } catch (IOException e) {
            throw new GitServiceException("Failed to create directory for repository", e);
        } catch (GitAPIException e) {
            throw new GitServiceException("Failed to clone repository: " + e.getMessage(), e);
        }
    }

    /**
     * Extract repository name from URL
     */
    private String extractRepoName(String repoUrl) {
        String name = repoUrl.trim();

        // Remove trailing .git if present
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }

        // Extract last part of the URL (the repo name)
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < name.length() - 1) {
            name = name.substring(lastSlash + 1);
        }

        return name;
    }

    /**
     * Clean up a cloned repository directory
     *
     * @param repoPath Path to the cloned repository
     */
    public void cleanupRepository(Path repoPath) {
        if (repoPath != null) {
            try {
                // Recursively delete the directory
                deleteDirectory(repoPath.toFile());
                log.info("Cleaned up repository: {}", repoPath);
            } catch (IOException e) {
                log.warn("Warning: Failed to clean up repository at {}: {}", repoPath, e.getMessage());
            }
        }
    }

    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            throw new IOException("Failed to delete file: " + file);
                        }
                    }
                }
            }
            if (!directory.delete()) {
                throw new IOException("Failed to delete directory: " + directory);
            }
        }
    }

    /**
     * Custom exception for Git service errors
     */
    public static class GitServiceException extends Exception {
        public GitServiceException(String message) {
            super(message);
        }

        public GitServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}