package com.example.demo.kafka;

import com.example.demo.model.EvaluationRequest;
import com.example.demo.model.EvaluationResult;
import com.example.demo.service.RepositoryEvaluatorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class EvaluationConsumer {
    private final RepositoryEvaluatorService evaluationService;
    private  final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;


    public  EvaluationConsumer( RepositoryEvaluatorService evaluationService, KafkaTemplate<String, String> kafkaTemplate){
        this.evaluationService= evaluationService;
        this.kafkaTemplate=kafkaTemplate ;
        this.objectMapper= new ObjectMapper();

    }

    @KafkaListener(topics = "evaluationTopic", groupId = "evaluation-consumer-group")
    public void consume(String reqMessage){
        try{
            EvaluationRequest evaluationRequest= objectMapper.readValue(reqMessage, EvaluationRequest.class);

            evaluationService.evaluateRepository(Path.of(evaluationRequest.getRepoUrl()), evaluationRequest.getSubmissionId());

        } catch (Exception e) {
            System.err.println("Req is not process send to retry pipeline : "+e.getMessage());

            try{
                EvaluationRequest evaluationRequest= objectMapper.readValue(reqMessage, EvaluationRequest.class);


                kafkaTemplate.send("kafkaRetryTopic", String.valueOf(evaluationRequest));

            }
            catch (JsonProcessingException exception){
                System.err.println("The msg req was not valid , Could not be processed"+ exception.getMessage());

            }
        }
    }


}
