package com.example.demo.utils;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class RepositoryTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(RepositoryTreeBuilder.class);

    /**
     * Build a tree representation of the repository
     *
     * @param repoPath Path to the repository root (must be a LOCAL directory, not a URL)
     * @return Tree representation of the repository
     */
    public static RepositoryTree buildTree(Path repoPath) {
        try {
            // Validate input path
            if (repoPath == null) {
                throw new IllegalArgumentException("repoPath is null");
            }
            String asString = repoPath.toString();
            // Guard against passing remote URLs or SSH refs
            if (asString.startsWith("http://") || asString.startsWith("https://")
                    || asString.matches("^[^@\\s]+@[^:\\s]+:.*$")) {
                throw new IllegalArgumentException(
                        "repoPath looks like a remote URL/reference (" + asString + "). " +
                                "Clone the repository first and pass a local directory Path.");
            }
            if (!Files.exists(repoPath)) {
                throw new NoSuchFileException(asString);
            }
            if (!Files.isDirectory(repoPath)) {
                throw new NotDirectoryException(asString);
            }

            // Normalize to real path (without following symlinks for the root)
            Path root = repoPath.toRealPath(LinkOption.NOFOLLOW_LINKS);

            // Create root node for the repository
            FileNode rootNode = new FileNode("", false, null);

            // Map to keep track of directory nodes
            Map<Path, FileNode> dirMap = new HashMap<>();
            dirMap.put(root, rootNode);

            // Walk the file tree
            Files.walkFileTree(
                    root,
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
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }

                            // Get parent directory node
                            Path parentDir = dir.getParent();
                            FileNode parentNode = dirMap.get(parentDir);

                            if (parentNode == null) {
                                log.warn("Parent node not found for directory: {}", dir);
                                return FileVisitResult.CONTINUE;
                            }

                            // Create node for this directory (keep as relative for readability)
                            String relativePath = root.relativize(dir).toString();
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

                            // Store ABSOLUTE path for files so later Files.readString(...) works
                            String relativePath = root.relativize(file).toString();
                            String absolutePath = root.resolve(relativePath).toString();
                            FileNode fileNode = new FileNode(absolutePath, true, parentNode);

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
        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";

        // Skip hidden directories
        if (dirName.startsWith(".")) {
            return true; // covers .git, .idea, .vscode, etc.
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
        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";

        // Skip hidden files
        if (fileName.startsWith(".")) {
            return true;
        }

        // Skip very large files and non-text files, unless whitelisted
        if (FileUtil.isFileTooLarge(file) ||
                (!FileUtil.isTextFile(file) && !isImportantBinaryFile(fileName))) {
            return true;
        }

        return false;
    }

    /**
     * Check if a non-text file is important (rare)
     */
    private static boolean isImportantBinaryFile(String fileName) {
        // These are actually text, but we keep this hook if you add real binary formats later.
        return fileName.equals("pom.xml") ||
                fileName.equals("build.gradle") ||
                fileName.equals("package.json") ||
                fileName.equals("package-lock.json");
    }
}