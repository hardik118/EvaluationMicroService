package com.example.demo.utils;


import com.example.demo.DbModels.Project;
import com.example.demo.DbService.Impl.ProjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Persists folders and files discovered by RepositoryTreeBuilder into the database.
 */
@Slf4j
@RequiredArgsConstructor
public class DbNodeListener implements NodeListener {

    private final Project project;
    private final ProjectStorageService storage;

    // Snippet and content limits to avoid large payloads in DB
    private static final int MAX_SNIPPET_CHARS = 4000;     // keep it small
    private static final long MAX_HASH_BYTES = 512_000;    // hash at most first 500KB

    @Override
    public void onFolder(String relativePath, String parentRelativePath) {
        try {
            // Ensure the folder exists (root is path="")
            storage.getOrCreateFolder(project, normalize(relativePath));
        } catch (Exception e) {
            log.warn("Failed to persist folder {}: {}", relativePath, e.getMessage());
        }
    }

    @Override
    public void onFile(String relativePath, String parentRelativePath, Path absolutePath) {
        try {
            String repoRelPath = normalize(relativePath);

            // Basic metadata
            String language = detectLanguage(repoRelPath);
            Integer sizeBytes = safeSize(absolutePath);
            String content = safeRead(absolutePath);
            Integer lines = content != null ? countLines(content) : null;
            String contentHash = safeHash(absolutePath);
            String contentSnippet = content != null ? snippet(content, MAX_SNIPPET_CHARS) : null;

            // taking/calling can be filled by a later analyzer; keep empty here
            List<String> taking = new ArrayList<>();
            List<String> calling = new ArrayList<>();

            storage.saveOrUpdateFile(
                    project,
                    repoRelPath,
                    language,
                    sizeBytes,
                    lines,
                    contentHash,
                    contentSnippet,
                    taking,
                    calling
            );

        } catch (Exception e) {
            log.warn("Failed to persist file {}: {}", relativePath, e.getMessage());
        }
    }

    /* ---------------- helpers ---------------- */

    private static String normalize(String p) {
        if (p == null) return "";
        String n = p.replace('\\', '/').trim();
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/") && n.length() > 1) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static String detectLanguage(String repoRelPath) {
        String ext = FileUtil.getFileExtension(repoRelPath).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "java" -> "java";
            case "py" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "jsx";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "css" -> "css";
            case "html", "htm" -> "html";
            case "md" -> "markdown";
            case "json" -> "json";
            case "yml", "yaml" -> "yaml";
            case "xml" -> "xml";
            case "go" -> "go";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "cs" -> "csharp";
            default -> ext.isEmpty() ? null : ext;
        };
    }

    private static Integer safeSize(Path file) {
        try {
            long s = Files.size(file);
            return s > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeRead(Path file) {
        try {
            // Only read text-like files; RepositoryTreeBuilder already filters noisy/binary/huge files.
            return Files.readString(file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static String snippet(String s, int maxChars) {
        if (s == null) return null;
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    private static String safeHash(Path file) {
        try {
            long size = Files.size(file);
            byte[] bytes;
            if (size <= MAX_HASH_BYTES) {
                bytes = Files.readAllBytes(file);
            } else {
                // Hash only the first MAX_HASH_BYTES to keep it bounded
                byte[] buf = new byte[(int) MAX_HASH_BYTES];
                try (var in = Files.newInputStream(file)) {
                    int read = in.read(buf);
                    if (read < buf.length) {
                        byte[] exact = new byte[read];
                        System.arraycopy(buf, 0, exact, 0, read);
                        bytes = exact;
                    } else {
                        bytes = buf;
                    }
                }
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception ignored) {
            return null;
        }
    }
}