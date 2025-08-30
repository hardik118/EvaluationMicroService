package com.example.demo.DbService.Impl;

import com.example.demo.DbModels.Project;
import com.example.demo.DbRepository.ProjectRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    public Project create(String name, Long submissionId) {
        Project p = new Project();
        p.setName(name);
        p.setSubmissionId(submissionId);
        return projectRepository.save(p);
    }

    public Optional<Project> findBySubmissionId(Long submissionId) {
        return projectRepository.findBySubmissionId(submissionId);
    }




    @Transactional
    public void saveRepoSummary(Long submissionId, String repoSummary) {
        Optional<Project> projectOpt = projectRepository.findBySubmissionId(submissionId);
        if (projectOpt.isPresent()) {
            Project project = projectOpt.get();
            project.setRepoSummary(repoSummary);
            projectRepository.save(project);
        } else {
            throw new IllegalArgumentException("Project not found with submissionId: " + submissionId);
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> getRepoSummary(Long submissionId) {
        return projectRepository.findBySubmissionId(submissionId)
                .map(Project::getRepoSummary);
    }


