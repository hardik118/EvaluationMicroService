package com.example.demo.DbService.Impl;

import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import com.example.demo.DbRepository.FolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectStructureService {
    private final FolderRepository folderRepository;

    // Ensure a root folder (path="") exists for the project
    @Transactional
    public Folder getOrCreateRootFolder(Project project) {
        return folderRepository.findByProjectAndPath(project, "")
                .orElseGet(() -> {
                    Folder root = new Folder();
                    root.setProject(project);
                    root.setPath("");    // root path
                    root.setName("");    // or "."
                    return folderRepository.save(root);
                });
    }
}