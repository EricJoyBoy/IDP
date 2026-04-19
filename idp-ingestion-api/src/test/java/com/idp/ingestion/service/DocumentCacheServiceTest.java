package com.idp.ingestion.service;

import com.idp.common.model.DocumentDTO;
import com.idp.common.model.DocumentStatus;
import com.idp.ingestion.service.DocumentCacheService.CacheResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DocumentCacheService.
 * Requirements: 8.4, 8.5
 */
@ExtendWith(MockitoExtension.class)
class DocumentCacheServiceTest {

    @Mock
    private RedisTemplate<String, DocumentDTO> redisTemplate;

    @Mock
    private ValueOperations<String, DocumentDTO> valueOps;

    private DocumentCacheService cacheService;

    private static final String TENANT_ID = "tenant-001";
    private static final String DOC_ID = "doc-uuid-123";
    private static final String EXPECTED_KEY = "tenant-001:doc-uuid-123:results";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new DocumentCacheService(redisTemplate, 300L);
    }

    // -------------------------------------------------------------------------
    // Cache key format
    // -------------------------------------------------------------------------

    @Test
    void buildKey_followsExpectedFormat() {
        String key = DocumentCacheService.buildKey(TENANT_ID, DOC_ID);
        assertThat(key).isEqualTo(EXPECTED_KEY);
    }

    // -------------------------------------------------------------------------
    // Cache GET
    // -------------------------------------------------------------------------

    @Test
    void get_whenCacheHit_returnsTrueWithDocument() {
        DocumentDTO doc = DocumentDTO.builder()
                .documentId(DOC_ID)
                .tenantId(TENANT_ID)
                .status(DocumentStatus.COMPLETED)
                .build();

        when(valueOps.get(EXPECTED_KEY)).thenReturn(doc);

        CacheResult result = cacheService.get(TENANT_ID, DOC_ID);

        assertThat(result.hit()).isTrue();
        assertThat(result.document()).isEqualTo(doc);
    }

    @Test
    void get_whenCacheMiss_returnsFalseWithNullDocument() {
        when(valueOps.get(EXPECTED_KEY)).thenReturn(null);

        CacheResult result = cacheService.get(TENANT_ID, DOC_ID);

        assertThat(result.hit()).isFalse();
        assertThat(result.document()).isNull();
    }

    @Test
    void get_whenRedisThrows_returnsMissGracefully() {
        when(valueOps.get(EXPECTED_KEY)).thenThrow(new RuntimeException("Redis unavailable"));

        CacheResult result = cacheService.get(TENANT_ID, DOC_ID);

        assertThat(result.hit()).isFalse();
        assertThat(result.document()).isNull();
    }

    // -------------------------------------------------------------------------
    // Cache PUT
    // -------------------------------------------------------------------------

    @Test
    void put_storesDocumentWithConfiguredTtl() {
        DocumentDTO doc = DocumentDTO.builder().documentId(DOC_ID).build();

        cacheService.put(TENANT_ID, DOC_ID, doc);

        verify(valueOps).set(eq(EXPECTED_KEY), eq(doc), eq(Duration.ofSeconds(300)));
    }

    @Test
    void put_whenRedisThrows_doesNotPropagateException() {
        DocumentDTO doc = DocumentDTO.builder().documentId(DOC_ID).build();
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOps).set(anyString(), any(), any(Duration.class));

        // Should not throw
        cacheService.put(TENANT_ID, DOC_ID, doc);
    }

    // -------------------------------------------------------------------------
    // Cache EVICT
    // -------------------------------------------------------------------------

    @Test
    void evict_deletesCorrectKey() {
        cacheService.evict(TENANT_ID, DOC_ID);
        verify(redisTemplate).delete(EXPECTED_KEY);
    }
}
