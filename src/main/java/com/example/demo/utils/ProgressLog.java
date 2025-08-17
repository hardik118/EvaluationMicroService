package com.example.demo.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

public final class ProgressLog {

    private static volatile Path logFile = Paths.get("logs", "progress.log");
    private static final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            // serialize private fields too (so you don't need getters)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private ProgressLog() {}

    // Optional: change where the log goes (call once at startup if you want)
    public static void to(String filePath) {
        logFile = Paths.get(filePath);
        ensureFile();
    }

    // Log any object as JSON (single line): tag + data
    public static void write(String tag, Object data) {
        String prefix = Instant.now().toString() + " [" + tag + "] ";
        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            json = "\"" + String.valueOf(data) + "\"";
        }
        append(prefix + json);
    }

    // Log plain text
    public static void writeText(String tag, String text) {
        String line = Instant.now().toString() + " [" + tag + "] " + text;
        append(line);
    }

    // Log an error/exception
    public static void writeError(String tag, Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String line = Instant.now().toString() + " [" + tag + "] ERROR: " + e.toString()
                + " | stack=" + sw.toString().replace("\n", "\\n");
        append(line);
    }

    // Convenience: record a function result and return it
    public static <T> T recordResult(String tag, T result) {
        write(tag, result);
        return result;
    }

    // -------------- internals --------------
    private static synchronized void append(String line) {
        try {
            ensureFile();
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static void ensureFile() {
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (Exception ignored) {}
    }
}