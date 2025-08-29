package com.example.demo.service;

import com.example.demo.DbModels.Project;
import com.example.demo.DbService.Impl.ProjectService;
import com.example.demo.DbService.Impl.ProjectStorageService;
import com.example.demo.kafka.FeedbackProducer;
import com.example.demo.model.EvaluationContext;
import com.example.demo.model.EvaluationResult;
import com.example.demo.model.IssueItem;
import com.example.demo.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service for evaluating repository code quality
 */
@Slf4j
@Service
public class RepositoryEvaluatorService {

    private final DependencyCheckAnalyzer dependencyCheckAnalyzer;
    private final GitUtils gitService;
    private final FeedbackProducer feedbackProducer;
    private final ProjectService projectService;
    private final GroqClient groqClient;  // Add this field



    // Delegate file-level evaluations to a dedicated service
    @Autowired
    private     EvaluationService evaluationService;

    @Autowired
    private   SummaryService summaryService;



    @Value("${evaluation.timeout.seconds:600}")
    private int evaluationTimeoutSeconds;

    @Value("${groq.tpm.limit:6000}")
    private long groqTpmLimit;


    private final Object rlLock = new Object();
    private double rlTokens;
    private long rlLastRefillMs;
    private final ProjectStorageService projectStorageService;

    public RepositoryEvaluatorService(
            GitUtils gitService,
            FeedbackProducer feedbackProducer,
            ProducerFactory<?, ?> producerFactory,
            DependencyCheckAnalyzer dependencyCheckAnalyzer,
            ProjectService projectService,
            GroqClient groqClient,ProjectStorageService  projectStorageService
    ) {
        this.gitService = gitService;
        this.feedbackProducer = feedbackProducer;
        this.dependencyCheckAnalyzer = dependencyCheckAnalyzer;
        this.projectService = projectService;
        this.groqClient = groqClient;
        this.projectStorageService = projectStorageService;
    }



    /**
     * Evaluate a repository from its GitHub URL
     */
    public void evaluateRepositoryFromUrl(String repoUrl, Long submissionId, String UserIntent) {
        Path repoPath = null;

        try {
            String timestamp = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            log.info("Repository evaluation requested at {} UTC by user hardik118", timestamp);

            log.info("Cloning repository from URL: {}", repoUrl);
            repoPath = gitService.cloneRepository(repoUrl);
            log.info("Repository cloned successfully to {}", repoPath);

            if (repoPath == null) {
                throw new IllegalStateException("gitService.cloneRepository returned null");
            }
            if (!Files.exists(repoPath) || !Files.isDirectory(repoPath)) {
                throw new IllegalStateException("Cloned path is not a directory on disk: " + repoPath.toAbsolutePath());
            }

            EvaluationResult evaluationResult = evaluateRepository(repoPath, submissionId,  UserIntent);
            log.info("Evaluation result ready for submissionId={}", submissionId);
            feedbackProducer.produceFeedback(evaluationResult);

        } catch (GitUtils.GitServiceException e) {
            log.error("Error cloning repository: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
        } finally {
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
     */
    public EvaluationResult evaluateRepository(Path repoPath, Long submissionId, String UserIntent) {
        try {

            log.info("Starting evaluation of repository at {}", repoPath);

            Optional<Project> newProject = projectService.findBySubmissionId(submissionId);
            if (newProject.isEmpty()) {
                throw new IllegalStateException("Could not find project with submissionId: " + submissionId);
            }
            Project project = newProject.get();

            // Ensure root exists before walk (safe if already present)
            projectStorageService.getOrCreateRootFolder(project);

            // Persist to DB during the walk and still get the in-memory tree for later steps
            DbNodeListener dbListener = new DbNodeListener(project, projectStorageService);
            RepositoryTree repoTree = RepositoryTreeBuilder.buildTree(repoPath, dbListener);

            // Analyze dependencies and persist taking/calling per file
            dependencyCheckAnalyzer.analyze(project, repoPath);

            // Build evaluation context from DB
            EvaluationContext context = EvaluationContext.fromRepoPath(repoPath, project, projectStorageService);
            log.info("\n{}", context.toPrettyString(25));

            // Submit files for evaluation via EvaluationService (returns futures)
            Map<String, Future<Map<String, List<IssueItem>>>> futureResults =
                    evaluationService.submitFilesForEvaluation(repoTree, context, repoPath,submissionId);


            // Aggregate results
            Map<String, List<IssueItem>> errors = new HashMap<>();
            Map<String, List<IssueItem>> improvements = new HashMap<>();
            Map<String, List<IssueItem>> thingsDoneRight = new HashMap<>();

            // Process the evaluation results (waits on futures and logs to process.log)
            processEvaluationResults(futureResults, errors, improvements, thingsDoneRight);

            // Generate overall assessment (optional higher-level summary)
            List<String> generalComments = generateOverallAssessment(submissionId, UserIntent , repoTree);

            // Final structured result
            return new EvaluationResult(submissionId, errors, improvements, thingsDoneRight, generalComments);

        } catch (Exception e) {
            log.error("Error evaluating repository", e);

            throw new RuntimeException("Failed to evaluate repository", e);
        }
    }

    /**
     * Wait for futures, bucket issues, and log per-file results to process.log
     */
    private void processEvaluationResults(
            Map<String, Future<Map<String, List<IssueItem>>>> futureResults,
            Map<String, List<IssueItem>> errors,
            Map<String, List<IssueItem>> improvements,
            Map<String, List<IssueItem>> thingsDoneRight) {

        for (Map.Entry<String, Future<Map<String, List<IssueItem>>>> entry : futureResults.entrySet()) {
            String filePath = entry.getKey();
            Future<Map<String, List<IssueItem>>> future = entry.getValue();

            try {
                Map<String, List<IssueItem>> groupedIssues = future.get(evaluationTimeoutSeconds, TimeUnit.SECONDS);

                // Log summary for the file
                int totalIssues = groupedIssues.values().stream()
                        .mapToInt(List::size)
                        .sum();

                Map<String, Object> fileLog = new LinkedHashMap<>();
                fileLog.put("file", filePath);
                fileLog.put("issueCount", totalIssues);
                fileLog.put("issues", groupedIssues); // grouped map now

                // Add to global buckets
                if (groupedIssues.containsKey("errors")) {
                    for (IssueItem issue : groupedIssues.get("errors")) {
                        addToMap(errors, issue.getFilePath(), issue);
                    }
                }
                if (groupedIssues.containsKey("improvements")) {
                    for (IssueItem issue : groupedIssues.get("improvements")) {
                        addToMap(improvements, issue.getFilePath(), issue);
                    }
                }
                if (groupedIssues.containsKey("thingsDoneRight")) {
                    for (IssueItem issue : groupedIssues.get("thingsDoneRight")) {
                        addToMap(thingsDoneRight, issue.getFilePath(), issue);
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to get evaluation result for {}", filePath, e);
                IssueItem errorItem = new IssueItem(
                        "Evaluation failed: " + e.getMessage(),
                        filePath,
                        0,
                        0,
                        null,
                        IssueItem.IssueSeverity.ERROR
                );
                addToMap(errors, filePath, errorItem);

            }
        }
    }




    private List<String> generateOverallAssessment(Long submissionId, String userIntent, RepositoryTree repoTree) {
        try {
            // Step 1: Crawl and save repo summary
            boolean success = summaryService.crawlAndSummarize(submissionId);
            if (!success) {
                throw new IllegalStateException("Failed to generate and save repo summary");
            }

            // Step 2: Fetch the saved repo summary
            String repoSummary = projectService.getRepoSummary(submissionId)
                    .orElseThrow(() -> new IllegalStateException("No repo summary found for submissionId: " + submissionId));

            // Step 3: Construct prompts
            String systemPrompt = """
        You are a software evaluator. The user describes an intent or goal, and you assess whether the codebase meets that goal.
        Return your evaluation in two parts:

        1. A single summary sentence that clearly says "Yes, ..." or "No, ..." explaining whether the repo matches the user's intent.
        2. A list of general comments evaluating the codebase, starting with phrases like:
           - "Yes, the repo does this but..."
           - "There are some issues with..."
           - "The implementation could be improved by..."

        Format:
        ---
        [Summary]
        - Comment 1
        - Comment 2
        ...
        ---
        """;

            String userPrompt = "User Intent: " + userIntent + "\n\nRepository Summary:\n" + repoSummary;

            // Step 4: Get response from GroqClient
            String response = groqClient.getCompletion(systemPrompt, userPrompt, 1024, 0.3);

            // Step 5: Parse the response
            List<String> comments = new ArrayList<>();
            String[] lines = response.split("\n");
            boolean inCommentBlock = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("---")) {
                    inCommentBlock = !inCommentBlock;
                    continue;
                }

                if (!inCommentBlock && comments.isEmpty()) {
                    // First line outside comment block = summary sentence
                    comments.add(line);
                } else if (line.startsWith("-")) {
                    comments.add(line.substring(1).trim());
                }
            }

            if (comments.isEmpty()) {
                comments.add("No evaluation could be generated.");
            }

            return comments;

        } catch (Exception e) {
            log.error("Failed to generate overall assessment", e);
            return List.of("Failed to generate overall assessment: " + e.getMessage());
        }
    }



    private void addToMap(Map<String, List<IssueItem>> map, String filePath, IssueItem issue) {
        String key = (filePath == null || filePath.isBlank()) ? "<unknown>" : filePath;
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(issue);
    }

    // -------- Rate limiter helpers (used for overall assessment only here) --------

    private long estimateTokens(int chars) {
        return Math.max(1, chars / 4);
    }

    private void acquireTokens(long needed) throws InterruptedException {
        if (needed > groqTpmLimit) {
            needed = groqTpmLimit;
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
        double perMs = (double) groqTpmLimit / 60000.0;
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