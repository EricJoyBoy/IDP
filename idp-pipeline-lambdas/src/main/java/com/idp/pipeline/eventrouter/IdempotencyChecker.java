package com.idp.pipeline.eventrouter;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Checks DynamoDB to determine whether a document already has an active
 * (PROCESSING or COMPLETED) execution, preventing duplicate pipeline runs.
 */
public class IdempotencyChecker {

    private static final Logger log = Logger.getLogger(IdempotencyChecker.class.getName());

    static final String STATUS_PROCESSING = "PROCESSING";
    static final String STATUS_COMPLETED = "COMPLETED";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public IdempotencyChecker(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /**
     * Returns {@code true} if DynamoDB already contains a record for the given
     * document with status PROCESSING or COMPLETED.
     *
     * @param tenantId   the tenant identifier
     * @param documentId the document identifier
     * @return true if an active execution exists, false otherwise
     */
    public boolean hasActiveExecution(String tenantId, String documentId) {
        String pk = tenantId + "#" + documentId;

        try {
            GetItemRequest request = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("pk", AttributeValue.fromS(pk)))
                    .projectionExpression("#s")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .build();

            GetItemResponse response = dynamoDb.getItem(request);

            if (!response.hasItem() || response.item().isEmpty()) {
                return false;
            }

            AttributeValue statusAttr = response.item().get("status");
            if (statusAttr == null) {
                return false;
            }

            String status = statusAttr.s();
            boolean active = STATUS_PROCESSING.equals(status) || STATUS_COMPLETED.equals(status);

            if (active) {
                log.info(String.format("Found active execution for pk=%s status=%s", pk, status));
            }

            return active;

        } catch (Exception e) {
            // On DynamoDB error, allow execution to proceed (fail-open) to avoid blocking the pipeline
            log.warning("DynamoDB idempotency check failed for pk=" + pk + ": " + e.getMessage()
                    + " — proceeding with execution");
            return false;
        }
    }
}
