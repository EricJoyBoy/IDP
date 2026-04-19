package com.idp.pipeline.eventrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sends failed event messages to the Dead Letter Queue (SQS) with diagnostic metadata.
 */
public class DlqSender {

    private static final Logger log = Logger.getLogger(DlqSender.class.getName());

    private final SqsClient sqsClient;
    private final String dlqUrl;
    private final ObjectMapper objectMapper;

    public DlqSender(SqsClient sqsClient, String dlqUrl) {
        this.sqsClient = sqsClient;
        this.dlqUrl = dlqUrl;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Sends a failure message to the DLQ including diagnostic metadata.
     *
     * @param tenantId        tenant identifier
     * @param documentId      document identifier
     * @param s3Key           S3 object key
     * @param uploadTimestamp original upload timestamp
     * @param errorMessage    description of the failure
     */
    public void send(String tenantId, String documentId, String s3Key,
                     Instant uploadTimestamp, String errorMessage) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", documentId);
            body.put("tenantId", tenantId);
            body.put("s3Key", s3Key);
            body.put("uploadTimestamp", uploadTimestamp.toString());
            body.put("errorMessage", errorMessage);
            body.put("failedAt", Instant.now().toString());

            String messageBody = objectMapper.writeValueAsString(body);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .messageBody(messageBody)
                    .build();

            sqsClient.sendMessage(request);
            log.info("Sent failure message to DLQ for documentId=" + documentId);

        } catch (Exception e) {
            log.severe("Failed to send message to DLQ for documentId=" + documentId + ": " + e.getMessage());
            throw new RuntimeException("DLQ send failed", e);
        }
    }
}
