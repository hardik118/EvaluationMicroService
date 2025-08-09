package com.example.demo.model;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
public class ComplexityMetric {
    private final String filePath;
    private int cyclomaticComplexity;
    private int linesOfCode;
    private final Map<String, MethodMetric> methodMetrics = new HashMap<>();



    public void addMethod(String methodName, int complexity, int lines) {
        methodMetrics.put(methodName, new MethodMetric(methodName, complexity, lines));

        // Update file totals
        this.cyclomaticComplexity += complexity;
        this.linesOfCode += lines;
    }




    // Helper class for method-level metrics
    @Data
    @AllArgsConstructor
    public static class MethodMetric {
        private final String methodName;
        private final int complexity;
        private final int linesOfCode;


    }
}
