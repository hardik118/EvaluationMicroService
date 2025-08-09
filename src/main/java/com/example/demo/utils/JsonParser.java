package com.example.demo.utils;


import com.example.demo.model.IssueItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for parsing JSON and structured text responses
 */
@Slf4j
public class JsonParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse the LLM evaluation response into a structured map
     *
     * @param response The raw text response from the LLM
     * @return Map containing parsed evaluation data
     */
    public static Map<String, Object> parseEvaluation(String response) {
        Map<String, Object> result = new HashMap<>();

        // First try to parse as JSON directly
        try {
            Map<?, ?> jsonMap = objectMapper.readValue(response, Map.class);

            // Convert to string keys
            for (Object key : jsonMap.keySet()) {
                result.put(key.toString(), jsonMap.get(key));
            }

            return processEvaluationMap(result);

        } catch (JsonProcessingException e) {
            log.debug("Response is not valid JSON, trying to extract JSON or parse as structured text");
        }

        // Try to extract JSON from the text response (in case it's embedded)
        String extractedJson = extractJsonFromText(response);
        if (extractedJson != null) {
            try {
                Map<?, ?> jsonMap = objectMapper.readValue(extractedJson, Map.class);

                // Convert to string keys
                for (Object key : jsonMap.keySet()) {
                    result.put(key.toString(), jsonMap.get(key));
                }

                return processEvaluationMap(result);

            } catch (JsonProcessingException e) {
                log.debug("Extracted text is not valid JSON, falling back to structured text parsing");
            }
        }

        // Fall back to parsing as structured text
        return parseStructuredText(response);
    }

    /**
     * Try to extract JSON from a text that might contain other content
     */
    private static String extractJsonFromText(String text) {
        // Pattern to find text that looks like JSON (starts with { and ends with })
        Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\\}");
        Matcher matcher = jsonPattern.matcher(text);

        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    /**
     * Process the evaluation map to ensure it has the expected structure
     */
    private static Map<String, Object> processEvaluationMap(Map<String, Object> map) {
        Map<String, Object> processedMap = new HashMap<>(map);

        // Convert issue lists to IssueItem objects
        processIssueList(processedMap, "errors");
        processIssueList(processedMap, "improvements");
        processIssueList(processedMap, "thingsDoneRight");

        // Ensure generalComments exists
        if (!processedMap.containsKey("generalComments")) {
            processedMap.put("generalComments", new ArrayList<String>());
        } else if (!(processedMap.get("generalComments") instanceof List)) {
            // If it's a string, convert to a list with one item
            if (processedMap.get("generalComments") instanceof String) {
                List<String> comments = new ArrayList<>();
                comments.add((String) processedMap.get("generalComments"));
                processedMap.put("generalComments", comments);
            }
        }

        return processedMap;
    }

    /**
     * Convert issue entries in the map to proper IssueItem objects
     */
    @SuppressWarnings("unchecked")
    private static void processIssueList(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            map.put(key, new HashMap<String, List<IssueItem>>());
            return;
        }

        if (!(map.get(key) instanceof Map)) {
            map.put(key, new HashMap<String, List<IssueItem>>());
            return;
        }

        Map<String, Object> issuesByFile = (Map<String, Object>) map.get(key);
        Map<String, List<IssueItem>> processed = new HashMap<>();

        for (String file : issuesByFile.keySet()) {
            List<IssueItem> issues = new ArrayList<>();

            if (issuesByFile.get(file) instanceof List) {
                List<Object> rawIssues = (List<Object>) issuesByFile.get(file);

                for (Object rawIssue : rawIssues) {
                    if (rawIssue instanceof Map) {
                        Map<String, Object> issueMap = (Map<String, Object>) rawIssue;
                        issues.add(convertToIssueItem(issueMap, file));
                    } else if (rawIssue instanceof String) {
                        // Simple string description
                        issues.add(new IssueItem((String) rawIssue, file, null, null));
                    }
                }
            } else if (issuesByFile.get(file) instanceof String) {
                // Single string description
                issues.add(new IssueItem((String) issuesByFile.get(file), file, null, null));
            }

            processed.put(file, issues);
        }

        map.put(key, processed);
    }

    /**
     * Convert a map to an IssueItem
     */
    private static IssueItem convertToIssueItem(Map<String, Object> map, String defaultFilePath) {
        String description = map.containsKey("description") ?
                map.get("description").toString() :
                "No description provided";

        String filePath = map.containsKey("filePath") ?
                map.get("filePath").toString() :
                defaultFilePath;

        Integer lineNumber = null;
        if (map.containsKey("lineNumber")) {
            try {
                lineNumber = Integer.parseInt(map.get("lineNumber").toString());
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }

        String codeContext = map.containsKey("codeContext") ?
                map.get("codeContext").toString() :
                null;

        IssueItem.IssueSeverity severity = IssueItem.IssueSeverity.MEDIUM;
        if (map.containsKey("severity")) {
            String severityStr = map.get("severity").toString().toUpperCase();
            try {
                severity = IssueItem.IssueSeverity.valueOf(severityStr);
            } catch (IllegalArgumentException e) {
                // Use default severity if invalid
            }
        }

        return new IssueItem(description, filePath, lineNumber, codeContext, severity);
    }

    /**
     * Parse structured text format for evaluation
     */
    private static Map<String, Object> parseStructuredText(String text) {
        Map<String, Object> result = new HashMap<>();
        Map<String, List<IssueItem>> errors = new HashMap<>();
        Map<String, List<IssueItem>> improvements = new HashMap<>();
        Map<String, List<IssueItem>> thingsDoneRight = new HashMap<>();
        List<String> generalComments = new ArrayList<>();

        // Sections to look for
        String[] sections = {
                "## Errors",
                "## Improvements",
                "## Things Done Right",
                "## General Comments"
        };

        // Split the text into sections
        Map<String, String> sectionContent = splitIntoSections(text, sections);

        // Parse errors section
        if (sectionContent.containsKey("## Errors")) {
            errors = parseIssuesSection(sectionContent.get("## Errors"));
        }

        // Parse improvements section
        if (sectionContent.containsKey("## Improvements")) {
            improvements = parseIssuesSection(sectionContent.get("## Improvements"));
        }

        // Parse things done right section
        if (sectionContent.containsKey("## Things Done Right")) {
            thingsDoneRight = parseIssuesSection(sectionContent.get("## Things Done Right"));
        }

        // Parse general comments section
        if (sectionContent.containsKey("## General Comments")) {
            generalComments = parseGeneralComments(sectionContent.get("## General Comments"));
        }

        // Assemble result
        result.put("errors", errors);
        result.put("improvements", improvements);
        result.put("thingsDoneRight", thingsDoneRight);
        result.put("generalComments", generalComments);

        return result;
    }

    /**
     * Split text into sections based on headers
     */
    private static Map<String, String> splitIntoSections(String text, String[] sectionHeaders) {
        Map<String, String> sections = new HashMap<>();

        // Add headers and their indices
        List<Integer> headerIndices = new ArrayList<>();
        List<String> foundHeaders = new ArrayList<>();

        for (String header : sectionHeaders) {
            int index = text.indexOf(header);
            if (index >= 0) {
                headerIndices.add(index);
                foundHeaders.add(header);
            }
        }

        // Sort headers by their position in the text
        for (int i = 0; i < headerIndices.size(); i++) {
            for (int j = i + 1; j < headerIndices.size(); j++) {
                if (headerIndices.get(i) > headerIndices.get(j)) {
                    // Swap indices
                    Integer tempIndex = headerIndices.get(i);
                    headerIndices.set(i, headerIndices.get(j));
                    headerIndices.set(j, tempIndex);

                    // Swap headers
                    String tempHeader = foundHeaders.get(i);
                    foundHeaders.set(i, foundHeaders.get(j));
                    foundHeaders.set(j, tempHeader);
                }
            }
        }

        // Extract content between headers
        for (int i = 0; i < foundHeaders.size(); i++) {
            String header = foundHeaders.get(i);
            int startIndex = headerIndices.get(i) + header.length();
            int endIndex = (i < foundHeaders.size() - 1) ?
                    headerIndices.get(i + 1) :
                    text.length();

            sections.put(header, text.substring(startIndex, endIndex).trim());
        }

        return sections;
    }

    /**
     * Parse an issues section into a map of file paths to issue lists
     */
    private static Map<String, List<IssueItem>> parseIssuesSection(String sectionContent) {
        Map<String, List<IssueItem>> issuesByFile = new HashMap<>();

        // Look for file headers (### filename.ext)
        Pattern fileHeaderPattern = Pattern.compile("###\\s+([^\\n]+)");
        Matcher fileHeaderMatcher = fileHeaderPattern.matcher(sectionContent);

        int lastEnd = 0;
        String currentFile = "general";

        while (fileHeaderMatcher.find()) {
            // Get content for the previous file
            if (fileHeaderMatcher.start() > lastEnd) {
                String fileContent = sectionContent.substring(lastEnd, fileHeaderMatcher.start()).trim();
                if (!fileContent.isEmpty()) {
                    issuesByFile.put(currentFile, parseIssuesForFile(fileContent, currentFile));
                }
            }

            // Update current file and position
            currentFile = fileHeaderMatcher.group(1).trim();
            lastEnd = fileHeaderMatcher.end();
        }

        // Handle the last file section
        if (lastEnd < sectionContent.length()) {
            String fileContent = sectionContent.substring(lastEnd).trim();
            if (!fileContent.isEmpty()) {
                issuesByFile.put(currentFile, parseIssuesForFile(fileContent, currentFile));
            }
        }

        return issuesByFile;
    }

    /**
     * Parse issues for a specific file
     */
    private static List<IssueItem> parseIssuesForFile(String content, String filePath) {
        List<IssueItem> issues = new ArrayList<>();

        // Split content by bullet points
        String[] bullets = content.split("(?m)^\\s*-\\s+");

        for (String bullet : bullets) {
            bullet = bullet.trim();
            if (!bullet.isEmpty()) {
                // Try to extract line number and code context
                Pattern linePattern = Pattern.compile("Line\\s+(\\d+)");
                Matcher lineMatcher = linePattern.matcher(bullet);

                Integer lineNumber = null;
                if (lineMatcher.find()) {
                    lineNumber = Integer.parseInt(lineMatcher.group(1));
                }

                // Look for code blocks
                Pattern codePattern = Pattern.compile("```[^`]*```");
                Matcher codeMatcher = codePattern.matcher(bullet);

                String codeContext = null;
                if (codeMatcher.find()) {
                    codeContext = codeMatcher.group().replaceAll("```", "").trim();
                }

                issues.add(new IssueItem(bullet, filePath, lineNumber, codeContext));
            }
        }

        return issues;
    }

    /**
     * Parse general comments section
     */
    private static List<String> parseGeneralComments(String content) {
        List<String> comments = new ArrayList<>();

        // Split by bullet points or paragraphs
        String[] parts = content.split("(?m)^\\s*-\\s+|(?m)^\\s*$");

        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) {
                comments.add(part);
            }
        }

        return comments;
    }
}
