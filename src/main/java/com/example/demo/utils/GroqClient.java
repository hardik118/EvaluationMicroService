package com.example.demo.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client for interacting with the Groq API
 */
public class GroqClient {
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL_NAME = "llama3-8b-8192";
    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.2;

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GroqClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get a completion from the Groq API
     *
     * @param prompt The prompt to send
     * @return The model's response
     */
    public String getCompletion(String prompt) throws Exception {
        // Create the request payload
        GroqRequest request = new GroqRequest(
                MODEL_NAME,
                Collections.singletonList(new Message("user", prompt)),
                MAX_TOKENS,
                TEMPERATURE,
                true
        );

        String requestBody = objectMapper.writeValueAsString(request);

        // Build the HTTP request
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // Send the request
        HttpResponse<String> response = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        // Check for errors
        if (response.statusCode() != 200) {
            throw new RuntimeException("Groq API error: " + response.body());
        }

        // Parse the response
        GroqResponse groqResponse = objectMapper.readValue(response.body(), GroqResponse.class);

        // Extract the content
        if (groqResponse.getChoices() != null && !groqResponse.getChoices().isEmpty()) {
            return groqResponse.getChoices().get(0).getMessage().getContent();
        } else {
            throw new RuntimeException("No content in Groq API response");
        }
    }

    /**
     * Asynchronously get a completion from the Groq API
     */
    public CompletableFuture<String> getCompletionAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getCompletion(prompt);
            } catch (Exception e) {
                throw new RuntimeException("Error getting completion", e);
            }
        });
    }

    /**
     * Request model for Groq API
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GroqRequest {
        private String model;
        private List<Message> messages;

        @JsonProperty("max_tokens")
        private int maxTokens;

        private double temperature;

        @JsonProperty("stream")
        private boolean isStream;
    }

    /**
     * Response model from Groq API
     */
    @Data
    @NoArgsConstructor
    public static class GroqResponse {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<Choice> choices;
        private Usage usage;
    }

    /**
     * Message in the conversation
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    /**
     * Choice in the response
     */
    @Data
    @NoArgsConstructor
    public static class Choice {
        private int index;
        private Message message;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * Token usage information
     */
    @Data
    @NoArgsConstructor
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        @JsonProperty("completion_tokens")
        private int completionTokens;

        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
