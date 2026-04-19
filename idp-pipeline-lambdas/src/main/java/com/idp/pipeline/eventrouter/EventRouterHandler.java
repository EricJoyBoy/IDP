package com.idp.pipeline.eventrouter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Lambda handler triggered by S3 ObjectCreated events.
 * Extracts document metadata and starts a Step Functions execution,
 * with idempotency check and DLQ fallback on failure.
 */
public class EventRouterHandler implements RequestHandler<S3Event, String> {

    private static final Logger log = Logger.getLogger(EventRouterHandler.class.getName());

    private final IdempotencyChecker idempotencyChecker;
    private final StepFunctionsStarter stepFunctionsStarter;
    private final DlqSender dlqSender;
    private final ObjectMapper objectMapper;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public EventRouterHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;

        DynamoDbClient dynamoDb = DynamoDbClient.builder().region(awsRegion).build();
        SfnClient sfn = SfnClient.builder().region(awsRegion).build();
        SqsClient sqs = SqsClient.builder().region(awsRegion).build();

        String dynamoTable = System.getenv("DYNAMODB_TABLE");
        String sfnArn = System.getenv("STEP_FUNCTIONS_ARN");
        String dlqUrl = System.getenv("DLQ_URL");

        this.idempotencyChecker = new IdempotencyChecker(dynamoDb, dynamoTable);
        this.stepFunctionsStarter = new StepFunctionsStarter(sfn, sfnArn);
        this.dlqSender = new DlqSender(sqs, dlqUrl);
        this.objectMapper = buildObjectMapper();
    }

    /** Constructor for testing — allows injection of dependencies. */
    public EventRouterHandler(IdempotencyChecker idempotencyChecker,
                               StepFunctionsStarter stepFunctionsStarter,
                               DlqSender dlqSender) {
        this.idempotencyChecker = idempotencyChecker;
        this.stepFunctionsStarter = stepFunctionsStarter;
        this.dlqSender = dlqSender;
        this.objectMapper = buildObjectMapper();
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        int processed = 0;
        int skipped = 0;

        for (S3EventNotificationRecord record : event.getRecords()) {
            String s3Key = record.getS3().getObject().getKey();
            String bucket = record.getS3().getBucket().getName();
            Instant uploadTimestamp = record.getEventTime() != null
                    ? Instant.ofEpochMilli(record.getEventTime().getMillis())
                    : Instant.now();

            // S3 key format: {tenant_id}/{document_id}
            String[] parts = s3Key.split("/", 2);
            if (parts.length < 2) {
                log.warning("Unexpected S3 key format (expected tenant_id/document_id): " + s3Key);
                continue;
            }

            String tenantId = parts[0];
            String documentId = parts[1];

            log.info(String.format("Processing S3 event: bucket=%s key=%s tenantId=%s documentId=%s",
                    bucket, s3Key, tenantId, documentId));

            // Idempotency check
            if (idempotencyChecker.hasActiveExecution(tenantId, documentId)) {
                log.info("Skipping duplicate event — active execution already exists for documentId=" + documentId);
                skipped++;
                continue;
            }

            // Build Step Functions input payload
            StepFunctionsPayload payload = new StepFunctionsPayload(
                    documentId, tenantId, s3Key, uploadTimestamp);

            try {
                String payloadJson = objectMapper.writeValueAsString(payload);
                stepFunctionsStarter.startExecution(documentId, payloadJson);
                log.info("Step Functions execution started for documentId=" + documentId);
                processed++;
            } catch (Exception e) {
                log.severe("Failed to start Step Functions for documentId=" + documentId + ": " + e.getMessage());
                try {
                    dlqSender.send(tenantId, documentId, s3Key, uploadTimestamp, e.getMessage());
                } catch (Exception dlqEx) {
                    log.severe("Failed to send to DLQ for documentId=" + documentId + ": " + dlqEx.getMessage());
                }
            }
        }

        return String.format("processed=%d skipped=%d", processed, skipped);
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
