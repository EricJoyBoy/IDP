package com.idp.pipeline.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RDS failure handling in PersistenceHandler.
 *
 * Validates Requirements 7.6: on RDS failure, transaction is rolled back
 * and document is marked PERSISTENCE_ERROR in DynamoDB.
 */
@ExtendWith(MockitoExtension.class)
class PersistenceHandlerRollbackTest {

    @Mock
    private DynamoDbPersistenceService dynamoDbService;

    @Mock
    private RdsPersistenceService rdsService;

    @Mock
    private DataLakeArchiver dataLakeArchiver;

    private PersistenceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PersistenceHandler(dynamoDbService, rdsService, dataLakeArchiver);
    }

    private Map<String, Object> buildInput() {
        Map<String, Object> classification = new HashMap<>();
        classification.put("category", "bilancio");
        classification.put("confidenceScore", 0.95);

        Map<String, Object> kpi = new HashMap<>();
        kpi.put("name", "ricavi");
        kpi.put("value", "1000000");
        kpi.put("unit", "EUR");
        kpi.put("confidenceScore", 0.9);

        Map<String, Object> input = new HashMap<>();
        input.put("documentId", "doc-123");
        input.put("tenantId", "tenant-abc");
        input.put("s3Key", "tenant-abc/doc-123");
        input.put("uploadTimestamp", "2024-01-15T10:00:00Z");
        input.put("status", "COMPLETED");
        input.put("classification", classification);
        input.put("kpis", List.of(kpi));
        return input;
    }

    @Test
    void whenRdsFails_thenMarksPersistenceErrorInDynamoDb() {
        Map<String, Object> input = buildInput();

        doThrow(new RdsPersistenceException("DB down", new RuntimeException("connection refused")))
                .when(rdsService).persist(anyString(), anyString(), any(), any(), anyString());

        assertThatThrownBy(() -> handler.handleRequest(input, null))
                .isInstanceOf(RdsPersistenceException.class);

        // DynamoDB must be updated to PERSISTENCE_ERROR
        verify(dynamoDbService).markPersistenceError("tenant-abc", "doc-123");
    }

    @Test
    void whenRdsFails_thenDataLakeArchivalIsSkipped() {
        Map<String, Object> input = buildInput();

        doThrow(new RdsPersistenceException("DB down", new RuntimeException()))
                .when(rdsService).persist(anyString(), anyString(), any(), any(), anyString());

        assertThatThrownBy(() -> handler.handleRequest(input, null))
                .isInstanceOf(RdsPersistenceException.class);

        // Data Lake archival must NOT be called after RDS failure
        verify(dataLakeArchiver, never()).archive(any(), anyString());
    }

    @Test
    void whenAllSucceed_thenOutputContainsPersistedAt() {
        Map<String, Object> input = buildInput();

        Map<String, Object> output = handler.handleRequest(input, null);

        verify(dynamoDbService).persist(eq(input), anyString());
        verify(rdsService).persist(eq("doc-123"), eq("tenant-abc"), any(), any(), anyString());
        verify(dataLakeArchiver).archive(eq(input), anyString());

        assertThat(output).containsKey("persistedAt");
        assertThat(output.get("documentId")).isEqualTo("doc-123");
        assertThat(output.get("tenantId")).isEqualTo("tenant-abc");
    }

    @Test
    void whenRdsFails_thenDynamoDbIsWrittenBeforeError() {
        Map<String, Object> input = buildInput();

        doThrow(new RdsPersistenceException("DB down", new RuntimeException()))
                .when(rdsService).persist(anyString(), anyString(), any(), any(), anyString());

        assertThatThrownBy(() -> handler.handleRequest(input, null))
                .isInstanceOf(RdsPersistenceException.class);

        // DynamoDB initial write must have happened before the error
        verify(dynamoDbService).persist(eq(input), anyString());
        verify(dynamoDbService).markPersistenceError("tenant-abc", "doc-123");
    }

}
