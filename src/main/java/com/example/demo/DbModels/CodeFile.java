package com.example.demo.DbModels;


import jakarta.persistence.*;
import lombok.*;

import java.util.*;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"project", "folder"})
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uq_codefile_project_path", columnNames = {"project_id", "path"}),
        indexes = @Index(name = "idx_codefile_project_path", columnList = "project_id,path")
)
public class CodeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // Project this file belongs to
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Project project;

    // Always set; root-level files use the project's root Folder (path = "")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(nullable = false)
    private Folder folder;

    // Repo-relative normalized path (e.g., "src/app/index.ts" or "README.md")
    @Column(nullable = false)
    private String path;

    // Optional basic metadata
    private String language;
    private Integer sizeBytes;
    private Integer lines;
    private String contentHash;

    // Optional small preview; avoid storing full contents here
    @Lob
    private String contentSnippet;

    // Optional per-file context you build for evaluation (keep it lightweight)
    @Lob
    private String contextJson;


    @ElementCollection
    @CollectionTable(name = "codefile_taking", joinColumns = @JoinColumn(name = "codefile_id"))
    @Column(name = "import_path")
    private Set<String> taking = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "codefile_calling", joinColumns = @JoinColumn(name = "codefile_id"))
    @Column(name = "export_symbol")
    private Set<String> calling = new LinkedHashSet<>();

}
