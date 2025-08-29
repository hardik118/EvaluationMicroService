package com.example.demo.kafka;

import aj.org.objectweb.asm.commons.TryCatchBlockSorter;
import com.example.demo.DbModels.EvaluationFailure;
import com.example.demo.model.EvaluationRequest;
import com.example.demo.service.RepositoryEvaluatorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class EvaluationRetryConsumer {
    private static final Logger logger = LoggerFactory.getLogger(EvaluationRetryConsumer.class);

    private final RepositoryEvaluatorService evaluationService;
    private  final ObjectMapper objectMapper;
    private  final  ExceptionProducer exceptionProducer;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public  EvaluationRetryConsumer(RepositoryEvaluatorService evaluationService, ExceptionProducer exceptionProducer, KafkaTemplate<String, String> kafkaTemplate){
        this.evaluationService= evaluationService;
        this.exceptionProducer = exceptionProducer;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper= new ObjectMapper();


    }
    @KafkaListener(topics = "kafkaRetryTopic", groupId = "evaluation-retry-consumer-group")
    public void retryConsume(String msg){

try {
    EvaluationRequest evaluationRequest= objectMapper.readValue(msg, EvaluationRequest.class);
    logger.info("Request is now being processed in retry pipeline : {}", evaluationRequest);

    evaluationService.evaluateRepository(Path.of(evaluationRequest.getRepoUrl()), evaluationRequest.getSubmissionId(), evaluationRequest.getDescription());


}
catch (Exception e){


    System.err.println("The retry req failed , Evaluation  aborted : "+e.getMessage());

    try {
        EvaluationRequest evaluationRequest= objectMapper.readValue(msg, EvaluationRequest.class);

        // Create failure DTO and populate it
        EvaluationFailure failure = new EvaluationFailure();
        failure.setOriginalRequest(evaluationRequest);
        failure.setErrorMessage(e.getMessage());

        // Serialize failure DTO to JSON string
        String failureMsg = objectMapper.writeValueAsString(failure);

        // Send failure message to exception topic
        exceptionProducer.produceException(evaluationRequest, failureMsg);

        System.out.println("Sent failure info to evaluationFailureTopic");

    }
    catch (JsonProcessingException jsonEx) {
        System.err.println("Failed to serialize failure message: " + jsonEx.getMessage());
        // Optionally handle this scenario (e.g. send to dead-letter topic)
    }

}
    }




}
