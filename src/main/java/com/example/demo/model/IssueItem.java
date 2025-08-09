package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an issue, improvement suggestion, or positive aspect
 * identified during code evaluation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IssueItem {

    /**
     * Represents the severity level of an issue
     */
    public enum IssueSeverity {
        /**
         * Low severity issue (minor style issue, suggestion)
         */
        LOW,

        /**
         * Medium severity issue (code smell, non-critical improvement)
         */
        MEDIUM,

        /**
         * High severity issue (significant bug, performance problem)
         */
        HIGH,

        /**
         * Critical issue (security vulnerability, crash)
         */
        CRITICAL,

        /**
         * Actual error (compilation failure, syntax error)
         */
        ERROR
    }
    private String description;
    private String filePath;
    private Integer lineNumber;
    private String codeContext;
    private IssueSeverity severity = IssueSeverity.MEDIUM;

    /**
     * Constructor with default severity (MEDIUM)
     */
    public IssueItem(String description, String filePath, Integer lineNumber, String codeContext) {
        this.description = description;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.codeContext = codeContext;
    }
}
