package com.example.demo.utils;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Minimal RepositoryTree wrapper that exposes file-path helpers for building EvaluationContext
 * without using the database.
 *
 * NOTE:
 * - This is a simple data holder built by RepositoryTreeBuilder; it should NOT be a Spring bean.
 * - Added getFileCount() and getDirectoryCount() to support logging in the service.

 * Tree of repository contents.
 */
public class RepositoryTree {

    private final FileNode root;

    public RepositoryTree(FileNode root) {
        this.root = root;
    }

    /** Expose the root node if callers need to traverse directly. */
    public FileNode getRoot() {
        return root;
    }

    /**
     * Count all files in the tree.
     */
    public int getFileCount() {
        return countFiles(root);
    }

    /**
     * Count all directories in the tree (excludes the root directory from the count).
     */
    public int getDirectoryCount() {
        int total = countDirs(root);
        // Exclude the artificial root node if it's a directory
        return total > 0 ? total - 1 : 0;
    }

    private int countFiles(FileNode node) {
        if (node == null) return 0;
        int c = node.isFile() ? 1 : 0;
        if (node.getChildren() != null) {
            for (FileNode child : node.getChildren()) {
                c += countFiles(child);
            }
        }
        return c;
    }

    private int countDirs(FileNode node) {
        if (node == null) return 0;
        int c = node.isFile() ? 0 : 1;
        if (node.getChildren() != null) {
            for (FileNode child : node.getChildren()) {
                c += countDirs(child);
            }
        }
        return c;
    }

    /**
     * Get all files as absolute paths (as stored in FileNode for files).
     */
    public Collection<String> getAllFileAbsolutePaths() {
        List<String> out = new ArrayList<>();
        collectFiles(root, out, null);
        return out;
    }

    /**
     * Get all files as repo-relative paths by relativizing against repoRoot.
     * If a node already contains a relative path, it will simply be normalized.
     */
    public Collection<String> getAllFilePaths(Path repoRoot) {
        List<String> out = new ArrayList<>();
        collectFiles(root, out, repoRoot);
        return out;
    }

    private void collectFiles(FileNode node, List<String> out, Path repoRoot) {
        if (node == null) return;

        if (node.isFile()) {
            String p = node.getPath(); // For files this may be absolute from your builder
            if (p != null && !p.isBlank()) {
                String normalized;
                if (repoRoot != null) {
                    try {
                        Path rel = repoRoot.toAbsolutePath().normalize()
                                .relativize(Paths.get(p).toAbsolutePath().normalize());
                        normalized = rel.toString().replace('\\', '/');
                    } catch (IllegalArgumentException ex) {
                        // If relativize fails (different roots), fallback to normalization only
                        normalized = p.replace('\\', '/');
                    }
                } else {
                    normalized = p.replace('\\', '/');
                }
                out.add(normalized);
            }
        }

        if (node.getChildren() != null) {
            for (FileNode child : node.getChildren()) {
                collectFiles(child, out, repoRoot);
            }
        }
    }

    // ---------- NEW: Provide FileNode map keyed by repo-relative path ----------

    /**
     * Collect source files keyed by path. If repoRoot is provided, keys are repo-relative normalized paths
     * (recommended, so they match EvaluationContext keys). Otherwise keys are normalized node.getPath().
     */
    public Map<String, FileNode> collectSourceFiles(Path repoRoot) {
        Map<String, FileNode> out = new LinkedHashMap<>();
        collectSourceFilesRecursive(root, out, repoRoot);
        return out;
    }

    /** Convenience overload: keys will be normalized absolute paths if builder stored absolute paths. */
    public Map<String, FileNode> collectSourceFiles() {
        return collectSourceFiles(null);
    }

    private void collectSourceFilesRecursive(FileNode node, Map<String, FileNode> out, Path repoRoot) {
        if (node == null) return;

        if (node.isFile()) {
            String p = node.getPath();
            if (p != null && !p.isBlank()) {
                String key;
                if (repoRoot != null) {
                    try {
                        Path rel = repoRoot.toAbsolutePath().normalize()
                                .relativize(Paths.get(p).toAbsolutePath().normalize());
                        key = rel.toString().replace('\\', '/');
                    } catch (IllegalArgumentException ex) {
                        key = p.replace('\\', '/');
                    }
                } else {
                    key = p.replace('\\', '/');
                }
                out.put(key, node);
            }
        } else if (node.getChildren() != null) {
            for (FileNode child : node.getChildren()) {
                collectSourceFilesRecursive(child, out, repoRoot);
            }
        }
    }
}