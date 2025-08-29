package com.example.demo.utils;

import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import com.example.demo.DbModels.CodeFile;
import com.example.demo.DbRepository.CodeFileRepository;
import com.example.demo.DbService.Impl.ProjectService;
import com.example.demo.DbService.Impl.ProjectStorageService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Service
public class SummaryService {

    private final SummaryCache summaryCache = new SummaryCache();
    private final GroqClient groqClient;  // Your LLM client
    private final ProjectStorageService projectStorageService;
    private final ProjectService projectService;

    private Map<String, String> fileSummaries = new HashMap<>();

    public SummaryService(GroqClient groqClient, ProjectStorageService projectStorageService, ProjectService projectService) {
        this.groqClient = groqClient;
        this.projectStorageService = projectStorageService;
        this.projectService = projectService;
    }

    // Updated method - DB driven, no repoTree param needed anymore
    public boolean crawlAndSummarize(Long submissionId) {
        // 1. Fetch the associated project
        Optional<Project>   project= projectService.findBySubmissionId(submissionId);
        if (project.isEmpty()) {
            System.err.println("[ERROR] Project not found for submissionId: " + submissionId);
            return false;
        }

        Long projectId = project.get().getId();

        // 2. Get all folders for this project
        List<Folder> allFolders = projectStorageService.findAllFoldersByProjectId(projectId);

        // 3. Get all file summaries once from DB
        this.fileSummaries = projectStorageService.getAllFileSummariesBySubmissionId(submissionId);

        if (fileSummaries.isEmpty()) {
            System.out.println("[WARN] No file summaries found in DB for submissionId: " + submissionId);
        } else {
            fileSummaries.forEach((path, summary) -> {
                System.out.println("[DEBUG] File: " + path + " | Summary length: " + (summary != null ? summary.length() : "null"));
            });
        }

        // 4. Summarize all non-root folders first
        Map<String, String> folderSummaries = new HashMap<>();

        for (Folder folder : allFolders) {

            List<String> folderFileSummaries = new ArrayList<>();
            List<String> filesInFolder = projectStorageService.findAllFilespathsByFolderId(folder.getId());

            for (String filePath : filesInFolder) {
                String fileSummary = fileSummaries.getOrDefault(filePath, "");
                if (!fileSummary.isBlank()) {
                    folderFileSummaries.add(fileSummary);

                }
            }


            // Get combined folder summary from LLM
            String folderSummary = getSummaryForTexts(folderFileSummaries);
            System.out.println(folderSummary);
            folderSummaries.put(folder.getPath(), folderSummary);

            // Save summary for debugging

            // Cache folder summary (optional)
            summaryCache.saveFolderSummary(submissionId, folder.getPath(), folderSummary);

            System.out.println("[INFO] Folder summary saved for path: " + folder.getPath());
        }

        // 5. Generate root summary aggregating all folder summaries + root files
        List<String> allSummaries = new ArrayList<>(folderSummaries.values());


        String rootSummary = getSummaryForTexts(allSummaries);
        summaryCache.saveRootSummary(submissionId, rootSummary);

        // Save root summary to project DB
        projectService.saveRepoSummary(submissionId,rootSummary);

        System.out.println("[INFO] Root summary saved for submissionId: " + submissionId);

        return true;
    }

    // Fetch summary from map of summaries loaded from DB
    private String getSummaryForFile(String filePath) {
        return fileSummaries.getOrDefault(filePath, "");
    }

    // Call your LLM to combine multiple summaries into one
    private String getSummaryForTexts(List<String> texts) {
        if (texts.isEmpty()) return "";

        String combinedText = String.join("\n\n", texts);
        String prompt = "Combine the following summaries into a concise overall summary:\n\n";

        return groqClient.getCompletion(prompt, combinedText, 1024, 0.3);
    }


}
