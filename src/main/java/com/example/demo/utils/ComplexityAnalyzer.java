package com.example.demo.utils;

import com.example.demo.model.ComplexityMetric;
import com.example.demo.model.ToolResults;

import java.nio.file.Path;

public interface ComplexityAnalyzer {
    ToolResults analyze(Path repoPath);
    ComplexityMetric getMetricsForFile(String filePath);
}
