package com.example.demo.model;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ToolResults {
    private final List<IssueItem> issues = new ArrayList<>();
    private final List<ComplexityMetric> complexityMetrics = new ArrayList<>();
    private final List<DependencyInfo> dependencies = new ArrayList<>();

    public ToolResults(List<?> results) {
        for (Object result : results) {
            if (result instanceof IssueItem) {
                issues.add((IssueItem) result);
            } else if (result instanceof ComplexityMetric) {
                complexityMetrics.add((ComplexityMetric) result);
            } else if (result instanceof DependencyInfo) {
                dependencies.add((DependencyInfo) result);
            }
        }
    }

    public List<IssueItem> getIssues() {
        return Collections.unmodifiableList(issues);
    }

    public List<ComplexityMetric> getComplexityMetrics() {
        return Collections.unmodifiableList(complexityMetrics);
    }

    public List<DependencyInfo> getDependencies() {
        return Collections.unmodifiableList(dependencies);
    }
}

