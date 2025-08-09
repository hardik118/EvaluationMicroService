package com.example.demo.utils;


import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds a tree representation of a repository's file structure
 */
@Slf4j
public class RepositoryTreeBuilder {

    /**
     * Build a tree representation of the repository
     *
     * @param repoPath Path to the repository root
     * @return Tree representation of the repository
     */
    public static RepositoryTree buildTree(Path repoPath) {
        try {
            // Create root node for the repository
            FileNode rootNode = new FileNode("", false, null);

            // Map to keep track of directory nodes
            Map<Path, FileNode> dirMap = new HashMap<>();
            dirMap.put(repoPath, rootNode);

            // Walk the file tree
            Files.walkFileTree(
                    repoPath,
                    EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                    Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // Skip hidden directories and specific directories we want to ignore
                            if (shouldIgnoreDirectory(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }

                            // Skip the root directory (already created)
                            if (dir.equals(repoPath)) {
                                return FileVisitResult.CONTINUE;
                            }

                            // Get parent directory node
                            Path parentDir = dir.getParent();
                            FileNode parentNode = dirMap.get(parentDir);

                            if (parentNode == null) {
                                log.warn("Parent node not found for directory: {}", dir);
                                return FileVisitResult.CONTINUE;
                            }

                            // Create node for this directory
                            String relativePath = repoPath.relativize(dir).toString();
                            FileNode dirNode = new FileNode(relativePath, false, parentNode);

                            // Add to parent's children
                            parentNode.addChild(dirNode);

                            // Add to directory map
                            dirMap.put(dir, dirNode);

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            // Skip hidden files and files we want to ignore
                            if (shouldIgnoreFile(file)) {
                                return FileVisitResult.CONTINUE;
                            }

                            // Get parent directory node
                            Path parentDir = file.getParent();
                            FileNode parentNode = dirMap.get(parentDir);

                            if (parentNode == null) {
                                log.warn("Parent node not found for file: {}", file);
                                return FileVisitResult.CONTINUE;
                            }

                            // Create node for this file
                            String relativePath = repoPath.relativize(file).toString();
                            FileNode fileNode = new FileNode(relativePath, true, parentNode);

                            // Add to parent's children
                            parentNode.addChild(fileNode);

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            log.warn("Failed to visit file: {}", file, exc);
                            return FileVisitResult.CONTINUE;
                        }
                    }
            );

            return new RepositoryTree(rootNode);

        } catch (IOException e) {
            log.error("Error building repository tree", e);
            throw new RuntimeException("Failed to build repository tree", e);
        }
    }

    /**
     * Check if a directory should be ignored
     */
    private static boolean shouldIgnoreDirectory(Path dir) {
        String dirName = dir.getFileName().toString();

        // Skip hidden directories
        if (dirName.startsWith(".")) {
            return true;
        }

        // Skip common directories to ignore
        return dirName.equals("node_modules") ||
                dirName.equals("target") ||
                dirName.equals("build") ||
                dirName.equals("dist") ||
                dirName.equals("venv") ||
                dirName.equals("__pycache__") ||
                dirName.equals("bin") ||
                dirName.equals("obj");
    }

    /**
     * Check if a file should be ignored
     */
    private static boolean shouldIgnoreFile(Path file) {
        String fileName = file.getFileName().toString();

        // Skip hidden files
        if (fileName.startsWith(".")) {
            return true;
        }

        // Skip very large files and binary files we don't want to analyze
        if (FileUtil.isFileTooLarge(file) ||
                (!FileUtil.isTextFile(file) && !isImportantBinaryFile(fileName))) {
            return true;
        }

        return false;
    }

    /**
     * Check if a binary file is important (e.g., might need to be analyzed)
     */
    private static boolean isImportantBinaryFile(String fileName) {
        return fileName.equals("pom.xml") ||
                fileName.equals("build.gradle") ||
                fileName.equals("package.json") ||
                fileName.equals("package-lock.json");
    }
}
