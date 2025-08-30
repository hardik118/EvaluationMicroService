package com.example.demo.kafka;

import com.example.demo.DbRepository.ProjectRepository;
import com.example.demo.DbService.Impl.ProjectService;
import com.example.demo.model.EvaluationRequest;
import com.example.demo.service.RepositoryEvaluatorService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Service
public class EvaluationConsumer {
    private static final Logger logger = LoggerFactory.getLogger(EvaluationConsumer.class);
    private final RepositoryEvaluatorService evaluationService;
    private  final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ProjectService projectService;




    public  EvaluationConsumer( RepositoryEvaluatorService evaluationService, KafkaTemplate<String, String> kafkaTemplate, ProjectService projectService){
        this.evaluationService= evaluationService;
        this.kafkaTemplate=kafkaTemplate ;
        this.objectMapper= new ObjectMapper();
        this.projectService = projectService;


    }

    @KafkaListener(topics = "evaluation-requests", groupId = "evaluation-consumer-group")
    public void consume(String reqMessage){
        try{
            EvaluationRequest evaluationRequest= objectMapper.readValue(reqMessage, EvaluationRequest.class);
            logger.info("Received JSON message from Kafka: {}", evaluationRequest);
            projectService.create(evaluationRequest.getTitle(), evaluationRequest.getSubmissionId());
            evaluationService.evaluateRepositoryFromUrl(evaluationRequest.getRepoUrl(), evaluationRequest.getSubmissionId(), evaluationRequest.getDescription());


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
