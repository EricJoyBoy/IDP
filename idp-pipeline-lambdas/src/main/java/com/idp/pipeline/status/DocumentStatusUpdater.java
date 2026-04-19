package com.idp.pipeline.status;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that updates document status in DynamoDB.
 *
 * <p>Used by Step Functions for both {@code UpdateStatusCompleted} and
 * {@code UpdateStatusFailed} states. Writes the status and a processedTimestamp
 * to the Metadata_Store (DynamoDB) using the composite key {@code tenant_id#document_id}.
 *
 * <p>Input map keys: documentId (String), tenantId (String), status (String)
 * <p>Output map keys: all input keys + updatedAt (ISO-8601 timestamp)
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code DYNAMODB_TABLE} – name of the DynamoDB table</li>
 *   <li>{@code AWS_REGION} – AWS region (defaults to us-east-1)</li>
 * </ul>
 */
public class DocumentStatusUpdater implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(DocumentStatusUpdater.class.getName());

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public DocumentStatusUpdater() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(awsRegion)
                .build();
        this.tableName = System.getenv("DYNAMODB_TABLE");
    }

    /** Constructor for testing — allows injection of dependencies. */
    public DocumentStatusUpdater(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");
        String status = (String) input.get("status");

        if (documentId == null || tenantId == null || status == null) {
            throw new IllegalArgumentException(
                    "Input must contain documentId, tenantId, and status");
        }

        log.info(String.format("Updating status: documentId=%s tenantId=%s status=%s",
                documentId, tenantId, status));

        String partitionKey = tenantId + "#" + documentId;
        String updatedAt = Instant.now().toString();

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("pk", AttributeValue.builder().s(partitionKey).build());

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", AttributeValue.builder().s(status).build());
        expressionValues.put(":updatedAt", AttributeValue.builder().s(updatedAt).build());

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #st = :status, processedTimestamp = :updatedAt")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(expressionValues)
                .build();

        dynamoDbClient.updateItem(updateRequest);

        log.info(String.format("Status updated to %s for documentId=%s at %s",
                status, documentId, updatedAt));

        Map<String, Object> output = new HashMap<>(input);
        output.put("updatedAt", updatedAt);
        return output;
    }
}
