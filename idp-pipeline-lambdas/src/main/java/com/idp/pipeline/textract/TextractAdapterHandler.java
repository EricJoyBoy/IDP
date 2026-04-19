package com.idp.pipeline.textract;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.DocumentDTO;
import com.idp.common.model.DocumentStatus;
import com.idp.common.model.ExtractedContent;
import com.idp.common.serialization.DocumentObjectMapper;
import com.idp.common.validation.DocumentSchemaValidator;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.TextractException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that invokes Amazon Textract AnalyzeDocument API and normalizes
 * the output into an ExtractedContent DTO.
 *
 * <p>Input map keys: documentId, tenantId, s3Key, uploadTimestamp
 * <p>Output map keys: documentId, tenantId, s3Key, uploadTimestamp, status, content (JSON)
 */
public class TextractAdapterHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(TextractAdapterHandler.class.getName());

    private final TextractClient textractClient;
    private final TextractOutputParser parser;
    private final ObjectMapper objectMapper;
    private final String s3Bucket;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public TextractAdapterHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;
        this.s3Bucket = System.getenv("S3_BUCKET");
        this.textractClient = TextractClient.builder().region(awsRegion).build();
        this.parser = new TextractOutputParser();
        this.objectMapper = DocumentObjectMapper.getInstance();
    }

    /** Constructor for testing — allows injection of dependencies. */
    public TextractAdapterHandler(TextractClient textractClient, TextractOutputParser parser,
                                   ObjectMapper objectMapper, String s3Bucket) {
        this.textractClient = textractClient;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.s3Bucket = s3Bucket;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");
        String s3Key = (String) input.get("s3Key");
        String uploadTimestampStr = (String) input.get("uploadTimestamp");

        log.info(String.format("TextractAdapter invoked: documentId=%s tenantId=%s s3Key=%s",
                documentId, tenantId, s3Key));

        try {
            // Invoke Textract AnalyzeDocument with FORMS and TABLES
            AnalyzeDocumentRequest request = AnalyzeDocumentRequest.builder()
                    .document(Document.builder()
                            .s3Object(S3Object.builder()
                                    .bucket(s3Bucket)
                                    .name(s3Key)
                                    .build())
                            .build())
                    .featureTypes(FeatureType.FORMS, FeatureType.TABLES)
                    .build();

            AnalyzeDocumentResponse response = textractClient.analyzeDocument(request);
            List<Block> blocks = response.blocks();

            log.info(String.format("Textract returned %d blocks for documentId=%s", blocks.size(), documentId));

            // Parse blocks into ExtractedContent DTO
            ExtractedContent content = parser.parse(blocks);

            // Build DocumentDTO for schema validation
            Instant uploadTimestamp = uploadTimestampStr != null
                    ? Instant.parse(uploadTimestampStr)
                    : Instant.now();

            DocumentDTO dto = DocumentDTO.builder()
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .s3Key(s3Key)
                    .uploadTimestamp(uploadTimestamp)
                    .status(DocumentStatus.PROCESSING)
                    .content(content)
                    .build();

            if (!DocumentSchemaValidator.isValid(dto)) {
                log.warning("DocumentDTO failed schema validation for documentId=" + documentId);
            }

            // Build output map
            Map<String, Object> output = new HashMap<>(input);
            output.put("status", DocumentStatus.PROCESSING.name());
            output.put("content", objectMapper.convertValue(content, Map.class));

            log.info("TextractAdapter completed successfully for documentId=" + documentId);
            return output;

        } catch (TextractException e) {
            log.severe(String.format("Textract error for documentId=%s: %s", documentId, e.getMessage()));
            throw new RuntimeException("Textract extraction failed for documentId=" + documentId, e);
        } catch (Exception e) {
            log.severe(String.format("Unexpected error for documentId=%s: %s", documentId, e.getMessage()));
            throw new RuntimeException("TextractAdapter failed for documentId=" + documentId, e);
        }
    }
}
