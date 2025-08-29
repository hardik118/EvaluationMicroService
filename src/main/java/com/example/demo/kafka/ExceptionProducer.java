package com.example.demo.kafka;

import com.example.demo.model.EvaluationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExceptionProducer {
    private static final Logger logger = LoggerFactory.getLogger(FeedbackProducer.class);
    private static final String TOPIC = "evaluationFailureTopic";

    private final KafkaTemplate<String, String> kafkaTemplate;


    public ExceptionProducer(KafkaTemplate<String, String > kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void produceException(EvaluationRequest evaluationRequest, String failureMsg) {
        try {
            String key = evaluationRequest.getSubmissionId().toString();
            kafkaTemplate.send(TOPIC, key,  failureMsg);
            logger.info("Sent evaluation Exception  for submission {}", evaluationRequest.getSubmissionId());
        } catch (Exception e) {
            logger.error("Failed to send Exception to main service sending it to DLT : {}", e.getMessage(), e);
        }
    }
}
