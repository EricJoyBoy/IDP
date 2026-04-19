package com.idp.pipeline.nlp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.Entity;
import com.idp.common.model.ExtractedContent;
import com.idp.common.serialization.DocumentObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.comprehend.ComprehendClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that invokes Amazon Comprehend for NLP entity extraction.
 *
 * <p>Extracts entities (ORGANIZATION, DATE, QUANTITY) from the document's raw text
 * and enriches the pipeline DTO with the recognized entities.
 *
 * <p>Implements Circuit Breaker via Resilience4j:
 * <ul>
 *   <li>Opens after 5 consecutive failures</li>
 *   <li>Stays open for 60 seconds</li>
 *   <li>When open: returns partial result with empty entities and sets {@code nlpDegraded=true}</li>
 * </ul>
 *
 * <p>Input map keys: documentId, tenantId, s3Key, uploadTimestamp, status, content
 * <p>Output map keys: all input keys + entities (list), nlpDegraded (boolean, only when degraded)
 */
public class NLPProcessorHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(NLPProcessorHandler.class.getName());

    private final ComprehendEntityExtractor extractor;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public NLPProcessorHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;
        String languageCode = System.getenv("COMPREHEND_LANGUAGE_CODE");
        if (languageCode == null || languageCode.isBlank()) {
            languageCode = "it";
        }

        ComprehendClient comprehendClient = ComprehendClient.builder()
                .region(awsRegion)
                .build();

        this.extractor = new ComprehendEntityExtractor(comprehendClient, languageCode);
        this.circuitBreaker = NlpCircuitBreakerConfig.create();
        this.objectMapper = DocumentObjectMapper.getInstance();
    }

    /** Constructor for testing — allows injection of dependencies. */
    public NLPProcessorHandler(ComprehendEntityExtractor extractor, CircuitBreaker circuitBreaker,
                                ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");

        log.info(String.format("NLPProcessor invoked: documentId=%s tenantId=%s", documentId, tenantId));

        Map<String, Object> output = new HashMap<>(input);

        try {
            String rawText = extractRawText(input);

            // Execute entity extraction through the circuit breaker
            List<Entity> entities = CircuitBreaker.decorateCheckedSupplier(
                    circuitBreaker, () -> extractor.extract(rawText)
            ).get();

            output.put("entities", objectMapper.convertValue(entities, List.class));
            log.info(String.format("NLPProcessor extracted %d entities for documentId=%s",
                    entities.size(), documentId));

        } catch (CallNotPermittedException e) {
            // Circuit breaker is open — return partial result and signal degradation
            log.warning(String.format(
                    "Circuit breaker OPEN for Comprehend, returning partial result for documentId=%s", documentId));
            output.put("entities", new ArrayList<>());
            output.put("nlpDegraded", true);

        } catch (Exception e) {
            // Record failure in circuit breaker and propagate
            log.severe(String.format("Comprehend error for documentId=%s: %s", documentId, e.getMessage()));
            throw new RuntimeException("NLPProcessor failed for documentId=" + documentId, e);
        }

        return output;
    }

    /**
     * Extracts the raw text from the content map in the input.
     */
    @SuppressWarnings("unchecked")
    private String extractRawText(Map<String, Object> input) {
        Object contentObj = input.get("content");
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof Map) {
            Object rawText = ((Map<String, Object>) contentObj).get("rawText");
            return rawText != null ? rawText.toString() : "";
        }
        // If content is already an ExtractedContent object (e.g. in tests)
        if (contentObj instanceof ExtractedContent) {
            String rawText = ((ExtractedContent) contentObj).getRawText();
            return rawText != null ? rawText : "";
        }
        return "";
    }
}
