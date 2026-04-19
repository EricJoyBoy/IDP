package com.idp.pipeline.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.DocumentStatus;
import com.idp.common.serialization.DocumentObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Writes the full document record to DynamoDB.
 *
 * <p>Primary key: {@code pk = tenant_id#document_id}
 * <p>GSI attributes:
 * <ul>
 *   <li>GSI1: {@code tenant_id} + {@code status} — query by status</li>
 *   <li>GSI2: {@code tenant_id} + {@code document_type} + {@code upload_date} — query by type/date</li>
 * </ul>
 */
public class DynamoDbPersistenceService {

    private static final Logger log = Logger.getLogger(DynamoDbPersistenceService.class.getName());

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final ObjectMapper objectMapper;

    public DynamoDbPersistenceService(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.objectMapper = DocumentObjectMapper.getInstance();
    }

    /**
     * Writes the full document record to DynamoDB, including all pipeline fields and GSI attributes.
     *
     * @param input the pipeline input map containing all document fields
     * @param persistedAt ISO-8601 timestamp of persistence
     */
    @SuppressWarnings("unchecked")
    public void persist(Map<String, Object> input, String persistedAt) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");
        String partitionKey = tenantId + "#" + documentId;

        log.info(String.format("Writing document to DynamoDB: pk=%s", partitionKey));

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(partitionKey));
        item.put("documentId", s(documentId));
        item.put("tenantId", s(tenantId));

        // GSI1 attributes
        String status = (String) input.get("status");
        if (status != null) {
            item.put("status", s(status));
        }

        // GSI2 attributes
        Object classificationObj = input.get("classification");
        String documentType = extractDocumentType(classificationObj);
        if (documentType != null) {
            item.put("document_type", s(documentType));
        }

        String uploadTimestamp = (String) input.get("uploadTimestamp");
        if (uploadTimestamp != null) {
            item.put("uploadTimestamp", s(uploadTimestamp));
            // upload_date for GSI2 (YYYY-MM-DD prefix)
            String uploadDate = uploadTimestamp.length() >= 10 ? uploadTimestamp.substring(0, 10) : uploadTimestamp;
            item.put("upload_date", s(uploadDate));
        }

        // S3 key
        String s3Key = (String) input.get("s3Key");
        if (s3Key != null) {
            item.put("s3Key", s(s3Key));
        }

        // Serialise complex fields as JSON strings
        serializeField(item, "content", input.get("content"));
        serializeField(item, "entities", input.get("entities"));
        serializeField(item, "classification", classificationObj);
        serializeField(item, "kpis", input.get("kpis"));

        item.put("persistedAt", s(persistedAt));

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        dynamoDbClient.putItem(request);
        log.info(String.format("Document persisted to DynamoDB: pk=%s", partitionKey));
    }

    /**
     * Updates the document status to {@code PERSISTENCE_ERROR} in DynamoDB.
     *
     * @param tenantId   tenant identifier
     * @param documentId document identifier
     */
    public void markPersistenceError(String tenantId, String documentId) {
        String partitionKey = tenantId + "#" + documentId;
        log.warning(String.format("Marking PERSISTENCE_ERROR in DynamoDB: pk=%s", partitionKey));

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("pk", s(partitionKey));

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", s(DocumentStatus.PERSISTENCE_ERROR.name()));

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET #st = :status")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(expressionValues)
                .build();

        dynamoDbClient.updateItem(updateRequest);
    }

    @SuppressWarnings("unchecked")
    private String extractDocumentType(Object classificationObj) {
        if (classificationObj == null) return null;
        if (classificationObj instanceof Map) {
            Object category = ((Map<String, Object>) classificationObj).get("category");
            return category != null ? category.toString() : null;
        }
        return null;
    }

    private void serializeField(Map<String, AttributeValue> item, String key, Object value) {
        if (value == null) return;
        try {
            String json = objectMapper.writeValueAsString(value);
            item.put(key, s(json));
        } catch (Exception e) {
            log.warning(String.format("Failed to serialize field '%s': %s", key, e.getMessage()));
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
