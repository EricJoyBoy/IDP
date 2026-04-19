package com.idp.pipeline.eventrouter;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Payload passed to Step Functions as the execution input.
 */
public class StepFunctionsPayload {

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("tenantId")
    private final String tenantId;

    @JsonProperty("s3Key")
    private final String s3Key;

    @JsonProperty("uploadTimestamp")
    private final Instant uploadTimestamp;

    public StepFunctionsPayload(String documentId, String tenantId, String s3Key, Instant uploadTimestamp) {
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.s3Key = s3Key;
        this.uploadTimestamp = uploadTimestamp;
    }

    public String getDocumentId() { return documentId; }
    public String getTenantId() { return tenantId; }
    public String getS3Key() { return s3Key; }
    public Instant getUploadTimestamp() { return uploadTimestamp; }
}
