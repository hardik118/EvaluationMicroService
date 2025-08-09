package com.example.demo.utils;

import lombok.Getter;

/**
 * Represents the file and directory structure of a repository
 */
public class RepositoryTree {

    @Getter
    private final FileNode root;

    @Getter
    private int fileCount = 0;

    @Getter
    private int directoryCount = 0;

    public RepositoryTree(FileNode root) {
        this.root = root;
        countNodes(root);
    }

    /**
     * Recursively count files and directories in the tree
     */
    private void countNodes(FileNode node) {
        if (node.isFile()) {
            fileCount++;
        } else {
            directoryCount++;
            for (FileNode child : node.getChildren()) {
                countNodes(child);
            }
        }
    }

    /**
     * Find a node by its path
     *
     * @param path Path to search for
     * @return The node if found, null otherwise
     */
    public FileNode findByPath(String path) {
        return findByPath(root, path);
    }

    private FileNode findByPath(FileNode node, String path) {
        if (node.getPath().equals(path)) {
            return node;
        }

        if (node.isFile()) {
            return null;
        }

        for (FileNode child : node.getChildren()) {
            FileNode found = findByPath(child, path);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository Tree:\n");
        appendNodeToString(root, sb, 0);
        sb.append("\nTotal: ").append(fileCount).append(" files, ")
                .append(directoryCount).append(" directories");
        return sb.toString();
    }

    private void appendNodeToString(FileNode node, StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
        sb.append(node.isFile() ? "📄 " : "📁 ");
        sb.append(FileUtil.getFileName(node.getPath())).append("\n");

        if (!node.isFile()) {
            for (FileNode child : node.getChildren()) {
                appendNodeToString(child, sb, level + 1);
            }
        }
    }
}
