package com.example.demo.DbRepository;

import com.example.demo.DbModels.CodeFile;
import com.example.demo.DbModels.Dto.FileSummaryProjection;
import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {
    List<CodeFile> findByProject(Project project);
    List<CodeFile> findAllByProject(Project project);

    Optional<CodeFile> findByProjectAndPath(Project project, String path);
    @Query("SELECT c.path FROM CodeFile c WHERE c.folder.id = :folderId")
    List<String> findFilePathsByFolderId(@Param("folderId") Long folderId);
    boolean existsByProjectAndPath(Project project, String path);
    @Query("SELECT DISTINCT cf FROM CodeFile cf " +
            "LEFT JOIN FETCH cf.taking " +
            "LEFT JOIN FETCH cf.calling " +
            "WHERE cf.project = :project " +
            "ORDER BY cf.path ASC")
    List<CodeFile> findAllByProjectWithTakingAndCallingFetched(@Param("project") Project project);
    @Query("SELECT f.path AS path, f.contextJson AS contextJson FROM CodeFile f WHERE f.project = :project")
    List<FileSummaryProjection> findSummariesByProject(@Param("project") Project project);



}