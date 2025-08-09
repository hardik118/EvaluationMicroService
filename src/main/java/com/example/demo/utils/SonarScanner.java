package com.example.demo.utils;


import org.sonarsource.scanner.api.EmbeddedScanner;
import org.sonarsource.scanner.api.LogOutput;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;


/**
 * Wrapper for SonarQube scanner integration
 */
@Slf4j
public class SonarScanner {

    private final Map<String, String> properties = new HashMap<>();
    private EmbeddedScanner embeddedScanner;

    private SonarScanner() {
        // Private constructor for factory method
    }

    /**
     * Create a new SonarScanner instance
     * @return SonarScanner instance
     */
    public static SonarScanner create() {
        return new SonarScanner();
    }

    /**
     * Set a scanner property
     *
     * @param key Property key
     * @param value Property value
     * @return This scanner instance for method chaining
     */
    public SonarScanner setProperty(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Set the project key
     *
     * @param projectKey The project key
     * @return This scanner instance for method chaining
     */
    public SonarScanner setProjectKey(String projectKey) {
        return setProperty("sonar.projectKey", projectKey);
    }

    /**
     * Set the project name
     *
     * @param projectName The project name
     * @return This scanner instance for method chaining
     */
    public SonarScanner setProjectName(String projectName) {
        return setProperty("sonar.projectName", projectName);
    }

    /**
     * Set the project version
     *
     * @param projectVersion The project version
     * @return This scanner instance for method chaining
     */
    public SonarScanner setProjectVersion(String projectVersion) {
        return setProperty("sonar.projectVersion", projectVersion);
    }

    /**
     * Set the source encoding
     *
     * @param encoding The source encoding (e.g., "UTF-8")
     * @return This scanner instance for method chaining
     */
    public SonarScanner setSourceEncoding(String encoding) {
        return setProperty("sonar.sourceEncoding", encoding);
    }

    /**
     * Set the sources directory
     *
     * @param sources The sources directory path
     * @return This scanner instance for method chaining
     */
    public SonarScanner setSources(String sources) {
        return setProperty("sonar.sources", sources);
    }

    /**
     * Set the Java binary directories
     *
     * @param binaries The Java binary directories (compiled classes)
     * @return This scanner instance for method chaining
     */
    public SonarScanner setJavaBinaries(String binaries) {
        return setProperty("sonar.java.binaries", binaries);
    }

    /**
     * Set the host URL for the SonarQube server
     *
     * @param url The SonarQube server URL
     * @return This scanner instance for method chaining
     */
    public SonarScanner setHostUrl(String url) {
        return setProperty("sonar.host.url", url);
    }

    /**
     * Set the login token for authentication
     *
     * @param token The authentication token
     * @return This scanner instance for method chaining
     */
    public SonarScanner setLogin(String token) {
        return setProperty("sonar.login", token);
    }

    /**
     * Initialize the scanner
     *
     * @return This scanner instance for method chaining
     */
    public SonarScanner initialize() {
        // Create embedded scanner with custom log output
        embeddedScanner = EmbeddedScanner.create("Repository Evaluator",
                "1.0",
                new LogAdapter());

        // Add all properties
        embeddedScanner.addGlobalProperties(properties);

        // Initialize
        embeddedScanner.start();

        return this;
    }

    /**
     * Execute the SonarQube analysis
     */
    public void execute() {
        if (embeddedScanner == null) {
            initialize();
        }

        try {
            embeddedScanner.execute(properties);
            log.info("SonarQube analysis completed successfully");
        } catch (Exception e) {
            log.error("Error executing SonarQube analysis", e);
            throw new RuntimeException("SonarQube analysis failed", e);
        }
    }

    /**
     * Adapter to redirect SonarQube logs to SLF4J
     */
    private static class LogAdapter implements LogOutput {
        @Override
        public void log(String message, Level level) {
            switch (level) {
                case TRACE:
                    log.trace(message);
                    break;
                case DEBUG:
                    log.debug(message);
                    break;
                case INFO:
                    log.info(message);
                    break;
                case WARN:
                    log.warn(message);
                    break;
                case ERROR:
                    log.error(message);
                    break;
            }
        }
    }
}
