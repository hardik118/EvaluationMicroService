package com.example.demo.utils;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a node in the repository file tree.
 * Can be either a file or a directory.
 *
 * NOTE: This is a plain POJO and must NOT be a Spring bean.
 */
public class FileNode {
    /**
     * Relative or absolute path of the file or directory
     */
    @Getter
    private final String path;

    /**
     * Whether this node represents a file (true) or directory (false)
     */
    @Getter
    private final boolean isFile;

    /**
     * Parent node (null for root)
     */
    @Getter
    private final FileNode parent;

    /**
     * Child nodes (empty for files)
     */
    @Getter
    private final List<FileNode> children = new ArrayList<>();

    /**
     * File content (only populated when needed)
     */
    @Getter @Setter
    private String content;

    /**
     * File summary generated during evaluation
     */
    @Getter @Setter
    private String summary;

    /**
     * Number of lines in the file
     */
    @Getter @Setter
    private int lineCount;

    /**
     * The programming language of the file
     */
    @Getter @Setter
    private String language;

    /**
     * Create a new file node
     *
     * @param path Relative/absolute path
     * @param isFile Whether this is a file (true) or directory (false)
     * @param parent Parent node (null for root)
     */
    public FileNode(String path, boolean isFile, FileNode parent) {
        this.path = path;
        this.isFile = isFile;
        this.parent = parent;
    }

    /**
     * Add a child node to this directory node
     */
    public void addChild(FileNode childNode) {
        if (isFile) {
            throw new IllegalStateException("Cannot add children to a file node");
        }
        children.add(childNode);
    }

    /**
     * Get the file name without path
     */
    public String getName() {
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSeparator >= 0 ? path.substring(lastSeparator + 1) : path;
    }

    /**
     * Get the file extension (without the dot) or empty string if none
     */
    public String getExtension() {
        if (!isFile) return "";
        String name = getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1);
    }

    public boolean isSourceCode() {
        if (!isFile) return false;
        String ext = getExtension().toLowerCase();
        return ext.equals("java") || ext.equals("py") || ext.equals("js") || ext.equals("ts")
                || ext.equals("jsx") || ext.equals("tsx") || ext.equals("html") || ext.equals("css")
                || ext.equals("go") || ext.equals("cs") || ext.equals("php") || ext.equals("rb");
    }


    /**
     * Get the normalized relative path of this node.
     * Assumes 'path' is relative to the root of the repo.
     */
    public String getRelativePath() {
        return normalize(path);
    }

    private static String normalize(String p) {
        if (p == null) return "";
        String n = p.replace('\\', '/').trim();
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/") && n.length() > 1) n = n.substring(0, n.length() - 1);
        return n;
    }


    public boolean isConfigFile() {
        if (!isFile) return false;
        String name = getName().toLowerCase();
        return name.equals("pom.xml") ||
                name.equals("build.gradle") ||
                name.equals("package.json") ||
                name.equals("webpack.config.js") ||
                name.equals(".gitignore") ||
                name.equals("dockerfile") ||
                name.endsWith(".yaml") || name.endsWith(".yml") ||
                name.endsWith(".properties") || name.endsWith(".xml") ||
                name.endsWith(".json") || name.endsWith(".toml") || name.endsWith(".ini");
    }

    public boolean isDocumentation() {
        if (!isFile) return false;
        String name = getName().toLowerCase();
        String ext = getExtension().toLowerCase();
        return name.equals("readme.md") || name.startsWith("readme.") ||
                name.equals("contributing.md") ||
                name.equals("license") || name.equals("license.md") || name.equals("license.txt") ||
                ext.equals("md") || ext.equals("txt") ||
                path.toLowerCase().contains("doc/") || path.toLowerCase().contains("docs/");
    }

    /**
     * Get the depth of this node in the tree (0 = root)
     */
    public int getDepth() {
        if (parent == null) return 0;
        return parent.getDepth() + 1;
    }

    @Override
    public String toString() {
        return (isFile ? "File: " : "Dir: ") + path;
    }
}