package com.idp.pipeline.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.serialization.DocumentObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Archives the full pipeline payload to the S3 Data Lake.
 *
 * <p>S3 key pattern: {@code {tenant_id}/{year}/{month}/{day}/{document_id}.json}
 * <p>The partition date is derived from {@code uploadTimestamp} when present,
 * otherwise from the current UTC time.
 */
public class DataLakeArchiver {

    private static final Logger log = Logger.getLogger(DataLakeArchiver.class.getName());

    private final S3Client s3Client;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    public DataLakeArchiver(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.objectMapper = DocumentObjectMapper.getInstance();
    }

    /**
     * Serialises the full input map to JSON and uploads it to the Data Lake bucket.
     *
     * @param input      the pipeline input map (all document fields)
     * @param persistedAt ISO-8601 timestamp added to the archived payload
     */
    public void archive(Map<String, Object> input, String persistedAt) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");

        ZonedDateTime partitionDate = resolvePartitionDate(input);
        String year  = String.format("%04d", partitionDate.getYear());
        String month = String.format("%02d", partitionDate.getMonthValue());
        String day   = String.format("%02d", partitionDate.getDayOfMonth());

        String s3Key = String.format("%s/%s/%s/%s/%s.json", tenantId, year, month, day, documentId);

        log.info(String.format("Archiving document to Data Lake: bucket=%s key=%s", bucketName, s3Key));

        try {
            // Build the payload including persistedAt
            Map<String, Object> payload = new java.util.HashMap<>(input);
            payload.put("persistedAt", persistedAt);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/json")
                    .contentLength((long) jsonBytes.length)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(jsonBytes));
            log.info(String.format("Document archived to Data Lake: s3://%s/%s", bucketName, s3Key));

        } catch (Exception e) {
            throw new RuntimeException("Data Lake archival failed for documentId=" + documentId, e);
        }
    }

    private ZonedDateTime resolvePartitionDate(Map<String, Object> input) {
        Object uploadTimestamp = input.get("uploadTimestamp");
        if (uploadTimestamp != null) {
            try {
                return Instant.parse(uploadTimestamp.toString()).atZone(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // fall through to current time
            }
        }
        return Instant.now().atZone(ZoneOffset.UTC);
    }
}
