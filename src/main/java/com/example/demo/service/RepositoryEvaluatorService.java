package com.example.demo.service;

import com.example.demo.kafka.FeedbackProducer;
import com.example.demo.model.*;
import com.example.demo.utils.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.Locale;

/**
 * Service for evaluating repository code quality
 */
@Slf4j
@Service
public class RepositoryEvaluatorService {

    private final ProducerFactory producerFactory;

    @Value("${groq.api.key}")
    private String groqApiKey;

    // Lower this in properties to reduce burst parallelism when using on_demand tier
    @Value("${evaluation.concurrency:2}")
    private int concurrencyLevel;

    @Value("${evaluation.timeout.seconds:600}")
    private int evaluationTimeoutSeconds;

    // App-level TPM limiter (tokens per minute) to avoid 429s
    @Value("${groq.tpm.limit:6000}")
    private long groqTpmLimit;

    // Content caps to keep prompts small and within context/TPM budgets
    private static final int SUMMARY_MAX_CHARS = 6000;   // ~2k tokens
    private static final int EVAL_MAX_CHARS    = 12000;  // ~4k tokens
    private static final long MAX_FILE_BYTES   = 200_000; // hard cap for files sent to LLM (200 KB)

    private DependencyAnalyzer dependencyAnalyzer;
    private GroqClient groqClient;
    private final GitUtils gitService;
    private final FeedbackProducer feedbackProducer;
    private static final Logger logger = LoggerFactory.getLogger(RepositoryEvaluatorService.class);

    // Simple in-process token-bucket limiter for TPM
    private final Object rlLock = new Object();
    private double rlTokens;           // current tokens in bucket
    private long rlLastRefillMs;       // last refill timestamp

    @Autowired
    public RepositoryEvaluatorService(GitUtils gitService, FeedbackProducer feedbackProducer, ProducerFactory producerFactory) {
        this.gitService = gitService;
        this.feedbackProducer = feedbackProducer;
        this.producerFactory = producerFactory;
    }

    @PostConstruct
    void init() {
        this.dependencyAnalyzer = new DependencyCheckAnalyzer();
        this.groqClient = new GroqClient(groqApiKey);
        // init rate limiter
        synchronized (rlLock) {
            this.rlTokens = groqTpmLimit;
            this.rlLastRefillMs = System.currentTimeMillis();
        }
    }

    /**
     * Evaluate a repository from its GitHub URL
     *
     * @param repoUrl URL of the GitHub repository
     * @param submissionId ID of the submission being evaluated
     */
    public void evaluateRepositoryFromUrl(String repoUrl, Long submissionId) {
        Path repoPath = null;

        try {
            // Log evaluation request with timestamp and username
            String timestamp = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("Repository evaluation requested at {} UTC by user hardik118", timestamp);

            // Clone the repository
            log.info("Cloning repository from URL: {}", repoUrl);
            repoPath = gitService.cloneRepository(repoUrl);
            log.info("Repository cloned successfully to {}", repoPath);

            // Sanity checks — these will reveal if cloneRepository returned a URL-like path
            log.info("Clone returned path: {}", repoPath);
            if (repoPath == null) {
                throw new IllegalStateException("gitService.cloneRepository returned null");
            }
            String asString = repoPath.toString();
            if (asString.startsWith("http://") || asString.startsWith("https://")
                    || asString.matches("^[^@\\s]+@[^:\\s]+:.*$")) {
                throw new IllegalStateException("Clone did not return a local directory. Got: " + asString);
            }
            if (!Files.exists(repoPath) || !Files.isDirectory(repoPath)) {
                throw new IllegalStateException("Cloned path is not a directory on disk: " + repoPath.toAbsolutePath());
            }

            // Evaluate the cloned repository
            EvaluationResult evaluationResult = evaluateRepository(repoPath, submissionId);
            logger.info("Evaluation result: {}", evaluationResult);
            feedbackProducer.produceFeedback(evaluationResult);

        } catch (GitUtils.GitServiceException e) {
            log.error("Error cloning repository: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
        } finally {
            // Clean up cloned repository
            if (repoPath != null) {
                try {
                    gitService.cleanupRepository(repoPath);
                } catch (Exception e) {
                    log.warn("Failed to clean up repository: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Evaluate the code quality of a repository
     *
     * @param repoPath     Path to the repository
     * @param submissionId Unique identifier for this evaluation
     */
    public EvaluationResult evaluateRepository(Path repoPath, Long submissionId) {
        try {
            log.info("Starting evaluation of repository at {}", repoPath);

            // Build tree representation of the repository
            RepositoryTree repoTree = RepositoryTreeBuilder.buildTree(repoPath);
            log.info("Built repository tree with {} files and {} directories",
                    repoTree.getFileCount(), repoTree.getDirectoryCount());

            // Analyze dependencies in the repository
            ToolResults dependencies = dependencyAnalyzer.analyze(repoPath);

            // Build evaluation context
            EvaluationContext context = buildContext(repoTree, dependencies);

            // Submit files for evaluation
            Map<String, Future<List<IssueItem>>> futureResults = submitFilesForEvaluation(repoTree, context);

            // Aggregate results
            Map<String, List<IssueItem>> errors = new HashMap<>();
            Map<String, List<IssueItem>> improvements = new HashMap<>();
            Map<String, List<IssueItem>> thingsDoneRight = new HashMap<>();

            // Process the evaluation results
            processEvaluationResults(futureResults, errors, improvements, thingsDoneRight);

            // Generate overall assessment
            List<String> generalComments = generateOverallAssessment(context);

            // Create and return the evaluation result
            return new EvaluationResult(submissionId, errors, improvements, thingsDoneRight, generalComments);

        } catch (Exception e) {
            log.error("Error evaluating repository", e);
            throw new RuntimeException("Failed to evaluate repository", e);
        }
    }

    private EvaluationContext buildContext(RepositoryTree repoTree, ToolResults dependencies) {
        EvaluationContext context = new EvaluationContext();

        // Process each file in the repository tree
        processFileNode(repoTree.getRoot(), repoTree, context);

        // Add dependencies to the context
        for (DependencyInfo dep : dependencies.getDependencies()) {
            context.addDependency(dep.getSourceFile(), dep.getTargetFile());
        }

        return context;
    }

    private void processFileNode(FileNode node, RepositoryTree tree, EvaluationContext context) {
        if (node.isFile() && shouldProcessFile(node) && isLLMProcessable(node.getPath())) {
            try {
                String content = Files.readString(Paths.get(node.getPath()));
                String summary = generateFileSummary(node.getPath(), content);

                node.setContent(content);
                node.setSummary(summary);
                context.addFileSummary(node.getPath(), summary);

            } catch (IOException e) {
                log.warn("Failed to read file: {}", node.getPath(), e);
            }
        }

        // Process child nodes
        for (FileNode child : node.getChildren()) {
            processFileNode(child, tree, context);
        }
    }

    /**
     * Determine if a file should be processed for evaluation
     */
    private boolean shouldProcessFile(FileNode node) {
        // Keep your own heuristics (source/config/docs)
        return node.isSourceCode() || node.isConfigFile() || node.isDocumentation();
    }

    /**
     * Additional gate to avoid sending noisy/huge files to the LLM
     */
    private boolean isLLMProcessable(String filePath) {
        String lower = filePath.toLowerCase(Locale.ROOT);

        // Exclude lock files and common noisy artifacts
        if (lower.endsWith("package-lock.json")
                || lower.endsWith("yarn.lock")
                || lower.endsWith("pnpm-lock.yaml")
                || lower.endsWith(".lock")
                || lower.endsWith(".min.js")
                || lower.endsWith(".map")
                || lower.endsWith(".bundle.js")) {
            return false;
        }

        // Exclude large files
        try {
            long size = Files.size(Paths.get(filePath));
            if (size > MAX_FILE_BYTES) return false;
        } catch (IOException ignored) {}

        return true;
    }

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n... [truncated]";
    }

    /**
     * Generate a summary of what the file does
     */
    private String generateFileSummary(String filePath, String content) {
        try {
            String trimmed = truncate(content, SUMMARY_MAX_CHARS);

            // Gate by TPM limiter before calling LLM
            long estTokens = estimateTokens(200 + filePath.length() + trimmed.length());
            acquireTokens(estTokens);

            String prompt = "Summarize what this " + FileUtil.getFileExtension(filePath) +
                    " file does in 1-2 sentences. Focus on purpose and key functionality. File: " +
                    FileUtil.getFileName(filePath) + "\n\n" + trimmed;

            String summary = groqClient.getCompletion(prompt);

            // Clean up the summary
            summary = summary.replaceAll("^[\"']|[\"']$", "").trim();
            if (summary.length() > 500) {
                summary = summary.substring(0, 497) + "...";
            }

            return summary;

        } catch (Exception e) {
            log.warn("Failed to generate summary for {}", filePath, e);
            return "No summary available.";
        }
    }

    /**
     * Submit files for evaluation and return Future results
     */
    private Map<String, Future<List<IssueItem>>> submitFilesForEvaluation(
            RepositoryTree repoTree, EvaluationContext context) {

        Map<String, Future<List<IssueItem>>> futureResults = new HashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);

        try {
            // Queue important files first, then others
            Map<String, FileNode> allFiles = collectSourceFiles(repoTree);
            Set<String> processedFiles = new HashSet<>();

            // Process key entry points first
            for (String filePath : findEntryPoints(allFiles, context)) {
                if (processedFiles.contains(filePath)) continue;

                FileNode fileNode = allFiles.get(filePath);
                if (fileNode != null && isLLMProcessable(fileNode.getPath())) {
                    futureResults.put(filePath, submitFileEvaluation(fileNode, context, executor));
                    processedFiles.add(filePath);
                }
            }

            // Then process the rest of the files
            for (FileNode fileNode : allFiles.values()) {
                String filePath = fileNode.getPath();
                if (processedFiles.contains(filePath)) continue;
                if (!isLLMProcessable(filePath)) continue;

                futureResults.put(filePath, submitFileEvaluation(fileNode, context, executor));
                processedFiles.add(filePath);
            }

        } finally {
            executor.shutdown();
            try {
                // Wait for all tasks to complete or timeout
                if (!executor.awaitTermination(evaluationTimeoutSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        return futureResults;
    }

    /**
     * Find entry point files in the repository
     */
    private List<String> findEntryPoints(Map<String, FileNode> files, EvaluationContext context) {
        List<String> entryPoints = new ArrayList<>();

        // Find files with many dependents (used by many other files)
        Map<String, Integer> dependentCounts = new HashMap<>();

        for (String filePath : files.keySet()) {
            Set<String> dependents = context.getDependents(filePath);
            dependentCounts.put(filePath, dependents.size());
        }

        // Sort by number of dependents, descending
        List<Map.Entry<String, Integer>> sortedFiles = new ArrayList<>(dependentCounts.entrySet());
        sortedFiles.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        // Take the top files as entry points
        for (int i = 0; i < Math.min(5, sortedFiles.size()); i++) {
            entryPoints.add(sortedFiles.get(i).getKey());
        }

        // Also look for common entry point files
        for (String filePath : files.keySet()) {
            String fileName = FileUtil.getFileName(filePath).toLowerCase(Locale.ROOT);
            if (fileName.contains("main") ||
                    fileName.contains("app") ||
                    fileName.contains("index") ||
                    fileName.equals("application.java")) {
                entryPoints.add(filePath);
            }
        }

        return entryPoints;
    }

    /**
     * Collect all source files in the repository
     */
    private Map<String, FileNode> collectSourceFiles(RepositoryTree repoTree) {
        Map<String, FileNode> files = new HashMap<>();
        collectSourceFilesRecursive(repoTree.getRoot(), files);
        return files;
    }

    /**
     * Recursively collect source files
     */
    private void collectSourceFilesRecursive(FileNode node, Map<String, FileNode> files) {
        if (node.isFile() && shouldProcessFile(node) && isLLMProcessable(node.getPath())) {
            files.put(node.getPath(), node);
        } else if (!node.isFile()) {
            for (FileNode child : node.getChildren()) {
                collectSourceFilesRecursive(child, files);
            }
        }
    }

    /**
     * Submit a single file for evaluation
     */
    private Future<List<IssueItem>> submitFileEvaluation(
            FileNode fileNode, EvaluationContext context, ExecutorService executor) {
        return executor.submit(() -> evaluateFile(fileNode, context));
    }

    /**
     * Evaluate a single file
     */
    private List<IssueItem> evaluateFile(FileNode fileNode, EvaluationContext context) {
        try {
            String filePath = fileNode.getPath();
            log.info("Evaluating file: {}", filePath);

            // Get file content
            String content = fileNode.getContent();
            if (content == null) {
                content = Files.readString(Paths.get(filePath));
                fileNode.setContent(content);
            }
            String trimmed = truncate(content, EVAL_MAX_CHARS);

            // Get file context
            Map<String, Object> fileContext = context.getFileContext(filePath);

            // Build prompt for evaluation
            String prompt = buildEvaluationPrompt(fileNode, trimmed, fileContext);

            // Gate by TPM limiter before calling LLM (include prompt size; add margin for response)
            long estTokens = estimateTokens(600 + prompt.length());
            acquireTokens(estTokens);

            // Get evaluation from LLM
            String response = groqClient.getCompletion(prompt);

            // Parse the evaluation
            Map<String, Object> evaluation = JsonParser.parseEvaluation(response);

            // Collect all issues
            List<IssueItem> allIssues = new ArrayList<>();

            // Process errors
            if (evaluation.containsKey("errors") && evaluation.get("errors") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, List<IssueItem>> errors = (Map<String, List<IssueItem>>) evaluation.get("errors");
                for (List<IssueItem> issues : errors.values()) {
                    allIssues.addAll(issues);
                }
            }

            // Process improvements
            if (evaluation.containsKey("improvements") && evaluation.get("improvements") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, List<IssueItem>> improvements = (Map<String, List<IssueItem>>) evaluation.get("improvements");
                for (List<IssueItem> issues : improvements.values()) {
                    allIssues.addAll(issues);
                }
            }

            // Process things done right
            if (evaluation.containsKey("thingsDoneRight") && evaluation.get("thingsDoneRight") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, List<IssueItem>> thingsDoneRight = (Map<String, List<IssueItem>>) evaluation.get("thingsDoneRight");
                for (List<IssueItem> issues : thingsDoneRight.values()) {
                    allIssues.addAll(issues);
                }
            }

            return allIssues;

        } catch (Exception e) {
            log.error("Error evaluating file: {}", fileNode.getPath(), e);
            List<IssueItem> errorList = new ArrayList<>();
            errorList.add(new IssueItem(
                    "Failed to evaluate file: " + e.getMessage(),
                    fileNode.getPath(),
                    null,
                    null,
                    IssueItem.IssueSeverity.ERROR
            ));
            return errorList;
        }
    }

    /**
     * Build prompt for file evaluation
     */
    private String buildEvaluationPrompt(
            FileNode fileNode, String content, Map<String, Object> fileContext) {

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a code quality expert reviewing this ")
                .append(FileUtil.getFileExtension(fileNode.getPath()))
                .append(" file: ")
                .append(fileNode.getPath())
                .append("\n\n");

        // Add content
        prompt.append("FILE CONTENT:\n```\n")
                .append(content)
                .append("\n```\n\n");

        // Add context information
        prompt.append("FILE CONTEXT:\n");

        // Dependencies
        @SuppressWarnings("unchecked")
        Set<String> dependencies = (Set<String>) fileContext.get("dependencies");
        if (dependencies != null && !dependencies.isEmpty()) {
            prompt.append("This file depends on:\n");
            for (String dep : dependencies) {
                String summary = fileNode.getParent() != null ?
                        fileNode.getParent().getSummary() : "Unknown";
                prompt.append("- ").append(dep)
                        .append(" (").append(summary).append(")\n");
            }
            prompt.append("\n");
        }

        // Dependents
        @SuppressWarnings("unchecked")
        Set<String> dependents = (Set<String>) fileContext.get("dependents");
        if (dependents != null && !dependents.isEmpty()) {
            prompt.append("This file is used by:\n");
            for (String dep : dependents) {
                String summary = fileNode.getParent() != null ?
                        fileNode.getParent().getSummary() : "Unknown";
                prompt.append("- ").append(dep)
                        .append(" (").append(summary).append(")\n");
            }
            prompt.append("\n");
        }

        // Instructions
        prompt.append("INSTRUCTIONS:\n")
                .append("Analyze this code and provide the following in JSON format:\n")
                .append("1. Errors: Actual bugs or critical issues that need to be fixed\n")
                .append("2. Improvements: Suggestions to improve code quality, readability, or performance\n")
                .append("3. Things Done Right: Good practices or patterns found in the code\n\n")
                .append("For each issue, include:\n")
                .append("- Description of the issue\n")
                .append("- File path\n")
                .append("- Line number (if applicable)\n")
                .append("- Code context (if applicable)\n")
                .append("- Severity (LOW, MEDIUM, HIGH, CRITICAL, ERROR)\n\n")
                .append("RESPONSE FORMAT: JSON with structure {\"errors\": {}, \"improvements\": {}, \"thingsDoneRight\": {}}");

        return prompt.toString();
    }

    /**
     * Process evaluation results from futures
     */
    private void processEvaluationResults(
            Map<String, Future<List<IssueItem>>> futureResults,
            Map<String, List<IssueItem>> errors,
            Map<String, List<IssueItem>> improvements,
            Map<String, List<IssueItem>> thingsDoneRight) {

        for (Map.Entry<String, Future<List<IssueItem>>> entry : futureResults.entrySet()) {
            String filePath = entry.getKey();
            Future<List<IssueItem>> future = entry.getValue();

            try {
                List<IssueItem> issues = future.get(30, TimeUnit.SECONDS);

                // Categorize issues
                for (IssueItem issue : issues) {
                    if (issue.getSeverity() == IssueItem.IssueSeverity.ERROR) {
                        addToMap(errors, issue.getFilePath(), issue);
                    } else if (issue.getDescription().toLowerCase().contains("good") ||
                            issue.getDescription().toLowerCase().contains("right") ||
                            issue.getDescription().toLowerCase().contains("well")) {
                        addToMap(thingsDoneRight, issue.getFilePath(), issue);
                    } else {
                        addToMap(improvements, issue.getFilePath(), issue);
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to get evaluation result for {}", filePath, e);
                IssueItem errorItem = new IssueItem(
                        "Evaluation failed: " + e.getMessage(),
                        filePath,
                        null,
                        null,
                        IssueItem.IssueSeverity.ERROR
                );
                addToMap(errors, filePath, errorItem);
            }
        }
    }

    /**
     * Generate overall assessment of the repository
     */
    private List<String> generateOverallAssessment(EvaluationContext context) {
        try {
            // Build prompt for overall assessment (truncate list to reduce size)
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a code quality expert reviewing a repository. ")
                    .append("Based on the following information, provide an overall assessment ")
                    .append("of the codebase with key strengths and areas for improvement.\n\n");

            // Add summary of files
            prompt.append("Files in the repository (").append(context.getFileCount()).append(" total):\n");

            int count = 0;
            for (Map.Entry<String, String> entry : context.getFileSummaries().entrySet()) {
                if (count++ > 30) {
                    prompt.append("... and ").append(context.getFileCount() - 30).append(" more files\n");
                    break;
                }
                prompt.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }

            // Gate by TPM limiter
            long estTokens = estimateTokens(500 + prompt.length());
            acquireTokens(estTokens);

            // Get assessment
            String response = groqClient.getCompletion(prompt.toString());

            // Parse the response into bullet points
            String[] lines = response.split("\n");
            List<String> comments = new ArrayList<>();

            StringBuilder currentComment = new StringBuilder();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    if (currentComment.length() > 0) {
                        comments.add(currentComment.toString().trim());
                        currentComment = new StringBuilder();
                    }
                } else if (line.startsWith("-") || line.startsWith("•")) {
                    if (currentComment.length() > 0) {
                        comments.add(currentComment.toString().trim());
                    }
                    currentComment = new StringBuilder(line.substring(1).trim());
                } else {
                    if (currentComment.length() > 0) {
                        currentComment.append(" ");
                    }
                    currentComment.append(line);
                }
            }

            if (currentComment.length() > 0) {
                comments.add(currentComment.toString().trim());
            }

            return comments;

        } catch (Exception e) {
            log.error("Failed to generate overall assessment", e);
            List<String> fallback = new ArrayList<>();
            fallback.add("Failed to generate overall assessment: " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Add an issue to a map of file paths to issues
     */
    private void addToMap(Map<String, List<IssueItem>> map, String filePath, IssueItem issue) {
        map.computeIfAbsent(filePath, k -> new ArrayList<>()).add(issue);
    }

    // -------- Rate limiter helpers (tokens per minute) --------

    // crude char->token estimate to keep us safe
    private long estimateTokens(int chars) {
        return Math.max(1, chars / 4); // ~4 chars per token
    }

    private void acquireTokens(long needed) throws InterruptedException {
        if (needed > groqTpmLimit) {
            needed = groqTpmLimit; // cap a single request to bucket capacity
        }
        synchronized (rlLock) {
            refillTokens();
            while (rlTokens < needed) {
                long sleepMs = computeSleepMillis(needed);
                rlLock.wait(sleepMs);
                refillTokens();
            }
            rlTokens -= needed;
        }
    }

    private void refillTokens() {
        long now = System.currentTimeMillis();
        long elapsedMs = now - rlLastRefillMs;
        if (elapsedMs <= 0) return;
        double perMs = (double) groqTpmLimit / 60000.0; // tokens per ms
        rlTokens = Math.min(groqTpmLimit, rlTokens + perMs * elapsedMs);
        rlLastRefillMs = now;
    }

    private long computeSleepMillis(long needed) {
        double deficit = needed - rlTokens;
        if (deficit <= 0) return 0L;
        double perMs = (double) groqTpmLimit / 60000.0;
        long ms = (long) Math.ceil(deficit / perMs);
        return Math.max(ms, 50L);
    }
}