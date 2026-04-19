package com.idp.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles document storage on Amazon S3.
 * Uses standard PutObject for files ≤5MB and multipart upload for files >5MB.
 * Requirements: 1.2, 1.5, 1.6
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    /** 5 MB threshold for switching to multipart upload */
    static final long MULTIPART_THRESHOLD_BYTES = 5L * 1024 * 1024;

    /** 5 MB part size for multipart upload */
    static final long PART_SIZE_BYTES = 5L * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucketName;

    public DocumentStorageService(S3Client s3Client,
                                  @Value("${aws.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    /**
     * Uploads a document to S3 with tenant metadata.
     * S3 key format: {tenantId}/{documentId}
     *
     * @param tenantId   tenant identifier (from JWT)
     * @param documentId generated UUID for this document
     * @param format     document format (PDF, PNG, JPEG, TIFF)
     * @param file       the uploaded file
     */
    public void upload(String tenantId, String documentId, String format, MultipartFile file) {
        String s3Key = tenantId + "/" + documentId;
        long sizeBytes = file.getSize();

        Map<String, String> metadata = Map.of(
                "tenant_id", tenantId,
                "timestamp", Instant.now().toString(),
                "format", format
        );

        if (sizeBytes > MULTIPART_THRESHOLD_BYTES) {
            uploadMultipart(s3Key, file, metadata, sizeBytes);
        } else {
            uploadSingle(s3Key, file, metadata);
        }

        log.info("Uploaded document s3Key={} tenantId={} format={} sizeBytes={}", s3Key, tenantId, format, sizeBytes);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void uploadSingle(String s3Key, MultipartFile file, Map<String, String> metadata) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .metadata(metadata)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    private void uploadMultipart(String s3Key, MultipartFile file,
                                 Map<String, String> metadata, long totalSize) {
        // Initiate multipart upload
        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(file.getContentType())
                .metadata(metadata)
                .build();

        CreateMultipartUploadResponse createResponse = s3Client.createMultipartUpload(createRequest);
        String uploadId = createResponse.uploadId();

        List<CompletedPart> completedParts = new ArrayList<>();
        int partNumber = 1;

        try (InputStream inputStream = file.getInputStream()) {
            long remaining = totalSize;

            while (remaining > 0) {
                long partSize = Math.min(PART_SIZE_BYTES, remaining);
                byte[] buffer = inputStream.readNBytes((int) partSize);

                UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(partSize)
                        .build();

                UploadPartResponse uploadPartResponse = s3Client.uploadPart(
                        uploadPartRequest, RequestBody.fromBytes(buffer));

                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(uploadPartResponse.eTag())
                        .build());

                remaining -= partSize;
                partNumber++;
            }

            // Complete the multipart upload
            CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build())
                    .build();

            s3Client.completeMultipartUpload(completeRequest);

        } catch (IOException e) {
            abortMultipartUpload(s3Key, uploadId);
            throw new RuntimeException("Failed to read uploaded file during multipart upload", e);
        } catch (Exception e) {
            abortMultipartUpload(s3Key, uploadId);
            throw e;
        }
    }

    private void abortMultipartUpload(String s3Key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .uploadId(uploadId)
                    .build());
        } catch (Exception ex) {
            log.error("Failed to abort multipart upload for key={} uploadId={}", s3Key, uploadId, ex);
        }
    }
}
