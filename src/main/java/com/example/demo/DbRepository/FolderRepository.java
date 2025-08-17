package com.example.demo.DbRepository;

import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FolderRepository extends JpaRepository<Folder, Long> {
    List<Folder> findByProject(Project project);
    Optional<Folder> findByProjectAndPath(Project project, String path); // path="" for root
    boolean existsByProjectAndPath(Project project, String path);
}