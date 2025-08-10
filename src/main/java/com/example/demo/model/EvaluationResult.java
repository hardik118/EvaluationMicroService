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
    private Map<String, java.util.List<IssueItem>> errors;
    private Map<String, java.util.List<IssueItem>> improvements;
    private Map<String, java.util.List<IssueItem>> thingsDoneRight;
    private java.util.List<String> generalComments;
}

