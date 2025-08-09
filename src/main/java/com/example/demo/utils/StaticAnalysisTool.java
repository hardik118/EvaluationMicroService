package com.example.demo.utils;



import com.example.demo.model.IssueItem;
import com.example.demo.model.ToolResults;

import java.nio.file.Path;
import java.util.List;

public interface StaticAnalysisTool {
    ToolResults analyze(Path repoPath);
    List<IssueItem> getIssuesForFile(String filePath);
}
