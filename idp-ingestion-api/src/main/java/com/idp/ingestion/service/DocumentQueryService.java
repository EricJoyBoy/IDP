package com.idp.ingestion.service;

import com.idp.common.model.*;
import com.idp.ingestion.dto.DocumentStatusResponse;
import com.idp.ingestion.dto.PagedDocumentResponse;
import com.idp.ingestion.exception.DocumentNotFoundException;
import com.idp.ingestion.exception.TenantAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads document data from DynamoDB.
 *
 * DynamoDB schema:
 *   - Partition key: tenant_id#document_id
 *   - GSI1: tenant_id (PK) + status (SK)  → query by status
 *   - GSI2: tenant_id (PK) + upload_date (SK) → query by date range / type
 *
 * Tenant isolation: if the stored tenant_id differs from the caller's tenant_id,
 * throws TenantAccessDeniedException (→ HTTP 403) without revealing the document exists.
 *
 * Requirements: 8.1, 8.2, 8.3
 */
@Service
public class DocumentQueryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueryService.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DocumentQueryService(DynamoDbClient dynamoDbClient,
                                @Value("${aws.dynamodb.table:idp-documents}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId} – status only
    // -------------------------------------------------------------------------

    /**
     * Returns a lightweight status response for the given document.
     * Enforces tenant isolation: throws TenantAccessDeniedException if the document
     * belongs to a different tenant.
     *
     * @param tenantId   caller's tenant (from JWT)
     * @param documentId document UUID
     * @return status response
     * @throws TenantAccessDeniedException if cross-tenant access is attempted
     * @throws DocumentNotFoundException   if the document does not exist
     */
    public DocumentStatusResponse getDocumentStatus(String tenantId, String documentId) {
        Map<String, AttributeValue> item = fetchItem(tenantId, documentId);
        return DocumentStatusResponse.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .status(parseStatus(item))
                .uploadTimestamp(parseInstant(item, "upload_timestamp"))
                .processedTimestamp(parseInstant(item, "processed_timestamp"))
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId}/results – full DTO
    // -------------------------------------------------------------------------

    /**
     * Returns the full DocumentDTO for the given document.
     * Enforces tenant isolation.
     *
     * @param tenantId   caller's tenant (from JWT)
     * @param documentId document UUID
     * @return full DocumentDTO
     */
    public DocumentDTO getDocumentResults(String tenantId, String documentId) {
        Map<String, AttributeValue> item = fetchItem(tenantId, documentId);
        return mapToDocumentDTO(item, tenantId, documentId);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents – paginated list for tenant
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of documents for the given tenant.
     * Uses GSI1 (tenant_id PK) to query all documents for the tenant.
     *
     * @param tenantId          caller's tenant
     * @param page              zero-based page number
     * @param size              page size (max 100)
     * @param lastEvaluatedKey  opaque pagination token from previous response (nullable)
     * @return paged response
     */
    public PagedDocumentResponse listDocuments(String tenantId, int page, int size,
                                               String lastEvaluatedKey) {
        int effectiveSize = Math.min(size, 100);

        QueryRequest.Builder queryBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI1")
                .keyConditionExpression("tenant_id = :tid")
                .expressionAttributeValues(Map.of(":tid", AttributeValue.fromS(tenantId)))
                .limit(effectiveSize);

        if (lastEvaluatedKey != null && !lastEvaluatedKey.isBlank()) {
            queryBuilder.exclusiveStartKey(decodePageToken(lastEvaluatedKey, tenantId));
        }

        QueryResponse response = dynamoDbClient.query(queryBuilder.build());

        List<DocumentDTO> items = response.items().stream()
                .map(item -> mapToDocumentDTO(item,
                        tenantId,
                        getString(item, "document_id")))
                .collect(Collectors.toList());

        String nextToken = response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()
                ? encodePageToken(response.lastEvaluatedKey())
                : null;

        return PagedDocumentResponse.builder()
                .items(items)
                .page(page)
                .size(effectiveSize)
                .totalItems(items.size())
                .lastEvaluatedKey(nextToken)
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/search – filter by KPI name, date range, status
    // -------------------------------------------------------------------------

    /**
     * Searches documents for the tenant with optional filters.
     * Uses GSI2 (tenant_id PK + upload_date SK) for date range queries.
     *
     * @param tenantId  caller's tenant
     * @param kpiName   optional KPI name filter (e.g. "ricavi")
     * @param dateFrom  optional start date (ISO-8601)
     * @param dateTo    optional end date (ISO-8601)
     * @param status    optional status filter
     * @param page      zero-based page number
     * @param size      page size
     * @return paged response
     */
    public PagedDocumentResponse searchDocuments(String tenantId, String kpiName,
                                                 String dateFrom, String dateTo,
                                                 String status, int page, int size) {
        int effectiveSize = Math.min(size, 100);

        // Build key condition and filter expressions
        StringBuilder keyCondition = new StringBuilder("tenant_id = :tid");
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":tid", AttributeValue.fromS(tenantId));

        if (dateFrom != null && dateTo != null) {
            keyCondition.append(" AND upload_date BETWEEN :dateFrom AND :dateTo");
            exprValues.put(":dateFrom", AttributeValue.fromS(dateFrom));
            exprValues.put(":dateTo", AttributeValue.fromS(dateTo));
        } else if (dateFrom != null) {
            keyCondition.append(" AND upload_date >= :dateFrom");
            exprValues.put(":dateFrom", AttributeValue.fromS(dateFrom));
        } else if (dateTo != null) {
            keyCondition.append(" AND upload_date <= :dateTo");
            exprValues.put(":dateTo", AttributeValue.fromS(dateTo));
        }

        QueryRequest.Builder queryBuilder = QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI2")
                .keyConditionExpression(keyCondition.toString())
                .expressionAttributeValues(exprValues)
                .limit(effectiveSize);

        // Apply status filter expression if provided
        if (status != null && !status.isBlank()) {
            queryBuilder.filterExpression("#st = :status")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(addEntry(exprValues, ":status", AttributeValue.fromS(status)));
        }

        QueryResponse response = dynamoDbClient.query(queryBuilder.build());

        List<DocumentDTO> items = response.items().stream()
                .map(item -> mapToDocumentDTO(item, tenantId, getString(item, "document_id")))
                .filter(doc -> kpiName == null || kpiName.isBlank() || hasKpi(doc, kpiName))
                .collect(Collectors.toList());

        return PagedDocumentResponse.builder()
                .items(items)
                .page(page)
                .size(effectiveSize)
                .totalItems(items.size())
                .lastEvaluatedKey(null)
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches a single item from DynamoDB and enforces tenant isolation.
     * If the item does not exist, throws DocumentNotFoundException.
     * If the item belongs to a different tenant, throws TenantAccessDeniedException
     * (HTTP 403 without revealing the document exists – Req 8.3).
     */
    private Map<String, AttributeValue> fetchItem(String tenantId, String documentId) {
        String pk = tenantId + "#" + documentId;

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", AttributeValue.fromS(pk)))
                .build();

        GetItemResponse response = dynamoDbClient.getItem(request);

        if (!response.hasItem() || response.item().isEmpty()) {
            // Try fetching without tenant prefix to detect cross-tenant access
            // We do NOT reveal whether the document exists for a different tenant.
            log.debug("Document not found pk={}", pk);
            throw new DocumentNotFoundException(documentId);
        }

        Map<String, AttributeValue> item = response.item();
        String storedTenantId = getString(item, "tenant_id");

        if (!tenantId.equals(storedTenantId)) {
            // Cross-tenant access: return 403 without revealing existence (Req 8.3)
            log.warn("Cross-tenant access attempt: caller={} storedTenant={} documentId={}",
                    tenantId, storedTenantId, documentId);
            throw new TenantAccessDeniedException();
        }

        return item;
    }

    private DocumentDTO mapToDocumentDTO(Map<String, AttributeValue> item,
                                         String tenantId, String documentId) {
        return DocumentDTO.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .s3Key(getString(item, "s3_key"))
                .format(getString(item, "format"))
                .sizeBytes(getLong(item, "size_bytes"))
                .uploadTimestamp(parseInstant(item, "upload_timestamp"))
                .processedTimestamp(parseInstant(item, "processed_timestamp"))
                .status(parseStatus(item))
                .kpis(parseKpis(item))
                .build();
    }

    private DocumentStatus parseStatus(Map<String, AttributeValue> item) {
        String s = getString(item, "status");
        if (s == null) return DocumentStatus.PENDING;
        try {
            return DocumentStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return DocumentStatus.PENDING;
        }
    }

    private Instant parseInstant(Map<String, AttributeValue> item, String key) {
        String val = getString(item, key);
        if (val == null) return null;
        try {
            return Instant.parse(val);
        } catch (Exception e) {
            return null;
        }
    }

    private List<KPI> parseKpis(Map<String, AttributeValue> item) {
        AttributeValue kpisAttr = item.get("kpis");
        if (kpisAttr == null || kpisAttr.l() == null) return Collections.emptyList();

        return kpisAttr.l().stream()
                .filter(av -> av.m() != null)
                .map(av -> {
                    Map<String, AttributeValue> m = av.m();
                    return KPI.builder()
                            .name(getString(m, "name"))
                            .value(getBigDecimal(m, "value"))
                            .unit(getString(m, "unit"))
                            .confidenceScore(getDouble(m, "confidence_score"))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private String getString(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return (av != null && av.s() != null) ? av.s() : null;
    }

    private Long getLong(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        if (av == null || av.n() == null) return null;
        try {
            return Long.parseLong(av.n());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDouble(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        if (av == null || av.n() == null) return null;
        try {
            return Double.parseDouble(av.n());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal getBigDecimal(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        if (av == null || av.n() == null) return null;
        try {
            return new BigDecimal(av.n());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean hasKpi(DocumentDTO doc, String kpiName) {
        if (doc.getKpis() == null) return false;
        return doc.getKpis().stream()
                .anyMatch(k -> kpiName.equalsIgnoreCase(k.getName()));
    }

    /** Encodes DynamoDB LastEvaluatedKey as a simple Base64 string token. */
    private String encodePageToken(Map<String, AttributeValue> lastEvaluatedKey) {
        String pk = lastEvaluatedKey.containsKey("pk")
                ? lastEvaluatedKey.get("pk").s() : "";
        return Base64.getEncoder().encodeToString(pk.getBytes());
    }

    /** Decodes a page token back to a DynamoDB ExclusiveStartKey map. */
    private Map<String, AttributeValue> decodePageToken(String token, String tenantId) {
        try {
            String pk = new String(Base64.getDecoder().decode(token));
            return Map.of("pk", AttributeValue.fromS(pk));
        } catch (Exception e) {
            log.warn("Invalid pagination token, ignoring: {}", token);
            return Collections.emptyMap();
        }
    }

    private Map<String, AttributeValue> addEntry(Map<String, AttributeValue> original,
                                                  String key, AttributeValue value) {
        Map<String, AttributeValue> copy = new HashMap<>(original);
        copy.put(key, value);
        return copy;
    }
}
