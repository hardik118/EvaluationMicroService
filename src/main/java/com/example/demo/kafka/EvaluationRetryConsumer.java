package com.example.demo.kafka;

import com.example.demo.model.EvaluationRequest;
import com.example.demo.service.RepositoryEvaluatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class EvaluationRetryConsumer {

    private final RepositoryEvaluatorService evaluationService;
    private  final ObjectMapper objectMapper;


    public  EvaluationRetryConsumer( RepositoryEvaluatorService evaluationService){
        this.evaluationService= evaluationService;
        this.objectMapper= new ObjectMapper();


    }
    @KafkaListener(topics = "kafkaRetryTopic", groupId = "evaluation-retry-consumer-group")
    public void retryConsume(String msg){

try {
    EvaluationRequest evaluationRequest= objectMapper.readValue(msg, EvaluationRequest.class);

    evaluationService.evaluateRepository(Path.of(evaluationRequest.getRepoUrl()), evaluationRequest.getSubmissionId());


}
catch (Exception e){
    System.err.println("The retry req failed , Evaluation  aborted : "+e.getMessage());

}
    }




}
