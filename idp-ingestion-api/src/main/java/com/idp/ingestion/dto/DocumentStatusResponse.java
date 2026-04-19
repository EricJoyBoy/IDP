package com.idp.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idp.common.model.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Lightweight response for GET /api/v1/documents/{documentId} – status only.
 * Requirements: 8.1, 8.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStatusResponse {

    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("status")
    private DocumentStatus status;

    @JsonProperty("uploadTimestamp")
    private Instant uploadTimestamp;

    @JsonProperty("processedTimestamp")
    private Instant processedTimestamp;
}
