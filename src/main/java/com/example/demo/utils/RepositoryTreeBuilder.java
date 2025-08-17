package com.example.demo.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds an in-memory tree representation of a repository and can optionally
 * fire node callbacks for each folder/file discovered.
 */
public class RepositoryTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(RepositoryTreeBuilder.class);

    /**
     * Build a tree representation of the repository
     *
     * @param repoPath Path to the repository root (must be a LOCAL directory, not a URL)
     * @return Tree representation of the repository
     */
    public static RepositoryTree buildTree(Path repoPath) {
        return buildTree(repoPath, null);
    }

    /**
     * Build a tree representation and notify the listener for each node.
     *
     * @param repoPath Path to the repository root
     * @param listener NodeListener to receive folder/file events (may be null)
     * @return Tree representation of the repository
     */
    public static RepositoryTree buildTree(Path repoPath, NodeListener listener) {
        try {
            // Validate input path
            if (repoPath == null) {
                throw new IllegalArgumentException("repoPath is null");
            }
            String asString = repoPath.toString();
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

            // Normalize root
            Path root = repoPath.toRealPath(LinkOption.NOFOLLOW_LINKS);

            // Create root node
            FileNode rootNode = new FileNode("", false, null);
            Map<Path, FileNode> dirMap = new HashMap<>();
            dirMap.put(root, rootNode);

            // If listener present, notify root
            if (listener != null) {
                listener.onFolder("", null);
            }

            // Walk the file tree
            Files.walkFileTree(root,
                    EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                    Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (shouldIgnoreDirectory(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path parentDir = dir.getParent();
                            FileNode parentNode = dirMap.get(parentDir);
                            if (parentNode == null) {
                                log.warn("Parent node not found for directory: {}", dir);
                                return FileVisitResult.CONTINUE;
                            }
                            String relativePath = normalize(root.relativize(dir).toString());
                            FileNode dirNode = new FileNode(relativePath, false, parentNode);
                            parentNode.addChild(dirNode);
                            dirMap.put(dir, dirNode);

                            // Notify listener
                            if (listener != null) {
                                String parentRel = normalize(root.relativize(parentDir).toString());
                                listener.onFolder(relativePath, parentRel.isEmpty() ? null : parentRel);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (shouldIgnoreFile(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            Path parentDir = file.getParent();
                            FileNode parentNode = dirMap.get(parentDir);
                            if (parentNode == null) {
                                log.warn("Parent node not found for file: {}", file);
                                return FileVisitResult.CONTINUE;
                            }
                            String relativePath = normalize(root.relativize(file).toString());
                            String absolutePath = root.resolve(relativePath).toString();
                            FileNode fileNode = new FileNode(absolutePath, true, parentNode);
                            parentNode.addChild(fileNode);

                            // Notify listener
                            if (listener != null) {
                                String parentRel = normalize(root.relativize(parentDir).toString());
                                listener.onFile(relativePath,
                                        parentRel.isEmpty() ? null : parentRel,
                                        file);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            log.warn("Failed to visit file: {}", file, exc);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return new RepositoryTree(rootNode);

        } catch (IOException e) {
            log.error("Error building repository tree", e);
            throw new RuntimeException("Failed to build repository tree", e);
        }
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        return p;
    }

    private static boolean shouldIgnoreDirectory(Path dir) {
        String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
        if (dirName.startsWith(".")) return true;
        return dirName.equals("node_modules")
                || dirName.equals("target")
                || dirName.equals("build")
                || dirName.equals("dist")
                || dirName.equals("venv")
                || dirName.equals("__pycache__")
                || dirName.equals("bin")
                || dirName.equals("obj");
    }

    private static boolean shouldIgnoreFile(Path file) {
        String fileName = file.getFileName() != null ? file.getFileName().toString() : "";
        if (fileName.startsWith(".")) {
            return true;
        }
        if (FileUtil.isFileTooLarge(file)
                || (!FileUtil.isTextFile(file) && !isImportantBinaryFile(fileName))) {
            return true;
        }
        return false;
    }

    private static boolean isImportantBinaryFile(String fileName) {
        return fileName.equals("pom.xml")
                || fileName.equals("build.gradle")
                || fileName.equals("package.json")
                || fileName.equals("package-lock.json");
    }
}