package com.idp.pipeline.eventrouter;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3BucketEntity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3Entity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3ObjectEntity;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that two S3 events with the same document_id do not start two Step Functions executions.
 * Validates: Requirements 2.4
 */
@ExtendWith(MockitoExtension.class)
class EventRouterIdempotencyTest {

    @Mock
    private IdempotencyChecker idempotencyChecker;

    @Mock
    private StepFunctionsStarter stepFunctionsStarter;

    @Mock
    private DlqSender dlqSender;

    private EventRouterHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EventRouterHandler(idempotencyChecker, stepFunctionsStarter, dlqSender);
    }

    @Test
    void firstEvent_startsExecution_whenNoActiveExecutionExists() throws Exception {
        when(idempotencyChecker.hasActiveExecution("tenant1", "doc-abc")).thenReturn(false);
        when(stepFunctionsStarter.startExecution(anyString(), anyString())).thenReturn("arn:aws:states:...");

        S3Event event = buildS3Event("tenant1/doc-abc");
        String result = handler.handleRequest(event, null);

        verify(stepFunctionsStarter, times(1)).startExecution(eq("doc-abc"), anyString());
        assertThat(result).contains("processed=1").contains("skipped=0");
    }

    @Test
    void secondEvent_skipsExecution_whenActiveExecutionAlreadyExists() throws Exception {
        // First call: no active execution → starts
        // Second call: active execution exists → skips
        when(idempotencyChecker.hasActiveExecution("tenant1", "doc-abc"))
                .thenReturn(false)
                .thenReturn(true);
        when(stepFunctionsStarter.startExecution(anyString(), anyString())).thenReturn("arn:aws:states:...");

        S3Event firstEvent = buildS3Event("tenant1/doc-abc");
        handler.handleRequest(firstEvent, null);

        S3Event secondEvent = buildS3Event("tenant1/doc-abc");
        String result = handler.handleRequest(secondEvent, null);

        // Step Functions should only be called once (for the first event)
        verify(stepFunctionsStarter, times(1)).startExecution(anyString(), anyString());
        assertThat(result).contains("processed=0").contains("skipped=1");
    }

    @Test
    void twoDistinctDocuments_bothStartExecutions() throws Exception {
        when(idempotencyChecker.hasActiveExecution(anyString(), anyString())).thenReturn(false);
        when(stepFunctionsStarter.startExecution(anyString(), anyString())).thenReturn("arn:aws:states:...");

        handler.handleRequest(buildS3Event("tenant1/doc-001"), null);
        handler.handleRequest(buildS3Event("tenant1/doc-002"), null);

        verify(stepFunctionsStarter, times(1)).startExecution(eq("doc-001"), anyString());
        verify(stepFunctionsStarter, times(1)).startExecution(eq("doc-002"), anyString());
    }

    @Test
    void onStepFunctionsFailure_sendsToDlq_andDoesNotThrow() throws Exception {
        when(idempotencyChecker.hasActiveExecution(anyString(), anyString())).thenReturn(false);
        when(stepFunctionsStarter.startExecution(anyString(), anyString()))
                .thenThrow(new RuntimeException("SFN unavailable"));

        S3Event event = buildS3Event("tenant1/doc-fail");
        String result = handler.handleRequest(event, null);

        verify(dlqSender, times(1)).send(eq("tenant1"), eq("doc-fail"), anyString(), any(), anyString());
        assertThat(result).contains("processed=0");
    }

    @Test
    void invalidS3KeyFormat_isSkipped_gracefully() {
        S3Event event = buildS3Event("no-slash-key");
        // Should not throw
        String result = handler.handleRequest(event, null);
        verifyNoInteractions(stepFunctionsStarter);
        assertThat(result).contains("processed=0").contains("skipped=0");
    }

    // ---- helpers ----

    private S3Event buildS3Event(String s3Key) {
        S3ObjectEntity objectEntity = new S3ObjectEntity(s3Key, 1024L, null, null, null);
        S3BucketEntity bucketEntity = new S3BucketEntity("my-bucket", null, null);
        S3Entity s3Entity = new S3Entity(null, bucketEntity, objectEntity, null);

        S3EventNotificationRecord record = new S3EventNotificationRecord(
                "us-east-1",
                "ObjectCreated:Put",
                "aws:s3",
                DateTime.now().toString(),
                "2.1",
                null,
                null,
                s3Entity,
                null
        );

        return new S3Event(List.of(record));
    }
}
