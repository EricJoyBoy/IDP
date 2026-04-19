package com.idp.ingestion.controller;

import com.idp.ingestion.exception.DocumentTooLargeException;
import com.idp.ingestion.exception.GlobalExceptionHandler;
import com.idp.ingestion.exception.UnsupportedDocumentFormatException;
import com.idp.ingestion.config.ThrottlingConfig;
import com.idp.ingestion.security.TenantContext;
import com.idp.ingestion.service.BatchProcessingService;
import com.idp.ingestion.service.DocumentStorageService;
import com.idp.ingestion.service.DocumentValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Unit tests for DocumentIngestionController.
 * Requirements: 1.1, 1.3, 1.4
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionControllerTest {

    @Mock
    private DocumentValidationService validationService;

    @Mock
    private DocumentStorageService storageService;

    @Mock
    private ThrottlingConfig throttlingConfig;

    @Mock
    private BatchProcessingService batchProcessingService;

    @InjectMocks
    private DocumentIngestionController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantContext.setTenantId("tenant-001");
        // Default: system not throttled, file is not large
        when(throttlingConfig.isThrottled()).thenReturn(false);
        when(batchProcessingService.isLargeDocument(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void uploadValidPdf_returns202WithDocumentId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf", new byte[1024]);

        when(validationService.resolveFormat(any())).thenReturn("PDF");
        doNothing().when(storageService).upload(anyString(), anyString(), anyString(), any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.uploadTimestamp").isNotEmpty());

        verify(validationService).validate(any());
        verify(storageService).upload(eq("tenant-001"), anyString(), eq("PDF"), any());
    }

    @Test
    void uploadValidPng_returns202WithDocumentId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.png", "image/png", new byte[2048]);

        when(validationService.resolveFormat(any())).thenReturn("PNG");
        doNothing().when(storageService).upload(anyString(), anyString(), anyString(), any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // -------------------------------------------------------------------------
    // Format validation – HTTP 415
    // -------------------------------------------------------------------------

    @Test
    void uploadUnsupportedFormat_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[512]);

        doThrow(new UnsupportedDocumentFormatException(
                "Unsupported document format. Accepted formats: [PDF, PNG, JPEG, TIFF]"))
                .when(validationService).validate(any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.acceptedFormats").isArray());
    }

    @Test
    void uploadGifFormat_returns415() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.gif", "image/gif", new byte[512]);

        doThrow(new UnsupportedDocumentFormatException("Unsupported document format"))
                .when(validationService).validate(any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isUnsupportedMediaType());
    }

    // -------------------------------------------------------------------------
    // Size validation – HTTP 413
    // -------------------------------------------------------------------------

    @Test
    void uploadFileExceeding50MB_returns413() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[1024]);

        doThrow(new DocumentTooLargeException("File size exceeds 50 MB"))
                .when(validationService).validate(any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));
    }

    // -------------------------------------------------------------------------
    // Tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void upload_usesCorrectTenantIdFromContext() throws Exception {
        TenantContext.setTenantId("tenant-xyz");
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[100]);

        when(validationService.resolveFormat(any())).thenReturn("PDF");
        doNothing().when(storageService).upload(anyString(), anyString(), anyString(), any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isAccepted());

        verify(storageService).upload(eq("tenant-xyz"), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Throttling — HTTP 429 (Req 13.4)
    // -------------------------------------------------------------------------

    @Test
    void upload_whenThrottled_returns429WithRetryAfterHeader() throws Exception {
        when(throttlingConfig.isThrottled()).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[1024]);

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));

        // Validation and storage must NOT be called when throttled
        verify(validationService, never()).validate(any());
        verify(storageService, never()).upload(anyString(), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Batch processing — large documents (Req 13.3)
    // -------------------------------------------------------------------------

    @Test
    void upload_largeDocument_routesToBatchAndReturnsPendingBatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[3 * 1024 * 1024]);

        when(batchProcessingService.isLargeDocument(any())).thenReturn(true);
        when(validationService.resolveFormat(any())).thenReturn("PDF");
        doNothing().when(batchProcessingService)
                .submitForBatchProcessing(anyString(), anyString(), anyString(), any());

        mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_BATCH"))
                .andExpect(jsonPath("$.documentId").isNotEmpty());

        verify(batchProcessingService).submitForBatchProcessing(
                eq("tenant-001"), anyString(), eq("PDF"), any());
        // Regular storageService.upload must NOT be called for large documents
        verify(storageService, never()).upload(anyString(), anyString(), anyString(), any());
    }
}
