package com.example.demo.kafka;

import com.example.demo.model.EvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Produces evaluation feedback messages to Kafka
 */
@Slf4j
@Component
public class FeedbackProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private static final String TOPIC = "EvaluationFeedback";

    @Autowired
    public FeedbackProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Produce feedback message with evaluation results
     *
     * @param evaluationResult The evaluation result to send
     * @return true if successfully sent, false otherwise
     */
    public boolean produceFeedback(EvaluationResult evaluationResult) {
        try {
            // Convert evaluation result to JSON
            String jsonPayload = objectMapper.writeValueAsString(evaluationResult);

            // Use submission ID as the message key for partitioning
            String key = evaluationResult.getSubmissionId().toString();

            // Send message to Kafka
            kafkaTemplate.send(TOPIC, key, jsonPayload);
            log.info("Sent evaluation feedback for submission {}", evaluationResult.getSubmissionId());

            return true;
        } catch (Exception e) {
            log.error("Failed to send evaluation feedback to Kafka: {}", e.getMessage(), e);
            return false;
        }
    }
}