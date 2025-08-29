package com.example.demo.utils;


public class LlmResponseParser {

    public static class ParsedResponse {
        public String jsonPart;
        public String summaryPart;

        public ParsedResponse(String jsonPart, String summaryPart) {
            this.jsonPart = jsonPart;
            this.summaryPart = summaryPart;
        }
    }

    public static ParsedResponse parseLlmResponse(String input) {
        if (input == null) {
            return new ParsedResponse("", "");
        }

        int startIndex = input.indexOf("```json");
        int endIndex = input.indexOf("```", startIndex + 1);

        String jsonPart = "";
        String summaryPart = "";

        if (startIndex >= 0 && endIndex > startIndex) {
            // Extract fenced block with ```json ... ```
            String fencedJson = input.substring(startIndex, endIndex + 3);

            // Use existing logic to strip fences and get raw JSON
            jsonPart = stripJsonCodeFence(fencedJson);

            // The summary is whatever is after the fenced JSON block
            if (endIndex + 3 < input.length()) {
                summaryPart = input.substring(endIndex + 3).trim();
            }
        } else {
            // No fenced JSON found; treat entire input as JSON and empty summary
            jsonPart = input.trim();
        }

        return new ParsedResponse(jsonPart, summaryPart);
    }

    private static String stripJsonCodeFence(String input) {
        if (input == null) return "";

        if (input.startsWith("```json")) {
            int firstNewline = input.indexOf('\n');
            if (firstNewline >= 0) {
                input = input.substring(firstNewline + 1);
            }
            if (input.endsWith("```")) {
                input = input.substring(0, input.length() - 3);
            }
        }
        return input.trim();
    }
}
