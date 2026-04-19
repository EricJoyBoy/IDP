package com.idp.pipeline.eventrouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCheckerTest {

    @Mock
    private DynamoDbClient dynamoDb;

    private IdempotencyChecker checker;

    @BeforeEach
    void setUp() {
        checker = new IdempotencyChecker(dynamoDb, "idp-documents");
    }

    @Test
    void returnsTrue_whenStatusIsProcessing() {
        givenDynamoReturnsStatus("PROCESSING");
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isTrue();
    }

    @Test
    void returnsTrue_whenStatusIsCompleted() {
        givenDynamoReturnsStatus("COMPLETED");
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isTrue();
    }

    @Test
    void returnsFalse_whenStatusIsPending() {
        givenDynamoReturnsStatus("PENDING");
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isFalse();
    }

    @Test
    void returnsFalse_whenStatusIsFailed() {
        givenDynamoReturnsStatus("FAILED");
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isFalse();
    }

    @Test
    void returnsFalse_whenItemNotFound() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isFalse();
    }

    @Test
    void returnsFalse_whenItemHasNoStatusAttribute() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("pk", AttributeValue.fromS("tenant1#doc1")))
                        .build());
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isFalse();
    }

    @Test
    void returnsFalse_onDynamoDbException_failOpen() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenThrow(new RuntimeException("DynamoDB unavailable"));
        // fail-open: allow execution to proceed
        assertThat(checker.hasActiveExecution("tenant1", "doc1")).isFalse();
    }

    private void givenDynamoReturnsStatus(String status) {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of("status", AttributeValue.fromS(status)))
                        .build());
    }
}
