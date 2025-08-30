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
    private String title;
    private String filePath;
    private Integer lineStart;
    private Integer lineEnd;
    private IssueSeverity severity;
    private String codeSnippet;

    /**
     * Constructor with default severity (MEDIUM)
     */
    public IssueItem(String title, String filePath, Integer lineStart, Integer lineEnd, String codeSnippet, IssueSeverity severity) {
        this.title = title;
        this.filePath = filePath;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.codeSnippet = codeSnippet;
        this.severity = severity;
    }

}
