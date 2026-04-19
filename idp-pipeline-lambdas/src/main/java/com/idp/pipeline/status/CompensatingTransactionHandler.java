package com.idp.pipeline.status;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that executes Saga compensating transactions on irreversible pipeline failure.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Marks the document as {@code FAILED} in DynamoDB (Metadata_Store)</li>
 *   <li>Sends a DLQ notification with diagnostic metadata (documentId, tenantId, error, timestamp)</li>
 * </ol>
 *
 * <p>Input map keys: documentId, tenantId, s3Key, uploadTimestamp, error (optional)
 * <p>Output map keys: all input keys + failedAt (ISO-8601 timestamp)
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code DYNAMODB_TABLE} – name of the DynamoDB table</li>
 *   <li>{@code DLQ_URL} – SQS DLQ URL for failure notifications</li>
 *   <li>{@code AWS_REGION} – AWS region (defaults to us-east-1)</li>
 * </ul>
 */
public class CompensatingTransactionHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(CompensatingTransactionHandler.class.getName());

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;
    private final String tableName;
    private final String dlqUrl;
    private final ObjectMapper objectMapper;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public CompensatingTransactionHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;
        this.dynamoDbClient = DynamoDbClient.builder().region(awsRegion).build();
        this.sqsClient = SqsClient.builder().region(awsRegion).build();
        this.tableName = System.getenv("DYNAMODB_TABLE");
        this.dlqUrl = System.getenv("DLQ_URL");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** Constructor for testing — allows injection of dependencies. */
    public CompensatingTransactionHandler(DynamoDbClient dynamoDbClient, SqsClient sqsClient,
                                          String tableName, String dlqUrl) {
        this.dynamoDbClient = dynamoDbClient;
        this.sqsClient = sqsClient;
        this.tableName = tableName;
        this.dlqUrl = dlqUrl;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");
        String s3Key = (String) input.get("s3Key");
        String uploadTimestamp = (String) input.get("uploadTimestamp");
        Object errorObj = input.get("error");
        String errorMessage = errorObj != null ? errorObj.toString() : "Unknown pipeline failure";

        log.warning(String.format(
                "Executing compensating transaction: documentId=%s tenantId=%s error=%s",
                documentId, tenantId, errorMessage));

        String failedAt = Instant.now().toString();

        // 1. Mark document as FAILED in DynamoDB
        markDocumentFailed(tenantId, documentId, errorMessage, failedAt);

        // 2. Send DLQ notification
        sendDlqNotification(documentId, tenantId, s3Key, uploadTimestamp, errorMessage, failedAt);

        Map<String, Object> output = new HashMap<>(input);
        output.put("failedAt", failedAt);
        return output;
    }

    private void markDocumentFailed(String tenantId, String documentId,
                                    String errorMessage, String failedAt) {
        String partitionKey = tenantId + "#" + documentId;

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("pk", AttributeValue.builder().s(partitionKey).build());

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", AttributeValue.builder().s("FAILED").build());
        expressionValues.put(":failedAt", AttributeValue.builder().s(failedAt).build());
        expressionValues.put(":errorMessage", AttributeValue.builder().s(errorMessage).build());

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #st = :status, processedTimestamp = :failedAt, errorMessage = :errorMessage")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(expressionValues)
                .build();

        dynamoDbClient.updateItem(updateRequest);
        log.info("Marked document FAILED in DynamoDB: documentId=" + documentId);
    }

    private void sendDlqNotification(String documentId, String tenantId, String s3Key,
                                     String uploadTimestamp, String errorMessage, String failedAt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", documentId);
            body.put("tenantId", tenantId);
            body.put("s3Key", s3Key);
            body.put("uploadTimestamp", uploadTimestamp);
            body.put("errorMessage", errorMessage);
            body.put("failedAt", failedAt);

            String messageBody = objectMapper.writeValueAsString(body);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .messageBody(messageBody)
                    .build();

            sqsClient.sendMessage(request);
            log.info("Sent DLQ notification for documentId=" + documentId);

        } catch (Exception e) {
            log.severe("Failed to send DLQ notification for documentId=" + documentId
                    + ": " + e.getMessage());
            throw new RuntimeException("DLQ notification failed for documentId=" + documentId, e);
        }
    }
}
