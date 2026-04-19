package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Main Data Transfer Object representing a document and all its processing results
 * throughout the IDP pipeline lifecycle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDTO {

    /** Unique document identifier (UUID) */
    @JsonProperty("documentId")
    private String documentId;

    /** Tenant identifier for multi-tenant isolation */
    @JsonProperty("tenantId")
    private String tenantId;

    /** S3 object key: {tenantId}/{documentId} */
    @JsonProperty("s3Key")
    private String s3Key;

    /** Document format: PDF, PNG, JPEG, TIFF */
    @JsonProperty("format")
    private String format;

    @JsonProperty("sizeBytes")
    private Long sizeBytes;

    @JsonProperty("uploadTimestamp")
    private Instant uploadTimestamp;

    @JsonProperty("status")
    private DocumentStatus status;

    @JsonProperty("content")
    private ExtractedContent content;

    @JsonProperty("entities")
    private List<Entity> entities;

    @JsonProperty("classification")
    private ClassificationResult classification;

    @JsonProperty("kpis")
    private List<KPI> kpis;

    @JsonProperty("processedTimestamp")
    private Instant processedTimestamp;
}
