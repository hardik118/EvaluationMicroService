package com.example.demo.service;

import com.example.demo.DbModels.Project;
import com.example.demo.DbService.Impl.ProjectService;
import com.example.demo.DbService.Impl.ProjectStorageService;
import com.example.demo.kafka.FeedbackProducer;
import com.example.demo.model.EvaluationContext;
import com.example.demo.model.EvaluationResult;
import com.example.demo.model.IssueItem;
import com.example.demo.utils.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private final ProducerFactory<?, ?> producerFactory;
    private final DependencyCheckAnalyzer dependencyCheckAnalyzer;
    private final GitUtils gitService;
    private final FeedbackProducer feedbackProducer;
    private final ProjectService projectService;
    private final GroqClient groqClient;  // Add this field



    // Delegate file-level evaluations to a dedicated service
    @Autowired
    private     EvaluationService evaluationService;



    @Value("${evaluation.timeout.seconds:600}")
    private int evaluationTimeoutSeconds;

    @Value("${groq.tpm.limit:6000}")
    private long groqTpmLimit;


    private final Object rlLock = new Object();
    private double rlTokens;
    private long rlLastRefillMs;

    @Autowired
    private ProjectStorageService projectStorageService;

    public RepositoryEvaluatorService(
            GitUtils gitService,
            FeedbackProducer feedbackProducer,
            ProducerFactory<?, ?> producerFactory,
            DependencyCheckAnalyzer dependencyCheckAnalyzer,
            ProjectService projectService,
            GroqClient groqClient
    ) {
        this.gitService = gitService;
        this.feedbackProducer = feedbackProducer;
        this.producerFactory = producerFactory;
        this.dependencyCheckAnalyzer = dependencyCheckAnalyzer;
        this.projectService = projectService;
        this.groqClient = groqClient;
    }



    /**
     * Evaluate a repository from its GitHub URL
     */
    public void evaluateRepositoryFromUrl(String repoUrl, Long submissionId) {
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

            EvaluationResult evaluationResult = evaluateRepository(repoPath, submissionId);
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
    public EvaluationResult evaluateRepository(Path repoPath, Long submissionId) {
        try {
            ProgressLog.write("evaluation.start", Map.of(
                    "submissionId", submissionId,
                    "repoPath", String.valueOf(repoPath))
            );
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
            ProgressLog.write("evaluation.context", context.debugSnapshot());

            // Submit files for evaluation via EvaluationService (returns futures)
            Map<String, Future<List<IssueItem>>> futureResults =
                    evaluationService.submitFilesForEvaluation(repoTree, context,repoPath);


// Define output folder to store result files
            Path evaluationOutputDir = Paths.get("evaluation-results");

// Timeout seconds, e.g. 60 seconds
            int evaluationTimeoutSeconds = 60;

// Save the results in separate .txt files inside evaluation-results folder
            evaluationService.saveEvaluationResultsToFiles(futureResults, evaluationOutputDir, evaluationTimeoutSeconds);


            // Aggregate results
            Map<String, List<IssueItem>> errors = new HashMap<>();
            Map<String, List<IssueItem>> improvements = new HashMap<>();
            Map<String, List<IssueItem>> thingsDoneRight = new HashMap<>();

            // Process the evaluation results (waits on futures and logs to process.log)
            processEvaluationResults(futureResults, errors, improvements, thingsDoneRight);

            // Generate overall assessment (optional higher-level summary)
            List<String> generalComments = generateOverallAssessment(context);

            // Final structured result
            EvaluationResult result = new EvaluationResult(submissionId, errors, improvements, thingsDoneRight, generalComments);

            // Log summary to process log
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("submissionId", submissionId);
            summary.put("filesEvaluated", futureResults.size());
            summary.put("errorsCount", errors.values().stream().mapToInt(List::size).sum());
            summary.put("improvementsCount", improvements.values().stream().mapToInt(List::size).sum());
            summary.put("thingsDoneRightCount", thingsDoneRight.values().stream().mapToInt(List::size).sum());
            ProgressLog.write("evaluation.summary", summary);

            return result;

        } catch (Exception e) {
            log.error("Error evaluating repository", e);
            ProgressLog.write("evaluation.error", Map.of(
                    "submissionId", submissionId,
                    "error", String.valueOf(e))
            );
            throw new RuntimeException("Failed to evaluate repository", e);
        }
    }

    /**
     * Wait for futures, bucket issues, and log per-file results to process.log
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
                List<IssueItem> issues = future.get(evaluationTimeoutSeconds, TimeUnit.SECONDS);

                // Log to process.log per file
                Map<String, Object> fileLog = new LinkedHashMap<>();
                fileLog.put("file", filePath);
                fileLog.put("issueCount", issues.size());
                fileLog.put("issues", issues); // ensure IssueItem has a readable toString or is serializable
                ProgressLog.write("evaluation.file", fileLog);

                // Bucket issues
                for (IssueItem issue : issues) {
                    if (issue.getSeverity() == IssueItem.IssueSeverity.ERROR) {
                        addToMap(errors, issue.getFilePath(), issue);
                    } else if (isPositiveSignal(issue.getDescription())) {
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
                ProgressLog.write("evaluation.file.error", Map.of(
                        "file", filePath,
                        "error", String.valueOf(e))
                );
            }
        }
    }

    private boolean isPositiveSignal(String description) {
        if (description == null) return false;
        String d = description.toLowerCase(Locale.ROOT);
        return d.contains("good") || d.contains("right") || d.contains("well") || d.contains("best practice");
    }

    private List<String> generateOverallAssessment(EvaluationContext context) {
        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Based on the following information, provide an overall assessment ")
                    .append("of the codebase with key strengths and areas for improvement.\n\n");

            userPrompt.append("Files (first 30 shown):\n");
            int max = Math.min(30, context.getAllFilePaths().size());
            for (int i = 0; i < max; i++) {
                userPrompt.append("- ").append(context.getAllFilePaths().get(i)).append('\n');
            }

            // Define your system prompt
            String systemPrompt = """
            You are a code quality expert. Provide a concise and structured response:
            - Start with **Strengths:** listing key positive points.
            - Follow with **Areas for Improvement:** listing key issues.
            - Use numbered lists for readability.
            - Avoid additional commentary outside these sections.
            """;

            long estTokens = estimateTokens(systemPrompt.length() + userPrompt.length());
            acquireTokens(estTokens);

            // Call groqClient with both prompts
            String response = groqClient.getCompletion(systemPrompt, userPrompt.toString(), 1024, 0.2);

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

            ProgressLog.write("evaluation.overall", Map.of("comments", comments));
            return comments;

        } catch (Exception e) {
            log.error("Failed to generate overall assessment", e);
            List<String> fallback = new ArrayList<>();
            fallback.add("Failed to generate overall assessment: " + e.getMessage());
            ProgressLog.write("evaluation.overall.error", Map.of("error", String.valueOf(e)));
            return fallback;
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