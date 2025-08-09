package com.example.demo.model;


import java.util.*;

/**
 * Context information for repository evaluation
 */
public class EvaluationContext {
    // File information
    private final Map<String, String> fileSummaries = new HashMap<>();

    // Folder information
    private final Map<String, List<String>> folderContents = new HashMap<>();

    // Dependencies between files
    private final Map<String, Set<String>> fileDependencies = new HashMap<>();

    /**
     * Add a summary of what a file does.
     *
     * @param filePath Path to the file
     * @param summary Brief description of what the file does
     */
    public void addFileSummary(String filePath, String summary) {
        fileSummaries.put(filePath, summary);

        // Also track this file in its parent folder
        String folderPath = getParentPath(filePath);
        folderContents.computeIfAbsent(folderPath, k -> new ArrayList<>()).add(filePath);
    }

    /**
     * Add a dependency relationship between files.
     *
     * @param sourceFile File that depends on another
     * @param targetFile File being depended on
     */
    public void addDependency(String sourceFile, String targetFile) {
        fileDependencies.computeIfAbsent(sourceFile, k -> new HashSet<>()).add(targetFile);
    }

    /**
     * Get the summary of what a file does.
     *
     * @param filePath Path to the file
     * @return Summary of the file's purpose or null if not available
     */
    public String getFileSummary(String filePath) {
        return fileSummaries.get(filePath);
    }

    /**
     * Get summaries of all files in a folder.
     *
     * @param folderPath Path to the folder
     * @return Map of file paths to summaries for files in the folder
     */
    public Map<String, String> getFilesInFolder(String folderPath) {
        Map<String, String> result = new HashMap<>();

        List<String> files = folderContents.getOrDefault(folderPath, Collections.emptyList());
        for (String filePath : files) {
            String summary = fileSummaries.get(filePath);
            if (summary != null) {
                result.put(filePath, summary);
            }
        }

        return result;
    }

    /**
     * Get dependencies of a file.
     *
     * @param filePath Path to the file
     * @return Set of files that this file depends on
     */
    public Set<String> getDependencies(String filePath) {
        return fileDependencies.getOrDefault(filePath, Collections.emptySet());
    }

    /**
     * Get files that depend on a specific file.
     *
     * @param filePath Path to the file
     * @return Set of files that depend on this file
     */
    public Set<String> getDependents(String filePath) {
        Set<String> dependents = new HashSet<>();

        for (Map.Entry<String, Set<String>> entry : fileDependencies.entrySet()) {
            if (entry.getValue().contains(filePath)) {
                dependents.add(entry.getKey());
            }
        }

        return dependents;
    }

    /**
     * Get related files (dependencies and dependents).
     *
     * @param filePath Path to the file
     * @return Map of related files and their relationship type
     */
    public Map<String, String> getRelatedFiles(String filePath) {
        Map<String, String> relatedFiles = new HashMap<>();

        // Add dependencies
        for (String dep : getDependencies(filePath)) {
            relatedFiles.put(dep, "depends on");
        }

        // Add dependents
        for (String dep : getDependents(filePath)) {
            relatedFiles.put(dep, "used by");
        }

        return relatedFiles;
    }

    /**
     * Get context information relevant for evaluating a file.
     *
     * @param filePath Path to the file being evaluated
     * @return Map with contextual information
     */
    public Map<String, Object> getFileContext(String filePath) {
        Map<String, Object> context = new HashMap<>();

        // Add information about files in the same folder
        String folderPath = getParentPath(filePath);
        context.put("filesInSameFolder", getFilesInFolder(folderPath));

        // Add information about dependencies and dependents
        context.put("dependencies", getDependencies(filePath));
        context.put("dependents", getDependents(filePath));

        return context;
    }

    /**
     * Extract the parent path from a file path.
     */
    private String getParentPath(String filePath) {
        int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return (lastSeparator > 0) ? filePath.substring(0, lastSeparator) : "";
    }

    public void addComplexityMetric(String filePath, ComplexityMetric metric) {
    }

    /**
     * Get all file summaries.
     *
     * @return Unmodifiable map of file paths to summaries
     */
    public Map<String, String> getFileSummaries() {
        return Collections.unmodifiableMap(fileSummaries);
    }

    /**
     * Get the total number of files with summaries.
     *
     * @return Count of files with summaries
     */
    public int getFileCount() {
        return fileSummaries.size();
    }
}