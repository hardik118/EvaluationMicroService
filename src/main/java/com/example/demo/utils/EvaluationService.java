package com.example.demo.utils;

import com.example.demo.model.EvaluationContext;
import com.example.demo.model.IssueItem;
import com.example.demo.utils.FileNode;
import com.example.demo.utils.GroqClient;
import com.example.demo.utils.LlmPromptBuilder;
import com.example.demo.utils.RepositoryTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final GroqClient groqClient;
    private static final Logger logger = LoggerFactory.getLogger(EvaluationService.class);

    private final int concurrencyLevel = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final int evaluationTimeoutSeconds = 600;
    private final int EVAL_MAX_CHARS = 32_000;
    private static final ObjectMapper mapper = new ObjectMapper();


    public Map<String, Future<List<IssueItem>>> submitFilesForEvaluation(
            RepositoryTree repoTree, EvaluationContext context, Path repoRoot) {

        Map<String, Future<List<IssueItem>>> futureResults = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);

        try {
            // IMPORTANT: repo-relative keys so they match EvaluationContext
            Map<String, FileNode> allFiles = repoTree.collectSourceFiles(repoRoot);

            for (Map.Entry<String, FileNode> e : allFiles.entrySet()) {
                String repoRelPath = toRepoRelKey(e.getKey()); // no stripping of leading slash; keep as repo-relative
                FileNode node = e.getValue();
                futureResults.put(repoRelPath, submitFileEvaluation(repoRelPath, node, context, executor, repoRoot));
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(evaluationTimeoutSeconds, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info(futureResults.toString());
        return futureResults;
    }

    private Future<List<IssueItem>> submitFileEvaluation(
            String repoRelPath, FileNode fileNode, EvaluationContext context, ExecutorService executor, Path repoRoot) {
        return executor.submit(() -> evaluateFile(repoRelPath, fileNode, context, repoRoot));
    }

    private List<IssueItem> evaluateFile(String repoRelPath, FileNode fileNode, EvaluationContext context, Path repoRoot) {
        try {
            String nodePath = fileNode.getPath(); // usually absolute from tree builder
            log.info("Evaluating file: {}", nodePath);

            String content = fileNode.getContent();
            logger.info("Initial content for {} is {}", nodePath, (content == null ? "null" : "len=" + content.length()));
            if (content == null) {
                content = safeRead(repoRoot, repoRelPath, nodePath);
                fileNode.setContent(content);
                logger.info("Read content for {} via disk -> len={}", nodePath, (content == null ? 0 : content.length()));
            }
            if (content == null) content = "";
            String trimmed = truncate(content, EVAL_MAX_CHARS);

            // CRITICAL: use repo-relative key for context
            Map<String, Object> fileContext = context.getFileContext(repoRelPath);
            String language = Objects.toString(fileContext.get("language"), null);

            String prompt = LlmPromptBuilder.buildEvaluationPrompt(
                    repoRelPath, language, trimmed, fileContext,
                    /* maxDeps */ 20, /* maxDependents */ 20, /* maxExports */ 30
            );

            String systemPrompt = """
            You are a code reviewer assistant. Please follow these instructions strictly:
            - Provide feedback on errors, improvements, and things done right.
            - Use a structured JSON format with keys: errors, improvements, thingsDoneRight.
            - Each key should map to a list of issue objects with fields: message, filePath, lineNumber, severity.
            - Do not include explanations outside the JSON structure.
            - Use severity levels: ERROR, WARNING, INFO.
            """;

            long estTokens = estimateTokens(prompt.length() + systemPrompt.length());
            String response = groqClient.getCompletion(systemPrompt, prompt, 1024, 0.2);
            logger.info("LLM response length for {} = {}", repoRelPath, response == null ? 0 : response.length());
            if (response != null) {
                try {
                    // Make a safe file name from repoRelPath
                    String safeFileName = repoRelPath.replace("/", "_").replace("\\", "_");

                    // Create the output file path inside the "llm_responses" directory
                    Path logDir = Paths.get("llm_responses");
                    Path logFile = logDir.resolve(safeFileName + ".txt");

                    // Create the directory if it doesn't exist
                    Files.createDirectories(logDir);

                    // Write the full response to the file (overwrite if exists)
                    Files.writeString(
                            logFile,
                            response,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );

                    // Optional: Also print a short preview in the logs
                    logger.info("LLM response saved for {} → {}", repoRelPath, logFile.toAbsolutePath());
                } catch (IOException e) {
                    logger.error("Failed to log LLM response for {}: {}", repoRelPath, e.getMessage());
                }
            } else {
                logger.warn("No response received for {}", repoRelPath);
            }

            String cleanedResponse = stripJsonCodeFence(response);
            Map<String, Object> evaluation = JsonParser.parseEvaluation(cleanedResponse);
            LLMLogger.saveParsedEvaluationToFile(evaluation, repoRelPath);
            return flattenIssues(evaluation, repoRelPath);

        } catch (Exception e) {
            log.error("Error evaluating file: {}", fileNode.getPath(), e);
            List<IssueItem> errorList = new ArrayList<>();
            errorList.add(new IssueItem(
                    "Failed to evaluate file: " + e.getMessage(),
                    repoRelPath,
                    null,
                    null,
                    IssueItem.IssueSeverity.ERROR
            ));
            return errorList;
        }
    }

    private String safeRead(Path repoRoot, String repoRelPath, String nodePath) throws Exception {
        // 1) Prefer repoRoot + repo-relative key (matches disk layout)
        if (repoRoot != null && repoRelPath != null && !repoRelPath.isBlank()) {
            Path rel = repoRoot.resolve(repoRelPath).normalize();
            if (Files.exists(rel)) {
                logger.debug("Reading via repoRoot+rel: {}", rel);
                return Files.readString(rel);
            } else {
                logger.warn("Repo-relative path does not exist: {}", rel);
            }
        }
        // 2) Fallback to node's absolute path (if absolute)
        if (nodePath != null) {
            Path p = Paths.get(nodePath);
            if (p.isAbsolute() && Files.exists(p)) {
                logger.debug("Reading via absolute node path: {}", p);
                return Files.readString(p.normalize());
            } else {
                logger.warn("Node path not usable for disk read: {}", nodePath);
            }
        }
        // 3) Final fallback: try repoRelPath as-is (process CWD)
        if (repoRelPath != null) {
            Path p = Paths.get(repoRelPath).normalize();
            if (Files.exists(p)) {
                logger.debug("Reading via raw repoRelPath: {}", p);
                return Files.readString(p);
            }
        }
        throw new IllegalStateException("File not found for reading: repoRel=" + repoRelPath + ", nodePath=" + nodePath);
    }

    private String toRepoRelKey(String key) {
        // key is already repo-relative from collectSourceFiles(repoRoot); just normalize slashes
        return key == null ? "" : key.replace('\\', '/');
    }

    private String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    private long estimateTokens(int chars) { return Math.max(1, chars / 4); }

    @SuppressWarnings("unchecked")
    private List<IssueItem> flattenIssues(Map<String, Object> evaluation, String filePath) {
        List<IssueItem> allIssues = new ArrayList<>();
        if (evaluation == null) return allIssues;

        if (evaluation.get("errors") instanceof Collection<?> col) {
            for (Object v : col) if (v instanceof IssueItem ii) allIssues.add(ii);
        } else if (evaluation.get("errors") instanceof Map<?,?> map) {
            for (Object v : map.values()) if (v instanceof Collection<?> list) {
                for (Object o : list) if (o instanceof IssueItem ii) allIssues.add(ii);
            }
        }

        if (evaluation.get("improvements") instanceof Collection<?> col) {
            for (Object v : col) if (v instanceof IssueItem ii) allIssues.add(ii);
        } else if (evaluation.get("improvements") instanceof Map<?,?> map) {
            for (Object v : map.values()) if (v instanceof Collection<?> list) {
                for (Object o : list) if (o instanceof IssueItem ii) allIssues.add(ii);
            }
        }

        if (evaluation.get("thingsDoneRight") instanceof Collection<?> col) {
            for (Object v : col) if (v instanceof IssueItem ii) allIssues.add(ii);
        } else if (evaluation.get("thingsDoneRight") instanceof Map<?,?> map) {
            for (Object v : map.values()) if (v instanceof Collection<?> list) {
                for (Object o : list) if (o instanceof IssueItem ii) allIssues.add(ii);
            }
        }

        for (IssueItem ii : allIssues) {
            if (ii.getFilePath() == null || ii.getFilePath().isBlank()) {
                ii.setFilePath(filePath);
            }
        }
        return allIssues;
    }
    public static String stripJsonCodeFence(String input) {
        if (input == null) return "";

        // Check if it starts with a code fence like ```json
        if (input.startsWith("```json")) {
            // Remove the first line (```json)
            int firstNewline = input.indexOf('\n');
            if (firstNewline >= 0) {
                input = input.substring(firstNewline + 1);
            }
            // Remove trailing ``` if present
            if (input.endsWith("```")) {
                input = input.substring(0, input.length() - 3);
            }
        }
        return input.trim();
    }


    public void saveEvaluationResultsToFiles(
            Map<String, Future<List<IssueItem>>> futureResults,
            Path outputDir,
            int evaluationTimeoutSeconds) {

        Logger logger = LoggerFactory.getLogger(getClass());

        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
                logger.info("Created output directory: {}", outputDir.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create output directory: {}", outputDir.toAbsolutePath(), e);
            return;
        }

        for (Map.Entry<String, Future<List<IssueItem>>> entry : futureResults.entrySet()) {
            String filePath = entry.getKey();
            Future<List<IssueItem>> future = entry.getValue();

            try {
                List<IssueItem> issues = future.get(evaluationTimeoutSeconds, TimeUnit.SECONDS);

                StringBuilder content = new StringBuilder();
                content.append("Evaluation results for file: ").append(filePath).append("\n\n");

                if (issues.isEmpty()) {
                    content.append("No issues found.\n");
                } else {
                    for (IssueItem issue : issues) {
                        content.append("- Description : ").append(issue.getDescription()).append("\n");
                        content.append("  Severity    : ").append(issue.getSeverity()).append("\n");
                        content.append("  File Path  : ").append(issue.getFilePath()).append("\n");
                        content.append("  Line Number: ").append(issue.getLineNumber() != null ? issue.getLineNumber() : "N/A").append("\n");
                        content.append("  Code Context:\n");
                        if (issue.getCodeContext() != null && !issue.getCodeContext().isBlank()) {
                            content.append(issue.getCodeContext()).append("\n");
                        } else {
                            content.append("  [No code context]\n");
                        }
                        content.append("\n");
                    }
                }

                // Replace slashes/backslashes to avoid nested dirs in file name
                String safeFileName = filePath.replaceAll("[/\\\\]", "_") + ".txt";
                Path outputFile = outputDir.resolve(safeFileName);

                Files.writeString(outputFile, content.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("Saved evaluation results for '{}' to file: {}", filePath, outputFile.toAbsolutePath());

            } catch (Exception e) {
                logger.error("Failed to get or save evaluation result for file '{}'", filePath, e);
            }
        }
    }



    // Placeholder parser; replace with your real one
    public static class JsonParser {
        public static Map<String, Object> parseEvaluation(String json) {


                try {
                    return mapper.readValue(json, Map.class);
                } catch (IOException e) {
                    System.err.println("Failed to parse JSON: " + e.getMessage());
                    return Map.of(); // return empty map on failure

            }
        }
    }
}