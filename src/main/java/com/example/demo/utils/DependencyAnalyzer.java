package com.example.demo.utils;


import com.example.demo.model.DependencyInfo;
import com.example.demo.model.ToolResults;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for tools that analyze dependencies between components in a codebase
 */
public interface DependencyAnalyzer {
    /**
     * Analyze dependencies in a repository
     *
     * @param repoPath Path to the repository
     * @return Tool results containing dependency information
     */
    ToolResults analyze(Path repoPath);

    /**
     * Get dependencies for a specific file
     *
     * @param filePath Path to the file
     * @return List of dependencies for the file
     */
    List<DependencyInfo> getDependenciesForFile(String filePath);

    /**
     * Get files that depend on a specific file
     *
     * @param filePath Path to the file
     * @return List of files that depend on the given file
     */
    List<DependencyInfo> getDependentsForFile(String filePath);
}
