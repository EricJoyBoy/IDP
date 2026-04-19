package com.idp.pipeline.persistence;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that orchestrates persistence of document processing results.
 *
 * <p>Execution order:
 * <ol>
 *   <li>Write full {@code DocumentDTO} to DynamoDB (Metadata_Store) with composite key
 *       {@code tenant_id#document_id} and GSI attributes for status/type/date queries.</li>
 *   <li>Replicate KPIs and classification to RDS PostgreSQL in a single JDBC transaction.</li>
 *   <li>Archive the full payload to the S3 Data Lake at
 *       {@code {tenant_id}/{year}/{month}/{day}/{document_id}.json}.</li>
 * </ol>
 *
 * <p>If the RDS write fails the transaction is rolled back and the document is marked
 * {@code PERSISTENCE_ERROR} in DynamoDB before rethrowing the exception.
 *
 * <p>Input map keys: documentId, tenantId, s3Key, uploadTimestamp, status, content (Map),
 *   entities (List), classification (Map), kpis (List)
 * <p>Output map keys: all input keys + {@code persistedAt} (ISO-8601)
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code AWS_REGION}</li>
 *   <li>{@code DYNAMODB_TABLE}</li>
 *   <li>{@code DATA_LAKE_BUCKET}</li>
 *   <li>{@code RDS_JDBC_URL}</li>
 *   <li>{@code RDS_USERNAME}</li>
 *   <li>{@code RDS_PASSWORD}</li>
 * </ul>
 */
public class PersistenceHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(PersistenceHandler.class.getName());

    private final DynamoDbPersistenceService dynamoDbService;
    private final RdsPersistenceService rdsService;
    private final DataLakeArchiver dataLakeArchiver;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public PersistenceHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;

        DynamoDbClient dynamoDbClient = DynamoDbClient.builder().region(awsRegion).build();
        S3Client s3Client = S3Client.builder().region(awsRegion).build();

        String dynamoTable = System.getenv("DYNAMODB_TABLE");
        String dataLakeBucket = System.getenv("DATA_LAKE_BUCKET");
        String rdsJdbcUrl = System.getenv("RDS_JDBC_URL");
        String rdsUsername = System.getenv("RDS_USERNAME");
        String rdsPassword = System.getenv("RDS_PASSWORD");

        this.dynamoDbService = new DynamoDbPersistenceService(dynamoDbClient, dynamoTable);
        this.rdsService = new RdsPersistenceService(rdsJdbcUrl, rdsUsername, rdsPassword);
        this.dataLakeArchiver = new DataLakeArchiver(s3Client, dataLakeBucket);
    }

    /** Constructor for testing — allows injection of dependencies. */
    public PersistenceHandler(DynamoDbPersistenceService dynamoDbService,
                               RdsPersistenceService rdsService,
                               DataLakeArchiver dataLakeArchiver) {
        this.dynamoDbService = dynamoDbService;
        this.rdsService = rdsService;
        this.dataLakeArchiver = dataLakeArchiver;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");

        if (documentId == null || tenantId == null) {
            throw new IllegalArgumentException("Input must contain documentId and tenantId");
        }

        log.info(String.format("PersistenceHandler invoked: documentId=%s tenantId=%s", documentId, tenantId));

        String persistedAt = Instant.now().toString();

        // Step 1: Write to DynamoDB
        dynamoDbService.persist(input, persistedAt);

        // Step 2: Replicate to RDS (transactional) — on failure mark PERSISTENCE_ERROR and rethrow
        try {
            rdsService.persist(
                    documentId,
                    tenantId,
                    input.get("classification"),
                    input.get("kpis"),
                    persistedAt
            );
        } catch (RdsPersistenceException e) {
            log.severe(String.format(
                    "RDS persistence failed for documentId=%s, marking PERSISTENCE_ERROR: %s",
                    documentId, e.getMessage()));
            dynamoDbService.markPersistenceError(tenantId, documentId);
            throw e;
        }

        // Step 3: Archive to S3 Data Lake
        dataLakeArchiver.archive(input, persistedAt);

        log.info(String.format("Persistence complete for documentId=%s persistedAt=%s", documentId, persistedAt));

        Map<String, Object> output = new HashMap<>(input);
        output.put("persistedAt", persistedAt);
        return output;
    }
}
