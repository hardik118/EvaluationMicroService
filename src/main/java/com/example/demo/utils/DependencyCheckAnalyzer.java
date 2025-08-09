package com.example.demo.utils;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.demo.model.DependencyInfo;
import com.example.demo.model.ToolResults;
import lombok.extern.slf4j.Slf4j;

/**
 * Analyzes dependencies between files in a repository
 */
@Slf4j
public class DependencyCheckAnalyzer implements DependencyAnalyzer {

    // Maps to store dependencies and dependents
    private final Map<String, List<DependencyInfo>> dependenciesBySource = new HashMap<>();
    private final Map<String, List<DependencyInfo>> dependentsByTarget = new HashMap<>();

    // Patterns for detecting dependencies in different languages
    private static final Map<String, Pattern> IMPORT_PATTERNS = Map.of(
            "java", Pattern.compile("import\\s+([\\w\\.]+)(?:\\.\\*)?;"),
            "python", Pattern.compile("(?:from\\s+([\\w\\.]+)\\s+import|import\\s+([\\w\\.]+))"),
            "javascript", Pattern.compile("(?:import\\s+.*?from\\s+['\"]([\\w\\./]+)['\"]|require\\s*\\(['\"]([\\w\\./]+)['\"]\\))")
    );

    @Override
    public ToolResults analyze(Path repoPath) {
        List<DependencyInfo> allDependencies = new ArrayList<>();

        try {
            // Find all files that could have dependencies
            try (Stream<Path> paths = Files.walk(repoPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(this::isSourceFile)
                        .forEach(file -> {
                            String relativePath = repoPath.relativize(file).toString();
                            analyzeDependenciesInFile(file, relativePath, repoPath, allDependencies);
                        });
            }

            // Organize dependencies by source and target
            for (DependencyInfo dependency : allDependencies) {
                // Add to source map
                dependenciesBySource.computeIfAbsent(
                        dependency.getSourceFile(), k -> new ArrayList<>()
                ).add(dependency);

                // Add to target map
                dependentsByTarget.computeIfAbsent(
                        dependency.getTargetFile(), k -> new ArrayList<>()
                ).add(dependency);
            }

            return new ToolResults(allDependencies);

        } catch (IOException e) {
            log.error("Error analyzing dependencies", e);
            return new ToolResults(Collections.emptyList());
        }
    }

    @Override
    public List<DependencyInfo> getDependenciesForFile(String filePath) {
        return dependenciesBySource.getOrDefault(filePath, Collections.emptyList());
    }

    @Override
    public List<DependencyInfo> getDependentsForFile(String filePath) {
        return dependentsByTarget.getOrDefault(filePath, Collections.emptyList());
    }

    /**
     * Analyze dependencies in a single file
     */
    private void analyzeDependenciesInFile(Path file, String relativePath, Path repoPath,
                                           List<DependencyInfo> allDependencies) {
        String fileExtension = FileUtil.getFileExtension(file.toString());
        Pattern importPattern = IMPORT_PATTERNS.get(fileExtension);

        if (importPattern == null) {
            return; // No pattern for this file type
        }

        try {
            String content = Files.readString(file);
            Matcher matcher = importPattern.matcher(content);

            while (matcher.find()) {
                // Get the imported package/module
                String importedPath = matcher.group(1);
                if (importedPath == null && matcher.groupCount() > 1) {
                    importedPath = matcher.group(2); // Alternative group in some patterns
                }

                if (importedPath != null) {
                    // Convert import statement to file path
                    String targetFilePath = resolveImportToFilePath(importedPath, fileExtension, repoPath);

                    if (targetFilePath != null) {
                        // Create dependency info
                        DependencyInfo dependency = new DependencyInfo(
                                relativePath,
                                targetFilePath,
                                "import",
                                true
                        );

                        allDependencies.add(dependency);
                    }
                }
            }

            // For Java files, also look for extends and implements
            if ("java".equals(fileExtension)) {
                analyzeJavaInheritance(content, relativePath, repoPath, allDependencies);
            }

        } catch (IOException e) {
            log.warn("Error reading file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Analyze Java inheritance relationships (extends, implements)
     */
    private void analyzeJavaInheritance(String content, String sourceFile, Path repoPath,
                                        List<DependencyInfo> allDependencies) {
        // Pattern for class/interface declaration with extends/implements
        Pattern classPattern = Pattern.compile(
                "class\\s+(\\w+)\\s+extends\\s+(\\w+)"
        );

        Pattern interfacePattern = Pattern.compile(
                "class\\s+(\\w+).*?implements\\s+([\\w,\\s]+)"
        );

        // Check for "extends" relationships
        Matcher extendsMatcher = classPattern.matcher(content);
        while (extendsMatcher.find()) {
            String parentClass = extendsMatcher.group(2);
            String targetPath = findFileInRepo(parentClass, repoPath);

            if (targetPath != null) {
                DependencyInfo dependency = new DependencyInfo(
                        sourceFile,
                        targetPath,
                        "extends",
                        true
                );

                allDependencies.add(dependency);
            }
        }

        // Check for "implements" relationships
        Matcher implementsMatcher = interfacePattern.matcher(content);
        while (implementsMatcher.find()) {
            String interfaceList = implementsMatcher.group(2);
            String[] interfaces = interfaceList.split(",\\s*");

            for (String interfaceName : interfaces) {
                String targetPath = findFileInRepo(interfaceName, repoPath);

                if (targetPath != null) {
                    DependencyInfo dependency = new DependencyInfo(
                            sourceFile,
                            targetPath,
                            "implements",
                            true
                    );

                    allDependencies.add(dependency);
                }
            }
        }
    }

    /**
     * Try to resolve an imported class/package to an actual file path
     */
    private String resolveImportToFilePath(String importedPath, String fileExtension, Path repoPath) {
        if ("java".equals(fileExtension)) {
            // Convert Java package to file path
            String filePath = importedPath.replace('.', '/') + ".java";
            if (Files.exists(repoPath.resolve(filePath))) {
                return filePath;
            }
        } else if ("python".equals(fileExtension)) {
            // Convert Python module to file path
            String filePath = importedPath.replace('.', '/') + ".py";
            if (Files.exists(repoPath.resolve(filePath))) {
                return filePath;
            }
            // Check for __init__.py in package
            String packagePath = importedPath.replace('.', '/') + "/__init__.py";
            if (Files.exists(repoPath.resolve(packagePath))) {
                return packagePath;
            }
        } else if ("javascript".equals(fileExtension)) {
            // Try various JavaScript file extensions
            for (String ext : List.of(".js", ".jsx", ".ts", ".tsx")) {
                String filePath = importedPath + ext;
                if (Files.exists(repoPath.resolve(filePath))) {
                    return filePath;
                }
            }
            // Check for index.js in directory
            String indexPath = importedPath + "/index.js";
            if (Files.exists(repoPath.resolve(indexPath))) {
                return indexPath;
            }
        }

        return null;
    }

    /**
     * Find a Java class file in the repository
     */
    private String findFileInRepo(String className, Path repoPath) {
        try {
            // Try to find the file in the repository
            try (Stream<Path> paths = Files.walk(repoPath)) {
                Optional<Path> found = paths
                        .filter(path -> path.getFileName().toString().equals(className + ".java"))
                        .findFirst();

                if (found.isPresent()) {
                    return repoPath.relativize(found.get()).toString();
                }
            }
        } catch (IOException e) {
            log.warn("Error searching for {}.java: {}", className, e.getMessage());
        }

        return null;
    }

    /**
     * Check if a file is a source code file we should analyze
     */
    private boolean isSourceFile(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return filename.endsWith(".java") ||
                filename.endsWith(".py") ||
                filename.endsWith(".js") ||
                filename.endsWith(".jsx") ||
                filename.endsWith(".ts") ||
                filename.endsWith(".tsx");
    }
}
