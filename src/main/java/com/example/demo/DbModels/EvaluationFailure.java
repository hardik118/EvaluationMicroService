package com.example.demo.DbModels;

import com.example.demo.model.EvaluationRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationFailure {

    private EvaluationRequest originalRequest;
    private String errorMessage;

    // constructors, getters, setters
}
