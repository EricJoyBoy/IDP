package com.idp.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.UUID;

/**
 * Handles async batch processing for large documents (>10 pages heuristic: >2 MB).
 *
 * For large documents:
 *  1. Stores the document on S3 via DocumentStorageService.
 *  2. Sends an SQS message to the batch processing queue.
 *  3. Returns immediately — the caller should respond with status PENDING_BATCH.
 *
 * Requirements: 13.3
 */
@Service
public class BatchProcessingService {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingService.class);

    /** 2 MB heuristic threshold — documents larger than this are treated as >10 pages */
    public static final long LARGE_DOCUMENT_THRESHOLD_BYTES = 2L * 1024 * 1024;

    private final DocumentStorageService storageService;
    private final SqsClient sqsClient;
    private final String batchQueueUrl;

    public BatchProcessingService(
            DocumentStorageService storageService,
            SqsClient sqsClient,
            @Value("${aws.sqs.batch-queue-url:https://sqs.eu-west-1.amazonaws.com/000000000000/idp-batch-queue}") String batchQueueUrl) {
        this.storageService = storageService;
        this.sqsClient = sqsClient;
        this.batchQueueUrl = batchQueueUrl;
    }

    /**
     * Returns true when the file should be routed to async batch processing.
     * Heuristic: files larger than 2 MB are assumed to exceed 10 pages.
     */
    public boolean isLargeDocument(MultipartFile file) {
        return file.getSize() > LARGE_DOCUMENT_THRESHOLD_BYTES;
    }

    /**
     * Stores the document on S3 and enqueues an SQS message for async processing.
     *
     * @param tenantId   tenant identifier
     * @param documentId pre-generated UUID for this document
     * @param format     resolved document format (PDF, PNG, JPEG, TIFF)
     * @param file       the uploaded file
     */
    public void submitForBatchProcessing(String tenantId, String documentId,
                                         String format, MultipartFile file) {
        // 1. Persist to S3 (same path as synchronous flow)
        storageService.upload(tenantId, documentId, format, file);

        // 2. Enqueue SQS message for async pipeline trigger
        String messageBody = buildMessageBody(tenantId, documentId, format, file.getSize());

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(batchQueueUrl)
                .messageBody(messageBody)
                .messageGroupId(tenantId)          // FIFO queue grouping per tenant
                .messageDeduplicationId(documentId) // idempotent — one message per document
                .build());

        log.info("Submitted large document for batch processing documentId={} tenantId={} sizeBytes={}",
                documentId, tenantId, file.getSize());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildMessageBody(String tenantId, String documentId,
                                    String format, long sizeBytes) {
        // Minimal JSON payload — the batch consumer will fetch the document from S3
        return String.format(
                "{\"tenantId\":\"%s\",\"documentId\":\"%s\",\"format\":\"%s\",\"sizeBytes\":%d}",
                tenantId, documentId, format, sizeBytes);
    }
}
