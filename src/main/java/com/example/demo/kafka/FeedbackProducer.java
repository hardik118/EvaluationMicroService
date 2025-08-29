package com.example.demo.kafka;

import com.example.demo.model.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class FeedbackProducer {
    private static final Logger logger = LoggerFactory.getLogger(FeedbackProducer.class);
    private static final String TOPIC = "EvaluationFeedback";

    private final KafkaTemplate<String, EvaluationResult> kafkaTemplate;

    public FeedbackProducer(KafkaTemplate<String, EvaluationResult> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public boolean produceFeedback(EvaluationResult evaluationResult) {
        try {
            String key = evaluationResult.getSubmissionId().toString();
            kafkaTemplate.send(TOPIC, key, evaluationResult);
            logger.info("Sent evaluation feedback for submission {}", evaluationResult.getSubmissionId());
            return true;


        } catch (Exception e) {
            logger.error("Failed to send evaluation feedback to Kafka: {}", e.getMessage(), e);
            return false;
        }
    }
}