package com.example.demo.utils;


import com.example.demo.model.IssueItem;
import com.example.demo.model.ToolResults;

import java.nio.file.Path;
import java.util.*;

public class SonarQubeAnalyzer implements StaticAnalysisTool {
    private final Map<String, List<IssueItem>> issuesByFile = new HashMap<>();

    @Override
    public ToolResults analyze(Path repoPath) {
        try {
            // Configure and run SonarQube scanner
            SonarScanner scanner = SonarScanner.create()
                    .setProjectKey("repository-evaluation")
                    .setProjectName("Repository Evaluation")
                    .setProjectVersion("1.0")
                    .setSourceEncoding("UTF-8")
                    .setProperty("sonar.sources", repoPath.toString());

            // Execute the scan
            scanner.execute();

            // Parse the results (this would use the SonarQube API to get results)
            List<IssueItem> allIssues = parseSonarResults();

            // Group issues by file
            for (IssueItem issue : allIssues) {
                String filePath = issue.getFilePath();
                issuesByFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(issue);
            }

            return new ToolResults(allIssues);

        } catch (Exception e) {
            System.err.println("Error running SonarQube analysis: " + e.getMessage());
            return new ToolResults(Collections.emptyList());
        }
    }

    @Override
    public List<IssueItem> getIssuesForFile(String filePath) {
        return issuesByFile.getOrDefault(filePath, Collections.emptyList());
    }

    private List<IssueItem> parseSonarResults() {
        // This would use the SonarQube API to get results
        // For now, returning a placeholder
        return Collections.emptyList();
    }
}
