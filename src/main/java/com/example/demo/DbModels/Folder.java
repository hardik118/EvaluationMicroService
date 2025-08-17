package com.example.demo.DbModels;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"project", "files"})
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uq_folder_project_path", columnNames = {"project_id", "path"}),
        indexes = @Index(name = "idx_folder_project_path", columnList = "project_id,path")
)
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // Project this folder belongs to
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Project project;

    // Repo-relative normalized path for this folder.
    // Root folder uses empty string "".
    @Column(nullable = false)
    private String path;

    // Folder name (for root, you can use "" or ".")
    @Column(nullable = false)
    private String name;

    // Files directly in this folder
    @OneToMany(mappedBy = "folder", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<CodeFile> files = new ArrayList<>();
}