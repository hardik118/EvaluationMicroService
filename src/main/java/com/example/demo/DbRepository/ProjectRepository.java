package com.example.demo.DbRepository;


import com.example.demo.DbModels.Folder;
import com.example.demo.DbModels.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findBySubmissionId(Long submissionId);

}