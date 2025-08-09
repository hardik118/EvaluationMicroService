package com.example.demo.utils;



import com.example.demo.model.ComplexityMetric;
import com.example.demo.model.ToolResults;
import javancss.FunctionMetric;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;


public class JavaNcssAnalyzer implements ComplexityAnalyzer {
    private final Map<String, ComplexityMetric> metricsByFile = new HashMap<>();

    @Override
    public ToolResults analyze(Path repoPath) {
        try {
            // Find all Java files in the repository
            List<String> javaFiles = Files.walk(repoPath)
                    .map(Path::toString)
                    .filter(string -> string.endsWith(".java"))
                    .toList();

            // Create a Javancss instance
            Javancss javancss = new Javancss(javaFiles.toArray(new String[0]));

            // Process function metrics
            for (Javancss.FunctionMetric function : javancss.getFunctionMetrics()) {
                String filePath = function.name;  // This is simplified; real implementation would extract file path
                int cyclomaticComplexity = function.ccn;
                int linesOfCode = function.ncss;

                // Create or update metric for this file
                ComplexityMetric metric = metricsByFile.getOrDefault(filePath,
                        new ComplexityMetric(filePath, 0, 0));

                metric.addMethod(function.name, cyclomaticComplexity, linesOfCode);
                metricsByFile.put(filePath, metric);
            }

            // Create tool results with complexity metrics
            return new ToolResults(new ArrayList<>(metricsByFile.values()));

        } catch (Exception e) {
            System.err.println("Error analyzing code complexity: " + e.getMessage());
            return new ToolResults(Collections.emptyList());
        }
    }

    @Override
    public ComplexityMetric getMetricsForFile(String filePath) {
        return metricsByFile.get(filePath);
    }
}
