package com.example.demo.utils;



import java.nio.file.Path;

/**
 * Callback interface for repository tree nodes.
 */
public interface NodeListener {
    /**
     * Called when a folder node is discovered.
     *
     * @param relativePath       the folder path relative to repository root (no leading slash)
     * @param parentRelativePath the parent folder's relative path, or null for root
     */
    void onFolder(String relativePath, String parentRelativePath);

    /**
     * Called when a file node is discovered.
     *
     * @param relativePath       the file path relative to repository root (no leading slash)
     * @param parentRelativePath the parent folder's relative path
     * @param absolutePath       the absolute Path to the file on disk
     */
    void onFile(String relativePath, String parentRelativePath, Path absolutePath);
}