package com.example.demo.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class GroqClient {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama3-8b-8192";

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    // Simple retry/backoff settings
    private final int maxRetries = 5;
    private final Duration requestTimeout = Duration.ofSeconds(60);

    public GroqClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("groq.api.key is missing");
        }
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String getCompletion(String prompt) {
        // Default caps; override if you want via additional params
        return getCompletion(prompt, 512, 0.2);
    }

    public String getCompletion(String prompt, int maxTokens, double temperature) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                String body = """
                        {
                          "model": "%s",
                          "messages": [
                            {"role": "user", "content": %s}
                          ],
                          "max_tokens": %d,
                          "temperature": %s,
                          "stream": false
                        }
                        """.formatted(
                        MODEL,
                        mapper.writeValueAsString(prompt),
                        maxTokens,
                        String.valueOf(temperature)
                );

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT))
                        .timeout(requestTimeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(resp.body());
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        String content = choices.get(0).path("message").path("content").asText("");
                        return content != null ? content : "";
                    }
                    throw new RuntimeException("Groq API response missing choices");
                }

                // Non-200: try to parse error JSON
                String bodyText = resp.body() == null ? "" : resp.body();
                String lower = bodyText.toLowerCase(Locale.ROOT);

                // Handle rate limit/backoff; Groq returns helpful wait seconds in message
                if (resp.statusCode() == 429 || lower.contains("rate_limit_exceeded")) {
                    double waitSec = parseWaitSeconds(bodyText);
                    if (attempt <= maxRetries) {
                        // jitter 0–300ms
                        long sleepMs = (long) ((waitSec > 0 ? waitSec : 1.0) * 1000
                                + ThreadLocalRandom.current().nextInt(0, 300));
                        Thread.sleep(sleepMs);
                        continue;
                    }
                }

                // Request too large or other error
                throw new RuntimeException("Groq API error: " + bodyText);

            } catch (IOException | InterruptedException e) {
                if (attempt >= maxRetries) {
                    throw new RuntimeException("Groq API request failed after retries: " + e.getMessage(), e);
                }
                // basic exponential backoff with jitter
                try {
                    Thread.sleep(200L * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextInt(0, 200));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
            }
        }
    }

    private double parseWaitSeconds(String errorBody) {
        // Try to extract “Please try again in X.s” from Groq error message
        if (errorBody == null) return 0;
        try {
            JsonNode root = mapper.readTree(errorBody);
            String msg = root.path("error").path("message").asText("");
            if (msg == null) return 0;
            // look for a number followed by 's'
            var m = java.util.regex.Pattern.compile("try again in ([0-9]+\\.?[0-9]*)s", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(msg);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        } catch (Exception ignored) {
            // if body is not JSON, ignore
        }
        return 0;
    }
}