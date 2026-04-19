package com.idp.ingestion.controller;

import com.idp.common.model.DocumentDTO;
import com.idp.common.model.DocumentStatus;
import com.idp.ingestion.dto.DocumentStatusResponse;
import com.idp.ingestion.dto.PagedDocumentResponse;
import com.idp.ingestion.exception.DocumentNotFoundException;
import com.idp.ingestion.exception.GlobalExceptionHandler;
import com.idp.ingestion.exception.TenantAccessDeniedException;
import com.idp.ingestion.security.TenantContext;
import com.idp.ingestion.service.DocumentCacheService;
import com.idp.ingestion.service.DocumentCacheService.CacheResult;
import com.idp.ingestion.service.DocumentQueryService;
import com.idp.ingestion.service.DocumentStorageService;
import com.idp.ingestion.service.DocumentValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for GET endpoints in DocumentIngestionController.
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5
 */
@ExtendWith(MockitoExtension.class)
class DocumentQueryControllerTest {

    @Mock
    private DocumentValidationService validationService;
    @Mock
    private DocumentStorageService storageService;
    @Mock
    private DocumentQueryService queryService;
    @Mock
    private DocumentCacheService cacheService;

    @InjectMocks
    private DocumentIngestionController controller;

    private MockMvc mockMvc;

    private static final String TENANT_ID = "tenant-001";
    private static final String DOC_ID = "doc-uuid-123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId} – status
    // -------------------------------------------------------------------------

    @Test
    void getDocumentStatus_returnsOkWithStatus() throws Exception {
        DocumentStatusResponse statusResponse = DocumentStatusResponse.builder()
                .documentId(DOC_ID)
                .tenantId(TENANT_ID)
                .status(DocumentStatus.COMPLETED)
                .uploadTimestamp(Instant.parse("2024-01-01T10:00:00Z"))
                .build();

        when(queryService.getDocumentStatus(TENANT_ID, DOC_ID)).thenReturn(statusResponse);

        mockMvc.perform(get("/api/v1/documents/{id}", DOC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(DOC_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getDocumentStatus_crossTenant_returns403() throws Exception {
        // Req 8.3: cross-tenant access must return 403 without revealing existence
        when(queryService.getDocumentStatus(TENANT_ID, DOC_ID))
                .thenThrow(new TenantAccessDeniedException());

        mockMvc.perform(get("/api/v1/documents/{id}", DOC_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void getDocumentStatus_notFound_returns404() throws Exception {
        when(queryService.getDocumentStatus(TENANT_ID, DOC_ID))
                .thenThrow(new DocumentNotFoundException(DOC_ID));

        mockMvc.perform(get("/api/v1/documents/{id}", DOC_ID))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/{documentId}/results – full results with caching
    // -------------------------------------------------------------------------

    @Test
    void getDocumentResults_cacheMiss_fetchesFromDynamoAndReturnsMissHeader() throws Exception {
        DocumentDTO doc = DocumentDTO.builder()
                .documentId(DOC_ID)
                .tenantId(TENANT_ID)
                .status(DocumentStatus.COMPLETED)
                .build();

        when(cacheService.get(TENANT_ID, DOC_ID)).thenReturn(new CacheResult(null, false));
        when(queryService.getDocumentResults(TENANT_ID, DOC_ID)).thenReturn(doc);

        mockMvc.perform(get("/api/v1/documents/{id}/results", DOC_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.documentId").value(DOC_ID));

        verify(cacheService).put(TENANT_ID, DOC_ID, doc);
    }

    @Test
    void getDocumentResults_cacheHit_returnsHitHeaderWithoutDynamoCall() throws Exception {
        DocumentDTO doc = DocumentDTO.builder()
                .documentId(DOC_ID)
                .tenantId(TENANT_ID)
                .status(DocumentStatus.COMPLETED)
                .build();

        when(cacheService.get(TENANT_ID, DOC_ID)).thenReturn(new CacheResult(doc, true));

        mockMvc.perform(get("/api/v1/documents/{id}/results", DOC_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.documentId").value(DOC_ID));

        // DynamoDB must NOT be called on cache hit (Req 8.5)
        verify(queryService, never()).getDocumentResults(anyString(), anyString());
        verify(cacheService, never()).put(anyString(), anyString(), any());
    }

    @Test
    void getDocumentResults_crossTenant_returns403() throws Exception {
        when(cacheService.get(TENANT_ID, DOC_ID)).thenReturn(new CacheResult(null, false));
        when(queryService.getDocumentResults(TENANT_ID, DOC_ID))
                .thenThrow(new TenantAccessDeniedException());

        mockMvc.perform(get("/api/v1/documents/{id}/results", DOC_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents – paginated list
    // -------------------------------------------------------------------------

    @Test
    void listDocuments_returnsPagedResponse() throws Exception {
        PagedDocumentResponse paged = PagedDocumentResponse.builder()
                .items(Collections.emptyList())
                .page(0)
                .size(20)
                .totalItems(0)
                .build();

        when(queryService.listDocuments(TENANT_ID, 0, 20, null)).thenReturn(paged);

        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void listDocuments_withPaginationParams_passesThemToService() throws Exception {
        PagedDocumentResponse paged = PagedDocumentResponse.builder()
                .items(Collections.emptyList())
                .page(2)
                .size(10)
                .totalItems(0)
                .build();

        when(queryService.listDocuments(TENANT_ID, 2, 10, "someToken")).thenReturn(paged);

        mockMvc.perform(get("/api/v1/documents")
                        .param("page", "2")
                        .param("size", "10")
                        .param("lastEvaluatedKey", "someToken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/documents/search
    // -------------------------------------------------------------------------

    @Test
    void searchDocuments_withFilters_returnsResults() throws Exception {
        PagedDocumentResponse paged = PagedDocumentResponse.builder()
                .items(Collections.emptyList())
                .page(0)
                .size(20)
                .totalItems(0)
                .build();

        when(queryService.searchDocuments(TENANT_ID, "ricavi", "2024-01-01", "2024-12-31",
                "COMPLETED", 0, 20)).thenReturn(paged);

        mockMvc.perform(get("/api/v1/documents/search")
                        .param("kpi", "ricavi")
                        .param("dateFrom", "2024-01-01")
                        .param("dateTo", "2024-12-31")
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
