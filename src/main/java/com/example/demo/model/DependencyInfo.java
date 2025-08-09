package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a dependency relationship between two files or components
 * in the codebase.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"sourceFile", "targetFile"})
public class DependencyInfo {
    private String sourceFile;
    private String targetFile;
    private String dependencyType = "import";
    private boolean isDirect = true;

    /**
     * Creates a new direct dependency information object with default type "import".
     *
     * @param sourceFile The file that depends on another
     * @param targetFile The file being depended on
     */
    public DependencyInfo(String sourceFile, String targetFile) {
        this.sourceFile = sourceFile;
        this.targetFile = targetFile;
    }

    @Override
    public String toString() {
        return sourceFile + " " + dependencyType + " " + targetFile +
                (isDirect ? " (direct)" : " (transitive)");
    }
}