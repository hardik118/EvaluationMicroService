package com.example.demo.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

public class LLMLogger {

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT); // pretty print JSON

    /**
     * Saves a parsed LLM evaluation (Map<String, Object>) to a readable .txt file
     */
    public static void saveParsedEvaluationToFile(Map<String, Object> evaluation, String repoRelPath) {
        try {
            String safeFileName = repoRelPath.replace("/", "_").replace("\\", "_");

            Path evalDir = Paths.get("parsed_llm_evaluations");
            Files.createDirectories(evalDir);

            Path outputFile = evalDir.resolve(safeFileName + ".txt");

            // Serialize map to pretty JSON and write to file
            String jsonOutput = mapper.writeValueAsString(evaluation);
            Files.writeString(outputFile, jsonOutput, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Parsed LLM evaluation saved to: " + outputFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save parsed evaluation for " + repoRelPath + ": " + e.getMessage());
        }
    }
}
