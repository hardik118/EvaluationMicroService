package com.example.demo.model;

import com.example.demo.DbModels.CodeFile;
import com.example.demo.DbModels.Project;
import com.example.demo.DbService.Impl.ProjectStorageService;
import com.example.demo.utils.FileUtil;


import java.nio.file.Path;
import java.util.*;

/**
 * DB-backed evaluation context.
 * Builds context from CodeFile rows:
 * - taking: resolved import paths (repo-relative)
 * - calling: exported/public symbols
 * - dependents: reverse index built from taking
 * - language: detected/stored language for the file
 *
 * Backward compatibility:
 * - getFileContext includes "dependencies" (alias to taking) and "dependents".
 *
 * NOTE: Summaries/folder presence are no longer populated here.
 */
public class EvaluationContext {

    // Core maps (repo-relative normalized paths as keys)
    private final Map<String, Set<String>> takingByFile = new HashMap<>();
    private final Map<String, Set<String>> dependentsByFile = new HashMap<>();
    private final Map<String, Set<String>> callingByFile = new HashMap<>();
    private final Map<String, String> languageByFile = new HashMap<>();

    // Convenience: all file paths tracked (repo-relative, normalized)
    private final List<String> allFiles = new ArrayList<>();

    /**
     * Build context directly from DB via ProjectStorageService, using the project's files.
     * repoRoot is accepted for API clarity; paths stored in DB are repo-relative already.
     */
    public static EvaluationContext fromRepoPath(Path repoRoot, Project project, ProjectStorageService storage) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(storage, "storage must not be null");

        List<CodeFile> files = storage.listFiles(project);
        EvaluationContext ctx = fromCodeFiles(files);


        return ctx;
    }

    /**
     * Build context from CodeFile rows for a project.
     */
    public static EvaluationContext fromCodeFiles(Collection<CodeFile> files) {
        EvaluationContext ctx = new EvaluationContext();


        if (files != null) {
            // Track paths without duplicates, preserving order
            LinkedHashSet<String> seenPaths = new LinkedHashSet<>();

            // 1) Index primary maps
            for (CodeFile cf : files) {
                String path = normalizePath(cf.getPath());
                if (path.isEmpty()) continue;

                seenPaths.add(path);

                // taking (imports)
                Collection<String> takingList = (cf.getTaking() != null) ? cf.getTaking() : Collections.emptySet();
                Set<String> taking = new LinkedHashSet<>();
                for (String t : takingList) {
                    String nt = normalizePath(t);
                    if (!nt.isEmpty()) taking.add(nt);
                }
                if (!taking.isEmpty()) {
                    ctx.takingByFile.put(path, taking);
                }

                // calling (exports)
                Collection<String> callingList = (cf.getCalling() != null) ? cf.getCalling() : Collections.emptySet();
                Set<String> calling = new LinkedHashSet<>();
                for (String c : callingList) {
                    if (c != null && !c.isBlank()) calling.add(c.trim());
                }
                if (!calling.isEmpty()) {
                    ctx.callingByFile.put(path, calling);
                }

                // language
                if (cf.getLanguage() != null && !cf.getLanguage().isBlank()) {
                    ctx.languageByFile.put(path, cf.getLanguage());
                }
            }

            // finalize all files list in stable order
            ctx.allFiles.addAll(seenPaths);

            // 2) Build dependents (reverse index of taking)
            for (Map.Entry<String, Set<String>> e : ctx.takingByFile.entrySet()) {
                String src = e.getKey();
                for (String tgt : e.getValue()) {
                    ctx.dependentsByFile.computeIfAbsent(tgt, k -> new LinkedHashSet<>()).add(src);
                }
            }
        }


        return ctx;
    }

    // -------- Public accessors used by the evaluator --------

    public Map<String, Object> getFileContext(String filePath) {
        String np = normalizePath(filePath);
        Map<String, Object> ctx = new HashMap<>();

        // Basic file identity
        ctx.put("fileName", FileUtil.getFileName(np));
        ctx.put("language", languageByFile.getOrDefault(np, null));

        // Taking/Dependencies (alias for backward compatibility)
        Set<String> taking = getTaking(np);
        ctx.put("taking", taking);
        ctx.put("dependencies", taking);

        // Dependents (files that import this file)
        Set<String> dependents = getDependents(np);
        ctx.put("dependents", dependents);

        // Calling (exported/public symbols)
        Set<String> calling = getCalling(np);
        ctx.put("calling", calling);

        return ctx;
    }

    // Backward-compat convenience
    public Set<String> getDependencies(String filePath) {
        return getTaking(filePath);
    }

    public Set<String> getTaking(String filePath) {
        return takingByFile.getOrDefault(normalizePath(filePath), Collections.emptySet());
    }

    public Set<String> getDependents(String filePath) {
        return dependentsByFile.getOrDefault(normalizePath(filePath), Collections.emptySet());
    }

    public Set<String> getCalling(String filePath) {
        return callingByFile.getOrDefault(normalizePath(filePath), Collections.emptySet());
    }

    public String getLanguage(String filePath) {
        return languageByFile.get(normalizePath(filePath));
    }

    /**
     * Helper: return all repo-relative file paths known to the context.
     */
    public List<String> getAllFilePaths() {
        return Collections.unmodifiableList(allFiles);
    }

    // Kept for compatibility with earlier code that used this name.
    public List<String> getAllTrackedFiles() {
        return getAllFilePaths();
    }

    public int getApproximateFilePresenceCount() {
        return allFiles.size();
    }

    // Summaries are not populated in this DB-backed context.
    public Map<String, String> getFileSummaries() {
        return Map.of();
    }

    public int getFileCount() {
        return 0;
    }

    // -------- Debug/printing helpers --------


    @Override
    public String toString() {
        int edges = takingByFile.values().stream().mapToInt(Set::size).sum();
        return "EvaluationContext{files=" + allFiles.size()
                + ", edges=" + edges
                + ", withLanguages=" + languageByFile.size()
                + "}";
    }

    /**
     * Human-readable, multi-line dump with limits.
     */
    public String toPrettyString(int maxRows) {
        StringBuilder sb = new StringBuilder();
        int edges = takingByFile.values().stream().mapToInt(Set::size).sum();
        sb.append("EvaluationContext\n");
        sb.append("- files: ").append(allFiles.size()).append('\n');
        sb.append("- edges: ").append(edges).append('\n');
        sb.append("- languages: ").append(languageByFile.size()).append('\n');

        sb.append("\nFiles (first ").append(Math.min(maxRows, allFiles.size())).append("):\n");
        for (int i = 0; i < Math.min(maxRows, allFiles.size()); i++) {
            sb.append("  ").append(allFiles.get(i)).append('\n');
        }

        sb.append("\nTaking (first ").append(maxRows).append(" rows):\n");
        appendMapSample(sb, takingByFile, maxRows);

        sb.append("\nCalling (first ").append(maxRows).append(" rows):\n");
        appendMapSample(sb, callingByFile, maxRows);


        sb.append("\nDependents (first ").append(maxRows).append(" rows):\n");
        appendMapSample(sb, dependentsByFile, maxRows);

        return sb.toString();
    }

    /**
     * Snapshot as plain Java types (good for JSON serialization).
     */
    public Map<String, Object> debugSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("files", new ArrayList<>(allFiles));
        m.put("languageByFile", new LinkedHashMap<>(languageByFile));
        m.put("takingByFile", copyMapOfSets(takingByFile));
        m.put("callingByFile", copyMapOfSets(callingByFile));
        m.put("dependentsByFile", copyMapOfSets(dependentsByFile));
        return m;
    }

    private static Map<String, List<String>> copyMapOfSets(Map<String, Set<String>> src) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : src.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    private static void appendMapSample(StringBuilder sb, Map<String, ? extends Collection<String>> map, int maxRows) {
        int i = 0;
        for (Map.Entry<String, ? extends Collection<String>> e : map.entrySet()) {
            if (i++ >= maxRows) break;
            sb.append("  ").append(e.getKey()).append(" -> ").append(limitCollection(e.getValue(), 10)).append('\n');
        }
        if (map.isEmpty()) {
            sb.append("  (empty)\n");
        }
    }

    private static String limitCollection(Collection<?> c, int maxElems) {
        if (c == null || c.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Object o : c) {
            if (i++ > 0) sb.append(", ");
            if (i > maxElems) {
                sb.append("... (").append(c.size() - (i - 1)).append(" more)");
                break;
            }
            sb.append(Objects.toString(o));
        }
        sb.append("]");
        return sb.toString();
    }

    // -------- Helpers --------
    private static String normalizePath(String p) {
        if (p == null) return "";
        String n = p.replace('\\', '/').trim();
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        return n;
    }
}