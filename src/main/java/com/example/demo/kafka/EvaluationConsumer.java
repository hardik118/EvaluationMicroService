package com.example.demo.kafka;

import com.example.demo.model.EvaluationRequest;
import com.example.demo.service.RepositoryEvaluatorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Service
public class EvaluationConsumer {
    private static final Logger logger = LoggerFactory.getLogger(EvaluationConsumer.class);
    private final RepositoryEvaluatorService evaluationService;
    private  final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;


    public  EvaluationConsumer( RepositoryEvaluatorService evaluationService, KafkaTemplate<String, String> kafkaTemplate){
        this.evaluationService= evaluationService;
        this.kafkaTemplate=kafkaTemplate ;
        this.objectMapper= new ObjectMapper();

    }

    @KafkaListener(topics = "evaluation-requests", groupId = "evaluation-consumer-group")
    public void consume(String reqMessage){
        try{
            EvaluationRequest evaluationRequest= objectMapper.readValue(reqMessage, EvaluationRequest.class);
            logger.info("Received JSON message from Kafka: {}", evaluationRequest);

            evaluationService.evaluateRepositoryFromUrl(evaluationRequest.getRepoUrl(), evaluationRequest.getSubmissionId());

        } catch (Exception e) {
            System.err.println("Req is not process send to retry pipeline : "+e.getMessage());

            try{
                EvaluationRequest evaluationRequest= objectMapper.readValue(reqMessage, EvaluationRequest.class);

                logger.info("Request gone to retry pipeline : "+evaluationRequest);

                kafkaTemplate.send("kafkaRetryTopic", String.valueOf(evaluationRequest));

            }
            catch (JsonProcessingException exception){
                System.err.println("The msg req was not valid , Could not be processed"+ exception.getMessage());

            }
        }
    }


}
