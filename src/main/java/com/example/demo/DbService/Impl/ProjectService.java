package com.example.demo.DbService.Impl;

import com.example.demo.DbModels.Project;
import com.example.demo.DbRepository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public Optional<Project> findById(Long id) {
        return projectRepository.findById(id);
    }
}