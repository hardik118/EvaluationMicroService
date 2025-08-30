package com.example.demo.DbModels;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
@Data
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class Project {

        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        private Long id;

        private Long submissionId;

        @Column(nullable = false)
        private  String Name;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String repoSummary;

}
