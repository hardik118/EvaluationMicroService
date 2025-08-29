package com.example.demo.utils;


import com.example.demo.utils.GroqClient;
import com.google.genai.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public Client genaiClient(@Value("${genai.api.key:}") String configApiKey) {
        // Try all possible sources
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configApiKey;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.error("No API key found. Set GEMINI_API_KEY or GOOGLE_API_KEY env var, or genai.api.key in application.properties");
            throw new IllegalStateException("API key missing");
        }

        // Ensure both names are available as system properties too (some libs check system props)
        System.setProperty("GEMINI_API_KEY", apiKey);
        System.setProperty("GOOGLE_API_KEY", apiKey);

        log.info("Creating google-genai Client (apiKey length={})", apiKey.length());
        // Library's no-arg constructor will read the environment/system property
        return new Client();
    }

    @Bean
    public GroqClient groqClient(Client genaiClient) {
        return new GroqClient(genaiClient);
    }
}