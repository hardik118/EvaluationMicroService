package com.example.demo.utils;


import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple implementation of code metrics analyzer inspired by JavaNCSS.
 * This provides basic complexity metrics without external dependencies.
 */
@Slf4j
public class Javancss {

    @Getter
    private final Map<String, FileMetrics> fileMetrics = new HashMap<>();

    @Getter
    private final List<FunctionMetric> functionMetrics = new ArrayList<>();

    @Getter
    private int totalNcss = 0;

    @Getter
    private int totalCcn = 0;

    @Getter
    private int totalMethods = 0;

    @Getter
    private int totalClasses = 0;

    /**
     * Create a new Javancss instance and analyze the given files
     *
     * @param filePaths Array of file paths to analyze
     */
    public Javancss(String[] filePaths) {
        for (String filePath : filePaths) {
            try {
                analyzeFile(filePath);
            } catch (IOException e) {
                log.error("Error analyzing file: {}", filePath, e);
            }
        }
    }

    /**
     * Analyze a single file
     */
    private void analyzeFile(String filePath) throws IOException {
        if (!filePath.endsWith(".java")) {
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        String content = Files.readString(Paths.get(filePath));

        // Extract package and class information
        String packageName = extractPackageName(content);
        List<String> classNames = extractClassNames(content);
        String className = classNames.isEmpty() ? "Unknown" : classNames.get(0);

        // Calculate file-level metrics
        FileMetrics metrics = calculateMetrics(content);
        fileMetrics.put(filePath, metrics);

        // Extract method-level metrics
        List<FunctionMetric> methods = extractFunctionMetrics(content, filePath, packageName, className);
        functionMetrics.addAll(methods);

        // Update totals
        totalNcss += metrics.getNcss();
        totalCcn += metrics.getCcn();
        totalMethods += metrics.getMethodCount();
        totalClasses += metrics.getClassCount();
    }

    /**
     * Extract the package name from Java source code
     */
    private String extractPackageName(String content) {
        Pattern packagePattern = Pattern.compile("package\\s+([\\w\\.]+);");
        Matcher matcher = packagePattern.matcher(content);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return "default";
    }

    /**
     * Extract class names from Java source code
     */
    private List<String> extractClassNames(String content) {
        List<String> classNames = new ArrayList<>();
        Pattern classPattern = Pattern.compile("class\\s+(\\w+)");
        Matcher matcher = classPattern.matcher(content);

        while (matcher.find()) {
            classNames.add(matcher.group(1));
        }

        return classNames;
    }

    /**
     * Calculate metrics for a file
     */
    private FileMetrics calculateMetrics(String content) {
        int ncss = calculateNcss(content);
        int methodCount = countMethods(content);
        int classCount = countClasses(content);
        int ccn = calculateCcn(content);

        return new FileMetrics(ncss, ccn, methodCount, classCount);
    }

    /**
     * Extract function metrics from Java source code
     */
    private List<FunctionMetric> extractFunctionMetrics(String content, String filePath,
                                                        String packageName, String className) {
        List<FunctionMetric> metrics = new ArrayList<>();

        // Pattern for method declarations with body extraction
        Pattern methodPattern = Pattern.compile(
                "(public|protected|private|static|\\s) +[\\w\\<\\>\\[\\]]+\\s+(\\w+)\\s*\\([^\\)]*\\)\\s*\\{([^{}]*(?:\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}[^{}]*)*)\\}"
        );

        Matcher matcher = methodPattern.matcher(content);

        while (matcher.find()) {
            String methodName = matcher.group(2);
            String methodBody = matcher.group(3);

            // Calculate method-specific metrics
            int ncss = calculateNcss(methodBody);
            int ccn = calculateCcn(methodBody);

            // Full method name includes package and class
            String name = packageName + "." + className + "." + methodName;

            FunctionMetric functionMetric = new FunctionMetric(
                    name,
                    filePath,
                    ncss,
                    ccn,
                    packageName,
                    className,
                    methodName
            );

            metrics.add(functionMetric);
        }

        return metrics;
    }

    /**
     * Calculate non-commenting source statements
     */
    private int calculateNcss(String content) {
        // Remove comments
        String noComments = content.replaceAll("//.*?$|/\\*.*?\\*/", "");

        // Count semicolons (statement terminators)
        int semicolons = countOccurrences(noComments, ';');

        // Count opening braces (blocks)
        int openBraces = countOccurrences(noComments, '{');

        // Approximation of NCSS
        return semicolons + openBraces;
    }

    /**
     * Calculate cyclomatic complexity
     */
    private int calculateCcn(String content) {
        // Remove strings and comments
        String code = content.replaceAll("\".*?\"|//.*?$|/\\*.*?\\*/", "");

        // Count decision points (basic approximation)
        int ifCount = countOccurrences(code, "if\\s*\\(");
        int forCount = countOccurrences(code, "for\\s*\\(");
        int whileCount = countOccurrences(code, "while\\s*\\(");
        int caseCount = countOccurrences(code, "case\\s+");
        int catchCount = countOccurrences(code, "catch\\s*\\(");
        int andCount = countOccurrences(code, "&&");
        int orCount = countOccurrences(code, "\\|\\|");

        // CCN = decision points + 1
        return ifCount + forCount + whileCount + caseCount + catchCount + andCount + orCount + 1;
    }

    /**
     * Count method declarations
     */
    private int countMethods(String content) {
        // Pattern for method declarations (simplified)
        Pattern methodPattern = Pattern.compile(
                "(public|protected|private|static|\\s) +[\\w\\<\\>\\[\\]]+\\s+(\\w+) *\\([^\\)]*\\) *(\\{|[^;])"
        );

        Matcher matcher = methodPattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Count class declarations
     */
    private int countClasses(String content) {
        // Pattern for class declarations (simplified)
        Pattern classPattern = Pattern.compile(
                "(class|interface|enum)\\s+\\w+"
        );

        Matcher matcher = classPattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Count occurrences of a character in a string
     */
    private int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count occurrences of a pattern in a string
     */
    private int countOccurrences(String str, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);

        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Get metrics for a specific file
     *
     * @param filePath Path to the file
     * @return Metrics for the file or null if not analyzed
     */
    public FileMetrics getMetrics(String filePath) {
        return fileMetrics.get(filePath);
    }

    /**
     * Class to hold metrics for a file
     */
    @Getter
    public static class FileMetrics {
        private final int ncss;         // Non-commenting source statements
        private final int ccn;          // Cyclomatic complexity
        private final int methodCount;  // Number of methods
        private final int classCount;   // Number of classes

        public FileMetrics(int ncss, int ccn, int methodCount, int classCount) {
            this.ncss = ncss;
            this.ccn = ccn;
            this.methodCount = methodCount;
            this.classCount = classCount;
        }

        @Override
        public String toString() {
            return "FileMetrics{" +
                    "ncss=" + ncss +
                    ", ccn=" + ccn +
                    ", methodCount=" + methodCount +
                    ", classCount=" + classCount +
                    '}';
        }
    }

    /**
     * Class to hold metrics for a function/method
     */
    public static class FunctionMetric {
        // Direct field access for compatibility
        public final String name;       // Full method name (package.class.method)
        public final String filePath;   // File path
        public final int ncss;          // Non-commenting source statements
        public final int ccn;           // Cyclomatic complexity

        // Additional fields with getters
        @Getter private final String packageName;    // Package name
        @Getter private final String className;      // Class name
        @Getter private final String methodName;     // Method name

        public FunctionMetric(String name, String filePath, int ncss, int ccn,
                              String packageName, String className, String methodName) {
            this.name = name;
            this.filePath = filePath;
            this.ncss = ncss;
            this.ccn = ccn;
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public String toString() {
            return "FunctionMetric{" +
                    "name=" + name +
                    ", ccn=" + ccn +
                    ", ncss=" + ncss +
                    '}';
        }
    }
}