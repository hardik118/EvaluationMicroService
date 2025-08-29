package com.example.demo.utils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SummaryCache {
    // Key format: submissionId + ":" + folderPath (e.g. "123:/src/utils")
    private final Map<String, String> folderSummaries = new ConcurrentHashMap<>();

    // Keyed by submissionId only for root summary
    private final Map<Long, String> rootSummaries = new ConcurrentHashMap<>();

    public void saveFolderSummary(Long submissionId, String folderPath, String summary) {
        String key = submissionId + ":" + folderPath;
        folderSummaries.put(key, summary);
    }

    public Optional<String> getFolderSummary(Long submissionId, String folderPath) {
        String key = submissionId + ":" + folderPath;
        return Optional.ofNullable(folderSummaries.get(key));
    }

    public void saveRootSummary(Long submissionId, String summary) {
        rootSummaries.put(submissionId, summary);
    }

    public Optional<String> getRootSummary(Long submissionId) {
        return Optional.ofNullable(rootSummaries.get(submissionId));
    }

    public void invalidateFolderSummary(Long submissionId, String folderPath) {
        String key = submissionId + ":" + folderPath;
        folderSummaries.remove(key);
    }

    public void invalidateRootSummary(Long submissionId) {
        rootSummaries.remove(submissionId);
    }
}
