package com.example.demo.DbService.Impl;

import com.example.demo.DbModels.CodeFile;

import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import com.example.demo.DbRepository.CodeFileRepository;
import com.example.demo.DbRepository.FolderRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class ProjectStorageService {

    private final FolderRepository folderRepository;
    private final CodeFileRepository codeFileRepository;
    private final ProjectRepository projectRepository;


    /* ---------- Public API ---------- */

    @Transactional
    public Folder getOrCreateRootFolder(Project project) {
        return folderRepository.findByProjectAndPath(project, "")
                .orElseGet(() -> {
                    Folder root = new Folder();
                    root.setProject(project);
                    root.setPath("");
                    root.setName(".");
                    return folderRepository.save(root);
                });
    }

    @Transactional
    public Folder getOrCreateFolder(Project project, String folderPath) {
        String path = normalize(folderPath);
        if (path.isEmpty()) {
            return getOrCreateRootFolder(project);
        }
        return folderRepository.findByProjectAndPath(project, path)
                .orElseGet(() -> {
                    Folder f = new Folder();
                    f.setProject(project);
                    f.setPath(path);
                    String name = path;
                    int slash = path.lastIndexOf('/');
                    if (slash >= 0) name = path.substring(slash + 1);
                    f.setName(name.isEmpty() ? "." : name);
                    return folderRepository.save(f);
                });
    }

    // Optional: if you also set collections here, use the same in-place pattern.
    @Transactional
    public CodeFile saveOrUpdateFile(
            Project project,
            String filePath,
            String language,
            Integer sizeBytes,
            Integer lines,
            String contentHash,
            String contentSnippet,
            Collection<String> taking,
            Collection<String> calling
    ) {
        return upsertFile(project, filePath, file -> {
            file.setLanguage(language);
            file.setSizeBytes(sizeBytes);
            file.setLines(lines);
            file.setContentHash(contentHash);
            file.setContentSnippet(contentSnippet);

            // In-place updates for element collections
            if (file.getTaking() == null) {
                file.setTaking(new LinkedHashSet<>());
            } else {
                file.getTaking().clear();
            }
            if (taking != null) {
                for (String v : taking) {
                    if (v != null && !v.isBlank()) file.getTaking().add(v.trim());
                }
            }

            if (file.getCalling() == null) {
                file.setCalling(new LinkedHashSet<>());
            } else {
                file.getCalling().clear();
            }
            if (calling != null) {
                for (String v : calling) {
                    if (v != null && !v.isBlank()) file.getCalling().add(v.trim());
                }
            }
        });
    }

    @Transactional
    public CodeFile setFileContext(Project project, String filePath, String contextJson) {
        return upsertFile(project, filePath, file -> file.setContextJson(contextJson));
    }

    @Transactional
    public boolean addSummaryToProjectFiles(Long projectId, String repoRelPath, String summary) {
        Optional<Project> projectOpt = projectRepository.findBySubmissionId(projectId);
        if (projectOpt.isEmpty()) {
            return false;  // Project not found
        }

        Project project = projectOpt.get();

        Optional<CodeFile> fileOpt = codeFileRepository.findByProjectAndPath(project, repoRelPath);
        if (fileOpt.isEmpty()) {
            return false;  // File not found
        }

        setFileContext(project, repoRelPath, summary);
        return true;
    }

    public List<Folder> findAllFoldersByProjectId(Long projectId){
        List<Folder> folders =  folderRepository.findAllFoldersByProjectId(projectId);
        if(folders.isEmpty()){
            return Collections.emptyList();
        }
        return folders;


    }



    @Transactional(readOnly = true)
    public Map<String, String> getAllFileSummariesBySubmissionId(Long submissionId) {
        Project project = projectRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        List<FileSummaryProjection> summaries = codeFileRepository.findSummariesByProject(project);

        Map<String, String> summaryMap = new LinkedHashMap<>();
        for (FileSummaryProjection summary : summaries) {
            String content = summary.getContextJson();
            if (content != null && !content.isBlank()) {
                summaryMap.put(summary.getPath(), content);
            }
        }

        return summaryMap;
    }





    @Transactional(readOnly = true)
    public List<CodeFile> listFiles(Project project) {
        return codeFileRepository.findAllByProjectWithTakingAndCallingFetched(project);
    }

    public List<String > findAllFilespathsByFolderId(Long  folderId) {
        List<String> allPaths= codeFileRepository.findFilePathsByFolderId(folderId);
        if(allPaths.isEmpty()){
            return Collections.emptyList();
        }

        return allPaths;


    }


    /* ---------- Deduped helpers ---------- */

    @Transactional
    public CodeFile upsertFile(Project project, String filePath, Consumer<CodeFile> mutator) {
        CodeFile file = ensureCodeFile(project, filePath);
        mutator.accept(file);
        return codeFileRepository.save(file);
    }

    private CodeFile ensureCodeFile(Project project, String filePath) {
        String path = normalize(filePath);
        String folderPath = folderOf(path);
        Folder folder = getOrCreateFolder(project, folderPath);

        CodeFile file = codeFileRepository.findByProjectAndPath(project, path)
                .orElseGet(() -> {
                    CodeFile cf = new CodeFile();
                    cf.setProject(project);
                    cf.setPath(path);
                    return cf;
                });

        file.setFolder(folder);
        return file;
    }

    private String folderOf(String filePath) {
        String p = normalize(filePath);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(0, i) : "";
    }

    private String normalize(String p) {
        if (p == null) return "";
        String n = p.replace('\\', '/').trim();
        if (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.endsWith("/") && n.length() > 1) n = n.substring(0, n.length() - 1);
        return n;
    }

    private Set<String> toSet(Collection<String> src) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (src == null) return out;
        for (String s : src) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }
}