package com.example.demo.utils;

import com.example.demo.DbService.Impl.ProjectStorageService;
import com.example.demo.model.EvaluationContext;
import com.example.demo.model.IssueItem;

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
    private final ProjectStorageService projectStorageService;


    public Map<String, Future<Map<String, List<IssueItem>>>> submitFilesForEvaluation(
            RepositoryTree repoTree, EvaluationContext context, Path repoRoot, Long submissionId) {

        Map<String, Future<Map<String, List<IssueItem>>>> futureResults = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);

        try {
            Map<String, FileNode> allFiles = repoTree.collectSourceFiles(repoRoot);

            for (Map.Entry<String, FileNode> e : allFiles.entrySet()) {
                String repoRelPath = toRepoRelKey(e.getKey());
                FileNode node = e.getValue();
                // Submit the evaluation task returning Map<String, List<IssueItem>> per file
                futureResults.put(repoRelPath, submitFileEvaluation(repoRelPath, node, context, executor, repoRoot,submissionId));

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

        logger.info("Submitted evaluation tasks: {}", futureResults.keySet());
        return futureResults;
    }


    private Future<Map<String, List<IssueItem>>> submitFileEvaluation(
            String repoRelPath, FileNode fileNode, EvaluationContext context, ExecutorService executor, Path repoRoot,Long submissionId) {
        return executor.submit(() -> evaluateFile(repoRelPath, fileNode, context, repoRoot,submissionId));
    }


    private Map<String, List<IssueItem>> evaluateFile(String repoRelPath, FileNode fileNode, EvaluationContext context, Path repoRoot, Long submissionId) {

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
- Each key should map to a list of issue objects with fields: title, filePath, lineStart, lineEnd, severity, codeSnippet.
- The field 'codeSnippet' must contain the exact code from the file, between lineStart and lineEnd (inclusive).
- Only return a single JSON object first, with no additional explanation.
- After the JSON object, provide a brief summary describing the **purpose and functionality of the file** — what this file is essentially doing or responsible for.
- Use severity levels: ERROR, WARNING, INFO.
""";


            long estTokens = estimateTokens(prompt.length() + systemPrompt.length());
            String response = groqClient.getCompletion(systemPrompt, prompt, 1024, 0.2);
            logger.info("LLM response length for {} = {}", repoRelPath, response == null ? 0 : response.length());



            LlmResponseParser.ParsedResponse parsed = LlmResponseParser.parseLlmResponse(response);
            Map<String, Object> evaluation = JsonParser.parseEvaluation(parsed.jsonPart);

            if (!parsed.summaryPart.isEmpty()) {
                boolean saved = projectStorageService.addSummaryToProjectFiles(submissionId, repoRelPath, parsed.summaryPart);

                if (!saved) {
                    // Handle save failure, logging, etc.
                    throw new RuntimeException("Failed to add summary to project files for submission " + submissionId);
                }
            }
            LLMLogger.saveParsedEvaluationToFile(evaluation, repoRelPath);
            return flattenIssuesWithCategories(evaluation, repoRelPath);

        }catch (Exception e) {
            log.error("Error evaluating file: {}", fileNode.getPath(), e);


            List<IssueItem> errorList = new ArrayList<>();
            errorList.add(new IssueItem(
                    "Failed to evaluate file: " + e.getMessage(),
                    repoRelPath,
                    null,
                    null,
                    null,
                    IssueItem.IssueSeverity.ERROR
            ));

            Map<String, List<IssueItem>> errorMap = new HashMap<>();
            errorMap.put("errors", errorList);  // Put errors under the "errors" category

            // You can add empty lists for other categories if needed:
            errorMap.put("improvements", Collections.emptyList());
            errorMap.put("thingsDoneRight", Collections.emptyList());

            return errorMap;
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
    private Map<String, List<IssueItem>> flattenIssuesWithCategories(Map<String, Object> evaluation, String filePath) {
        Map<String, List<IssueItem>> categorizedIssues = new LinkedHashMap<>();
        if (evaluation == null) return categorizedIssues;

        for (String category : List.of("errors", "improvements", "thingsDoneRight")) {
            List<IssueItem> issuesInCategory = new ArrayList<>();

            Object val = evaluation.get(category);

            if (val instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item instanceof Map<?, ?> map) {
                        issuesInCategory.add(convertMapToIssueItem((Map<String, Object>) map, filePath));
                    }
                }
            } else if (val instanceof Map<?, ?> nestedMap) {
                for (Object nestedVal : nestedMap.values()) {
                    if (nestedVal instanceof Collection<?> innerList) {
                        for (Object obj : innerList) {
                            if (obj instanceof Map<?, ?> innerMap) {
                                issuesInCategory.add(convertMapToIssueItem((Map<String, Object>) innerMap, filePath));
                            }
                        }
                    }
                }
            }

            categorizedIssues.put(category, issuesInCategory);
        }

        return categorizedIssues;
    }


    /**
     * Converts a JSON-parsed map of an issue into an IssueItem object.
     * Applies fallback file path and safely parses types.
     */
    private IssueItem convertMapToIssueItem(Map<String, Object> map, String fallbackFilePath) {
        String title = (String) map.get("title");
        String filePath = (String) map.getOrDefault("filePath", fallbackFilePath);

        // Parse line numbers safely
        Integer lineStart = (map.get("lineStart") instanceof Number) ? ((Number) map.get("lineStart")).intValue() : null;
        Integer lineEnd = (map.get("lineEnd") instanceof Number) ? ((Number) map.get("lineEnd")).intValue() : null;

        // Parse codeSnippet if present
        String codeSnippet = (String) map.get("codeSnippet");

        // Parse severity safely (default to MEDIUM if missing/invalid)
        String severityRaw = (String) map.get("severity");
        IssueItem.IssueSeverity severity = IssueItem.IssueSeverity.MEDIUM;
        if (severityRaw != null) {
            try {
                severity = IssueItem.IssueSeverity.valueOf(severityRaw.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Leave as MEDIUM
            }
        }

        // Build and return the IssueItem
        IssueItem issue = new IssueItem();
        issue.setTitle(title);
        issue.setFilePath(filePath);
        issue.setLineStart(lineStart);
        issue.setLineEnd(lineEnd);
        issue.setCodeSnippet(codeSnippet);
        issue.setSeverity(severity);
        return issue;
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