package com.idp.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response returned after a successful document upload.
 * Requirements: 1.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {

    @JsonProperty("documentId")
    private String documentId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("uploadTimestamp")
    private Instant uploadTimestamp;
}
