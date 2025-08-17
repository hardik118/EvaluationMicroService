package com.example.demo.utils;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class GroqClient {
    private static final Logger logger = LoggerFactory.getLogger(GroqClient.class);
    private static final String MODEL = "gemini-2.5-flash";

    private final Client genaiClient;
    private final int maxRetries = 5;

    // Accept the library Client (injected from AppConfig)
    public GroqClient(Client genaiClient) {
        this.genaiClient = genaiClient;
        logger.info("GroqClient initialized with injected Client");
    }

    public String getCompletion(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                String combinedPrompt = "System: " + systemPrompt + "\n\nUser: " + userPrompt;

                GenerateContentResponse response = genaiClient.models.generateContent(
                        MODEL,
                        combinedPrompt,
                        null
                );

                if (response != null) {
                    return response.text();
                }

                throw new RuntimeException("API response missing content");
            } catch (Exception e) {
                logger.warn("API call attempt {} failed: {}", attempt, e.getMessage());
                if (attempt >= maxRetries) {
                    throw new RuntimeException("API request failed after retries: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
    }
}