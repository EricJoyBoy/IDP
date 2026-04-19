package com.idp.ingestion.controller;

import com.idp.common.model.DocumentDTO;
import com.idp.common.model.DocumentStatus;
import com.idp.ingestion.config.ThrottlingConfig;
import com.idp.ingestion.dto.DocumentStatusResponse;
import com.idp.ingestion.dto.PagedDocumentResponse;
import com.idp.ingestion.dto.UploadResponse;
import com.idp.ingestion.security.TenantContext;
import com.idp.ingestion.service.BatchProcessingService;
import com.idp.ingestion.service.DocumentCacheService;
import com.idp.ingestion.service.DocumentCacheService.CacheResult;
import com.idp.ingestion.service.DocumentQueryService;
import com.idp.ingestion.service.DocumentStorageService;
import com.idp.ingestion.service.DocumentValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for document ingestion and consultation.
 *
 * POST   /api/v1/documents                       – upload document
 * GET    /api/v1/documents/{documentId}           – document status
 * GET    /api/v1/documents/{documentId}/results   – full results (cached)
 * GET    /api/v1/documents                        – paginated list for tenant
 * GET    /api/v1/documents/search                 – search by KPI / date range
 *
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 8.1, 8.2, 8.3, 8.4, 8.5, 13.3, 13.4
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentIngestionController {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionController.class);

    private final DocumentValidationService validationService;
    private final DocumentStorageService storageService;
    private final DocumentQueryService queryService;
    private final DocumentCacheService cacheService;
    private final ThrottlingConfig throttlingConfig;
    private final BatchProcessingService batchProcessingService;

    public DocumentIngestionController(DocumentValidationService validationService,
                                       DocumentStorageService storageService,
                                       DocumentQueryService queryService,
                                       DocumentCacheService cacheService,
                                       ThrottlingConfig throttlingConfig,
                                       BatchProcessingService batchProcessingService) {
        this.validationService = validationService;
        this.storageService = storageService;
        this.queryService = queryService;
        this.cacheService = cacheService;
        this.throttlingConfig = throttlingConfig;
        this.batchProcessingService = batchProcessingService;
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/documents – upload
    // -------------------------------------------------------------------------

    /**
     * Upload a document.
     * HTTP 429 if system is at >80% capacity (Req 13.4).
     * HTTP 415 if format unsupported, HTTP 413 if >50 MB, HTTP 202 on success.
     * Large documents (>2 MB heuristic for >10 pages) are routed to async batch
     * processing and returned with status PENDING_BATCH (Req 13.3).
     * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 13.3, 13.4
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadDocument(
            @RequestPart("file") MultipartFile file) {

        // Req 13.4 — throttle when system is at >80% capacity
        if (throttlingConfig.isThrottled()) {
            log.warn("Throttling upload request — system at capacity activeRequests={}",
                    throttlingConfig.getActiveRequests());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .build();
        }

        throttlingConfig.acquire();
        try {
            String tenantId = TenantContext.getTenantId();
            log.info("Upload request tenantId={} filename={} size={}", tenantId,
                    file.getOriginalFilename(), file.getSize());

            validationService.validate(file);

            String documentId = UUID.randomUUID().toString();
            String format = validationService.resolveFormat(file);
            Instant uploadTimestamp = Instant.now();

            // Req 13.3 — route large documents (>2 MB ≈ >10 pages) to async batch processing
            if (batchProcessingService.isLargeDocument(file)) {
                batchProcessingService.submitForBatchProcessing(tenantId, documentId, format, file);
                log.info("Large document queued for batch processing documentId={} tenantId={}", documentId, tenantId);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                        UploadResponse.builder()
                                .documentId(documentId)
                                .status("PENDING_BATCH")
                                .uploadTimestamp(uploadTimestamp)
                                .build());
            }

            storageService.upload(tenantId, documentId, format, file);
            log.info("Document accepted documentId={} tenantId={} format={}", documentId, tenantId, format);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    UploadResponse.builder()
                            .documentId(documentId)
                            .status(DocumentStatus.PENDING.name())
                            .uploadTimestamp(uploadTimestamp)
                            .build());
        } finally {
            throttlingConfig.release();
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/search – must be declared BEFORE /{documentId}
    // -------------------------------------------------------------------------

    /**
     * Search documents by KPI name, date range, and/or status.
     * Requirements: 8.2
     */
    @GetMapping("/search")
    public ResponseEntity<PagedDocumentResponse> searchDocuments(
            @RequestParam(required = false) String kpi,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String tenantId = TenantContext.getTenantId();
        log.debug("Search documents tenantId={} kpi={} dateFrom={} dateTo={} status={}",
                tenantId, kpi, dateFrom, dateTo, status);

        PagedDocumentResponse result = queryService.searchDocuments(
                tenantId, kpi, dateFrom, dateTo, status, page, size);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents – paginated list
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of documents for the authenticated tenant.
     * Requirements: 8.2
     */
    @GetMapping
    public ResponseEntity<PagedDocumentResponse> listDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String lastEvaluatedKey) {

        String tenantId = TenantContext.getTenantId();
        log.debug("List documents tenantId={} page={} size={}", tenantId, page, size);

        PagedDocumentResponse result = queryService.listDocuments(tenantId, page, size, lastEvaluatedKey);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId} – status
    // -------------------------------------------------------------------------

    /**
     * Returns the status of a document.
     * HTTP 403 if the document belongs to a different tenant (Req 8.3).
     * Requirements: 8.1, 8.3
     */
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentStatusResponse> getDocumentStatus(
            @PathVariable String documentId) {

        String tenantId = TenantContext.getTenantId();
        log.debug("Get status tenantId={} documentId={}", tenantId, documentId);

        DocumentStatusResponse response = queryService.getDocumentStatus(tenantId, documentId);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId}/results – full results with caching
    // -------------------------------------------------------------------------

    /**
     * Returns the full processing results for a document.
     * Checks Redis cache first; adds X-Cache: HIT/MISS header.
     * Requirements: 8.1, 8.2, 8.4, 8.5
     */
    @GetMapping("/{documentId}/results")
    public ResponseEntity<DocumentDTO> getDocumentResults(
            @PathVariable String documentId) {

        String tenantId = TenantContext.getTenantId();
        log.debug("Get results tenantId={} documentId={}", tenantId, documentId);

        // Check cache first (Req 8.4, 8.5)
        CacheResult cached = cacheService.get(tenantId, documentId);
        if (cached.hit()) {
            return ResponseEntity.ok()
                    .header(DocumentCacheService.X_CACHE_HEADER, DocumentCacheService.CACHE_HIT)
                    .body(cached.document());
        }

        // Cache miss – fetch from DynamoDB
        DocumentDTO document = queryService.getDocumentResults(tenantId, documentId);

        // Populate cache for subsequent requests
        cacheService.put(tenantId, documentId, document);

        return ResponseEntity.ok()
                .header(DocumentCacheService.X_CACHE_HEADER, DocumentCacheService.CACHE_MISS)
                .body(document);
    }
}
