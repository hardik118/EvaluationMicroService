package com.example.demo.utils;

import com.example.demo.DbModels.Project;
import com.example.demo.DbService.Impl.ProjectStorageService;
import com.example.demo.model.DependencyInfo;
import com.example.demo.model.ToolResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dependency analyzer that:
 * - Discovers dependencies (edges) and exports per file
 * - Persists taking (imports) and calling (exports) to DB for each CodeFile
 * - Still returns in-memory ToolResults and maps for evaluation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyCheckAnalyzer {

    private final ProjectStorageService storage;

    private final Map<String, List<DependencyInfo>> dependenciesBySource = new HashMap<>();
    private final Map<String, List<DependencyInfo>> dependentsByTarget = new HashMap<>();

    // Import patterns
    private static final Pattern JAVA_IMPORT = Pattern.compile("import\\s+([\\w\\.]+)(?:\\.\\*)?;");
    private static final Pattern PY_IMPORT = Pattern.compile("(?:from\\s+([\\w\\.]+)\\s+import|import\\s+([\\w\\.]+))");
    private static final Pattern JS_IMPORT = Pattern.compile(
            "(?:import\\s+.*?from\\s+['\"]([^'\"\\s]+)['\"]" +      // group 1
                    "|import\\s*\\(\\s*['\"]([^'\"\\s]+)['\"]\\s*\\)" +     // group 2
                    "|require\\s*\\(\\s*['\"]([^'\"\\s]+)['\"]\\s*\\)" +    // group 3
                    "|import\\s+['\"]([^'\"\\s]+)['\"])");                  // group 4 (side-effect)
    private static final Set<String> JS_LIKE_EXTS = Set.of("js","jsx","ts","tsx","mjs","cjs","javascript");

    // Export patterns (JS/TS)
    private static final Pattern JS_EXPORT_DEFAULT_FUNC = Pattern.compile("export\\s+default\\s+function(?:\\s+(\\w+))?");
    private static final Pattern JS_EXPORT_DEFAULT_CLASS = Pattern.compile("export\\s+default\\s+class(?:\\s+(\\w+))?");
    private static final Pattern JS_EXPORT_NAMED_FUNC = Pattern.compile("export\\s+function\\s+(\\w+)");
    private static final Pattern JS_EXPORT_NAMED_CLASS = Pattern.compile("export\\s+class\\s+(\\w+)");
    private static final Pattern JS_EXPORT_VAR = Pattern.compile("export\\s+(?:const|let|var)\\s+(\\w+)");
    private static final Pattern JS_EXPORT_LIST = Pattern.compile("export\\s*\\{([^}]+)\\}");
    private static final Pattern JS_EXPORTS_DOT = Pattern.compile("exports\\.(\\w+)\\s*=");
    private static final Pattern JS_MODULE_EXPORTS_OBJ = Pattern.compile("module\\.exports\\s*=\\s*\\{([^}]+)\\}");
    private static final Pattern JS_MODULE_EXPORTS_ANY = Pattern.compile("module\\.exports\\s*=\\s*(\\w+)");

    // Export patterns (Java)
    private static final Pattern JAVA_PUBLIC_TYPE = Pattern.compile("public\\s+(?:class|interface|enum)\\s+(\\w+)");

    // Export patterns (Python) - simple heuristics
    private static final Pattern PY_DEF = Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PY_ALL = Pattern.compile("__all__\\s*=\\s*\\[([^\\]]+)\\]");

    /**
     * Legacy in-memory only analysis (no DB updates).
     */
    public ToolResults analyze(Path repoPath) {
        return analyze(null, repoPath);
    }

    /**
     * Analyze repository and persist per-file taking/calling to DB if project is provided.
     */
    public ToolResults analyze(Project project, Path repoPath) {
        dependenciesBySource.clear();
        dependentsByTarget.clear();

        List<DependencyInfo> allDependencies = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isSourceFile)
                    .forEach(file -> {
                        String rel = repoPath.relativize(file).toString().replace('\\', '/');
                        analyzeAndPersistFile(project, repoPath, file, rel, allDependencies);
                    });
        } catch (IOException e) {
            log.error("Error analyzing dependencies", e);
            return new ToolResults(Collections.emptyList());
        }

        for (DependencyInfo dep : allDependencies) {
            dependenciesBySource
                    .computeIfAbsent(normalize(dep.getSourceFile()), k -> new ArrayList<>())
                    .add(dep);
            dependentsByTarget
                    .computeIfAbsent(normalize(dep.getTargetFile()), k -> new ArrayList<>())
                    .add(dep);
        }

        int edgeCount = dependenciesBySource.values().stream().mapToInt(List::size).sum();
        log.info("DependencyCheckAnalyzer discovered {} dependency edges", edgeCount);

        return new ToolResults(allDependencies);
    }

    private void analyzeAndPersistFile(Project project,
                                       Path repoPath,
                                       Path absFile,
                                       String repoRelPath,
                                       List<DependencyInfo> allDependencies) {
        String ext = FileUtil.getFileExtension(absFile.toString()).toLowerCase(Locale.ROOT);
        Path baseDir = absFile.getParent();

        String content;
        try {
            content = Files.readString(absFile);
        } catch (IOException e) {
            log.warn("Error reading {}: {}", absFile, e.getMessage());
            return;
        }

        // Compute taking (imported targets resolved to repo-relative paths)
        List<String> taking = extractTaking(content, ext, repoPath, baseDir);

        // Add dependency edges for taking
        for (String target : taking) {
            allDependencies.add(new DependencyInfo(repoRelPath, target, "import", true));
        }

        // Compute calling (exported/public symbols)
        List<String> calling = extractCalling(content, ext);

        // Persist only taking/calling if we have a project
        // ... inside analyzeAndPersistFile(...) where you persist taking/calling:
        if (project != null) {
            try {
                // prepare deduped, ordered sets
                Set<String> newTaking = new LinkedHashSet<>(taking);   // 'taking' is a List<String>
                Set<String> newCalling = new LinkedHashSet<>(calling); // 'calling' is a List<String>

                storage.upsertFile(project, repoRelPath, cf -> {
                    // MUTATE IN-PLACE to keep Hibernate's PersistentSet happy
                    if (cf.getTaking() == null) {
                        cf.setTaking(new LinkedHashSet<>());
                    } else {
                        cf.getTaking().clear();
                    }
                    cf.getTaking().addAll(newTaking);

                    if (cf.getCalling() == null) {
                        cf.setCalling(new LinkedHashSet<>());
                    } else {
                        cf.getCalling().clear();
                    }
                    cf.getCalling().addAll(newCalling);
                });
            } catch (Exception e) {
                log.warn("Failed to update taking/calling for {}: {}", repoRelPath, e.getMessage());
            }
        }


    }

    private List<String> extractTaking(String content, String ext, Path repoPath, Path baseDir) {
        List<String> taking = new ArrayList<>();
        Pattern importPattern = patternForExt(ext);
        if (importPattern == null) return taking;

        Matcher matcher = importPattern.matcher(content);
        while (matcher.find()) {
            String importedPath = firstNonNull(
                    safeGroup(matcher, 1),
                    safeGroup(matcher, 2),
                    safeGroup(matcher, 3),
                    safeGroup(matcher, 4)
            );
            if (importedPath == null || importedPath.isBlank()) continue;

            String targetFilePath = resolveImportToFilePath(importedPath, ext, repoPath, baseDir);
            if (targetFilePath != null) {
                taking.add(targetFilePath);
            }
        }
        // dedupe and keep order
        LinkedHashSet<String> set = new LinkedHashSet<>(taking);
        return new ArrayList<>(set);
    }

    private List<String> extractCalling(String content, String ext) {
        List<String> calling = new ArrayList<>();
        if (JS_LIKE_EXTS.contains(ext)) {
            // JS/TS/JSX/TSX
            addAllGroups(calling, JS_EXPORT_DEFAULT_FUNC.matcher(content), 1, "default");
            addAllGroups(calling, JS_EXPORT_DEFAULT_CLASS.matcher(content), 1, "default");
            addAllGroups(calling, JS_EXPORT_NAMED_FUNC.matcher(content), 1, null);
            addAllGroups(calling, JS_EXPORT_NAMED_CLASS.matcher(content), 1, null);
            addAllGroups(calling, JS_EXPORT_VAR.matcher(content), 1, null);

            Matcher list = JS_EXPORT_LIST.matcher(content);
            while (list.find()) {
                String body = list.group(1); // e.g., "a, b as c"
                if (body != null) {
                    for (String part : body.split(",")) {
                        String tok = part.trim();
                        if (tok.isEmpty()) continue;
                        // handle "name as alias"
                        String[] asParts = tok.split("\\s+as\\s+");
                        String symbol = asParts.length == 2 ? asParts[1].trim() : asParts[0].trim();
                        // strip trailing punctuation
                        symbol = symbol.replaceAll("[^\\w$]", "");
                        if (!symbol.isEmpty()) calling.add(symbol);
                    }
                }
            }

            addAllGroups(calling, JS_EXPORTS_DOT.matcher(content), 1, null);

            Matcher modObj = JS_MODULE_EXPORTS_OBJ.matcher(content);
            while (modObj.find()) {
                String body = modObj.group(1); // keys in object
                if (body != null) {
                    for (String part : body.split(",")) {
                        String key = part.split(":")[0].trim();
                        key = key.replaceAll("[^\\w$]", "");
                        if (!key.isEmpty()) calling.add(key);
                    }
                }
            }

            addAllGroups(calling, JS_MODULE_EXPORTS_ANY.matcher(content), 1, "default");

            // TS interfaces/types/enums
            calling.addAll(matchAll(content, Pattern.compile("export\\s+interface\\s+(\\w+)"), 1));
            calling.addAll(matchAll(content, Pattern.compile("export\\s+type\\s+(\\w+)"), 1));
            calling.addAll(matchAll(content, Pattern.compile("export\\s+enum\\s+(\\w+)"), 1));

        } else if ("java".equals(ext)) {
            calling.addAll(matchAll(content, JAVA_PUBLIC_TYPE, 1));
        } else if ("py".equals(ext) || "python".equals(ext)) {
            // __all__ wins if present
            Matcher all = PY_ALL.matcher(content);
            if (all.find()) {
                String body = all.group(1);
                if (body != null) {
                    for (String part : body.split(",")) {
                        String sym = part.replaceAll("['\"\\s]", "");
                        if (!sym.isEmpty()) calling.add(sym);
                    }
                }
            } else {
                // otherwise, expose top-level defs and classes
                calling.addAll(matchAll(content, PY_DEF, 1));
                calling.addAll(matchAll(content, PY_CLASS, 1));
            }
        }

        // dedupe, keep order
        LinkedHashSet<String> set = new LinkedHashSet<>(calling);
        return new ArrayList<>(set);
    }

    private void addAllGroups(List<String> out, Matcher m, int group, String defaultIfMissing) {
        while (m.find()) {
            String g = safeGroup(m, group);
            String sym = (g == null || g.isBlank()) ? defaultIfMissing : g;
            if (sym != null && !sym.isBlank()) {
                out.add(sym.trim());
            }
        }
    }

    private List<String> matchAll(String content, Pattern p, int group) {
        List<String> res = new ArrayList<>();
        Matcher m = p.matcher(content);
        while (m.find()) {
            String g = safeGroup(m, group);
            if (g != null && !g.isBlank()) res.add(g.trim());
        }
        return res;
    }

    private Pattern patternForExt(String ext) {
        if ("java".equals(ext)) return JAVA_IMPORT;
        if ("py".equals(ext) || "python".equals(ext)) return PY_IMPORT;
        if (JS_LIKE_EXTS.contains(ext)) return JS_IMPORT;
        return null;
    }

    /**
     * Resolve imports relative to importing file for JS/TS; basic absolute for Java/Python.
     */
    private String resolveImportToFilePath(String importedPath, String sourceFileExt, Path repoPath, Path baseDir) {
        String norm = importedPath.replace('\\', '/');

        // Java
        if ("java".equals(sourceFileExt)) {
            String filePath = norm.replace('.', '/') + ".java";
            if (Files.exists(repoPath.resolve(filePath))) return filePath;
            return null;
        }

        // Python (simplified)
        if ("py".equals(sourceFileExt) || "python".equals(sourceFileExt)) {
            if (norm.startsWith(".")) {
                String stripped = norm.replaceFirst("^\\.+", "");
                if (stripped.isBlank() || baseDir == null) return null;
                Path rel = baseDir.resolve(stripped.replace('.', '/'));
                Path c1 = rel.resolveSibling(rel.getFileName() + ".py");
                Path c2 = rel.resolve("__init__.py");
                if (existsWithinRepo(repoPath, c1)) return toRepoRel(repoPath, c1);
                if (existsWithinRepo(repoPath, c2)) return toRepoRel(repoPath, c2);
                return null;
            } else {
                String filePath = norm.replace('.', '/') + ".py";
                if (Files.exists(repoPath.resolve(filePath))) return filePath;
                String packagePath = norm.replace('.', '/') + "/__init__.py";
                if (Files.exists(repoPath.resolve(packagePath))) return packagePath;
                return null;
            }
        }

        // JS/TS and variants
        if (JS_LIKE_EXTS.contains(sourceFileExt)) {
            // external libs (react, express) => ignore
            if (!(norm.startsWith("./") || norm.startsWith("../") || norm.startsWith("/"))) return null;
            if (baseDir == null) return null;

            Path start = norm.startsWith("/") ? repoPath.resolve(norm.substring(1)) : baseDir.resolve(norm);

            // explicit extension
            if (hasKnownExplicitExtension(norm)) {
                Path p = normalizeWithinRepo(repoPath, start);
                if (p != null && Files.exists(p)) return toRepoRel(repoPath, p);
            }

            // try common extensions
            String[] exts = new String[] { ".js", ".jsx", ".ts", ".tsx", ".json", ".css" };
            for (String ext : exts) {
                Path cand = withExtension(start, ext);
                Path p = normalizeWithinRepo(repoPath, cand);
                if (p != null && Files.exists(p)) return toRepoRel(repoPath, p);
            }

            // directory index files
            Path asDir = normalizeWithinRepo(repoPath, start);
            if (asDir != null && Files.isDirectory(asDir)) {
                String[] indexNames = new String[] { "index.js", "index.jsx", "index.ts", "index.tsx", "index.css" };
                for (String idx : indexNames) {
                    Path idxPath = asDir.resolve(idx);
                    if (Files.exists(idxPath)) return toRepoRel(repoPath, idxPath);
                }
            }
            return null;
        }

        return null;
    }

    private static Path withExtension(Path base, String ext) {
        Path parent = base.getParent();
        String name = base.getFileName().toString();
        return parent != null ? parent.resolve(name + ext) : Path.of(name + ext);
    }

    private static boolean hasKnownExplicitExtension(String p) {
        String lower = p.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts")
                || lower.endsWith(".tsx") || lower.endsWith(".json") || lower.endsWith(".css");
    }

    private static Path normalizeWithinRepo(Path repoRoot, Path candidate) {
        try {
            Path absRepo = repoRoot.toAbsolutePath().normalize();
            Path absCand = candidate.toAbsolutePath().normalize();
            if (!absCand.startsWith(absRepo)) {
                return absRepo.resolve(repoRoot.relativize(candidate)).normalize();
            }
            return absCand;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean existsWithinRepo(Path repoRoot, Path candidate) {
        Path p = normalizeWithinRepo(repoRoot, candidate);
        return p != null && Files.exists(p);
    }

    private static String toRepoRel(Path repoRoot, Path absolute) {
        return repoRoot.relativize(absolute).toString().replace('\\', '/');
    }

    private boolean isSourceFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".java")
                || filename.endsWith(".py")
                || filename.endsWith(".js")
                || filename.endsWith(".jsx")
                || filename.endsWith(".ts")
                || filename.endsWith(".tsx")
                || filename.endsWith(".mjs")
                || filename.endsWith(".cjs");
    }

    private static String normalize(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }

    private static String safeGroup(Matcher m, int i) {
        return i <= m.groupCount() ? m.group(i) : null;
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return null;
    }

    /* --------- Introspection helpers for other components --------- */

    public Map<String, Collection<String>> getDependenciesMap() {
        Map<String, Set<String>> edges = new HashMap<>();
        for (Map.Entry<String, List<DependencyInfo>> e : dependenciesBySource.entrySet()) {
            String src = normalize(e.getKey());
            Set<String> targets = edges.computeIfAbsent(src, k -> new HashSet<>());
            for (DependencyInfo d : e.getValue()) {
                targets.add(normalize(d.getTargetFile()));
            }
        }
        Map<String, Collection<String>> out = new HashMap<>();
        edges.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(v)));
        return Collections.unmodifiableMap(out);
    }

    public List<DependencyInfo> getDependenciesForFile(String filePath) {
        return dependenciesBySource.getOrDefault(normalize(filePath), Collections.emptyList());
    }

    public List<DependencyInfo> getDependentsForFile(String filePath) {
        return dependentsByTarget.getOrDefault(normalize(filePath), Collections.emptyList());
    }
}