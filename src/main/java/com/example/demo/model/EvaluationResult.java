package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResult {
    private Long submissionId;
    private Map<String, List<IssueItem>> errors;            // path -> list of errors
    private Map<String, List<IssueItem>> improvements;      // path -> list of improvements
    private Map<String, List<IssueItem>> thingsDoneRight;   // path -> list of positives
    private List<String> generalComments;                   // Overall comments
}

