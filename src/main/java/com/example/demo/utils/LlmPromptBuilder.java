package com.example.demo.utils;


import java.util.*;

public final class LlmPromptBuilder {

    private LlmPromptBuilder() {}

    @SuppressWarnings("unchecked")
    public static String buildEvaluationPrompt(String filePath,
                                               String language,
                                               String content,
                                               Map<String, Object> fileContext,
                                               int maxDeps,
                                               int maxDependents,
                                               int maxExports) {

        Set<String> taking = toLimitedSet((Collection<String>) fileContext.getOrDefault("taking", Collections.emptySet()), maxDeps);
        Set<String> dependents = toLimitedSet((Collection<String>) fileContext.getOrDefault("dependents", Collections.emptySet()), maxDependents);
        Set<String> calling = toLimitedSet((Collection<String>) fileContext.getOrDefault("calling", Collections.emptySet()), maxExports);

        StringBuilder sb = new StringBuilder(4096);

        sb.append("You are a senior code reviewer. Analyze the following file in the context of its repository.\n")
                .append("Return strict JSON with keys: errors, improvements, thingsDoneRight. Each value is a list of IssueItem JSON:\n")
                .append("{ \"title\": string, \"filePath\": string, \"lineStart\": number|null, \"lineEnd\": number|null, \"severity\": \"INFO\"|\"WARN\"|\"ERROR\" }\n\n");

        sb.append("Repository context for this file:\n")
                .append("- filePath: ").append(filePath).append('\n')
                .append("- language: ").append(nullToNA(language)).append('\n');

        sb.append("- taking (imports, repo-relative, limited to ").append(maxDeps).append("):\n");
        if (taking.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String t : taking) sb.append("  - ").append(t).append('\n');
        }

        sb.append("- dependents (files that import/call this, limited to ").append(maxDependents).append("):\n");
        if (dependents.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String d : dependents) sb.append("  - ").append(d).append('\n');
        }

        sb.append("- calling (exported/public symbols, limited to ").append(maxExports).append("):\n");
        if (calling.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String c : calling) sb.append("  - ").append(c).append('\n');
        }

        sb.append("\nReview goals:\n")
                .append("- Identify correctness/security issues and missing error handling.\n")
                .append("- Point out performance and scalability issues.\n")
                .append("- Note architecture or API misuse problems.\n")
                .append("- Suggest concrete, minimal improvements aligned with the codebase style.\n")
                .append("- Use the dependency context to check for broken imports/exports or misuse.\n\n");

        sb.append("Return only the JSON object. Do not add any commentary.\n\n");

        sb.append("File content starts:\n")
                .append("```").append(languageTag(language)).append('\n')
                .append(content).append('\n')
                .append("```\n");

        return sb.toString();
    }

    private static String nullToNA(String s) {
        return (s == null || s.isBlank()) ? "n/a" : s;
    }

    private static String languageTag(String lang) {
        if (lang == null) return "";
        String l = lang.toLowerCase(Locale.ROOT);
        // common normalizations
        if (l.contains("javascript") || l.equals("js") || l.equals("jsx")) return "javascript";
        if (l.contains("typescript") || l.equals("ts") || l.equals("tsx")) return "typescript";
        if (l.equals("py") || l.contains("python")) return "python";
        if (l.contains("java")) return "java";
        if (l.contains("html")) return "html";
        if (l.contains("css")) return "css";
        return "";
    }

    private static Set<String> toLimitedSet(Collection<String> in, int limit) {
        if (in == null || in.isEmpty()) return Collections.emptySet();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim());
            if (out.size() >= limit) break;
        }
        return out;
    }
}