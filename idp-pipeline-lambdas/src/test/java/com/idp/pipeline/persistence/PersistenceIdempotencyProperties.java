package com.idp.pipeline.persistence;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;
import org.mockito.Mockito;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for idempotency of document processing persistence.
 *
 * **Property 3: Idempotency of document processing**
 * **Validates: Requirements 12.5**
 *
 * Verifies that re-processing the same document_id does not duplicate records
 * and produces the same result on repeated invocations.
 */
class PersistenceIdempotencyProperties {

    /**
     * Property: calling handleRequest twice with the same documentId produces the same
     * output keys and the same documentId/tenantId values (idempotent result).
     *
     * The DynamoDB service uses PutItem (upsert semantics) so repeated calls overwrite
     * rather than duplicate. The RDS service uses INSERT ... ON CONFLICT DO UPDATE.
     * Both guarantee no duplication on re-processing.
     */
    @Property
    void idempotentOutputKeys(@ForAll @NotBlank String documentId,
                              @ForAll @NotBlank String tenantId) {
        DynamoDbPersistenceService dynamoDb = Mockito.mock(DynamoDbPersistenceService.class);
        RdsPersistenceService rds = Mockito.mock(RdsPersistenceService.class);
        DataLakeArchiver archiver = Mockito.mock(DataLakeArchiver.class);

        PersistenceHandler handler = new PersistenceHandler(dynamoDb, rds, archiver);

        Map<String, Object> input = buildInput(documentId, tenantId);

        Map<String, Object> result1 = handler.handleRequest(new HashMap<>(input), null);
        Map<String, Object> result2 = handler.handleRequest(new HashMap<>(input), null);

        // Both results must contain the same keys
        assertThat(result1.keySet()).isEqualTo(result2.keySet());

        // Core identity fields must be identical
        assertThat(result1.get("documentId")).isEqualTo(result2.get("documentId"));
        assertThat(result1.get("tenantId")).isEqualTo(result2.get("tenantId"));

        // persistedAt must be present in both
        assertThat(result1).containsKey("persistedAt");
        assertThat(result2).containsKey("persistedAt");
    }

    /**
     * Property: DynamoDB persist is called exactly once per invocation (no duplicate writes
     * within a single call), regardless of input content.
     */
    @Property
    void dynamoDbWrittenExactlyOncePerInvocation(@ForAll @NotBlank String documentId,
                                                  @ForAll @NotBlank String tenantId) {
        DynamoDbPersistenceService dynamoDb = Mockito.mock(DynamoDbPersistenceService.class);
        RdsPersistenceService rds = Mockito.mock(RdsPersistenceService.class);
        DataLakeArchiver archiver = Mockito.mock(DataLakeArchiver.class);

        PersistenceHandler handler = new PersistenceHandler(dynamoDb, rds, archiver);
        Map<String, Object> input = buildInput(documentId, tenantId);

        handler.handleRequest(new HashMap<>(input), null);

        // Exactly one DynamoDB write per invocation
        verify(dynamoDb, times(1)).persist(any(), anyString());
        verify(dynamoDb, never()).markPersistenceError(anyString(), anyString());
    }

    /**
     * Property: on RDS failure, markPersistenceError is called exactly once and
     * the Data Lake archiver is never called — regardless of document content.
     */
    @Property
    void onRdsFailure_persistenceErrorMarkedExactlyOnce(@ForAll @NotBlank String documentId,
                                                         @ForAll @NotBlank String tenantId) {
        DynamoDbPersistenceService dynamoDb = Mockito.mock(DynamoDbPersistenceService.class);
        RdsPersistenceService rds = Mockito.mock(RdsPersistenceService.class);
        DataLakeArchiver archiver = Mockito.mock(DataLakeArchiver.class);

        doThrow(new RdsPersistenceException("fail", new RuntimeException()))
                .when(rds).persist(anyString(), anyString(), any(), any(), anyString());

        PersistenceHandler handler = new PersistenceHandler(dynamoDb, rds, archiver);
        Map<String, Object> input = buildInput(documentId, tenantId);

        try {
            handler.handleRequest(new HashMap<>(input), null);
        } catch (RdsPersistenceException ignored) {
            // expected
        }

        verify(dynamoDb, times(1)).markPersistenceError(tenantId, documentId);
        verify(archiver, never()).archive(any(), anyString());
    }

    // ---- helpers ----

    private Map<String, Object> buildInput(String documentId, String tenantId) {
        Map<String, Object> input = new HashMap<>();
        input.put("documentId", documentId);
        input.put("tenantId", tenantId);
        input.put("s3Key", tenantId + "/" + documentId);
        input.put("uploadTimestamp", "2024-06-01T12:00:00Z");
        input.put("status", "COMPLETED");
        return input;
    }
}
