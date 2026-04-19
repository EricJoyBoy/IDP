package com.idp.pipeline.classifier;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.CategoryCandidate;
import com.idp.common.model.ClassificationResult;
import com.idp.common.model.DocumentStatus;
import com.idp.common.model.KPI;
import com.idp.common.serialization.DocumentObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lambda handler that invokes a SageMaker endpoint for document classification and KPI extraction.
 *
 * <p>Classifies documents into: bilancio, conto_economico, rendiconto, contratto, fattura, altro.
 * Extracts financial KPIs (ricavi, EBITDA, utile_netto, totale_attivo) with value, unit and confidence.
 *
 * <p>Confidence threshold: if confidence &lt; 0.7, document is marked {@code NEEDS_REVIEW} and
 * top-3 candidate categories are included in the result.
 *
 * <p>Model version is resolved per-tenant: reads {@code modelVersion} from the input map first,
 * then falls back to the {@code SAGEMAKER_ENDPOINT_VERSION} environment variable.
 *
 * <p>Implements Circuit Breaker via Resilience4j:
 * <ul>
 *   <li>Opens after 5 consecutive failures</li>
 *   <li>Stays open for 60 seconds</li>
 *   <li>Fallback: returns partial result with {@code NEEDS_REVIEW} status</li>
 * </ul>
 *
 * <p>Input map keys: documentId, tenantId, s3Key, uploadTimestamp, status, content, entities, modelVersion (optional)
 * <p>Output map keys: all input keys + classification (Map), kpis (List), status (updated)
 */
public class MLClassifierHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = Logger.getLogger(MLClassifierHandler.class.getName());

    static final double CONFIDENCE_THRESHOLD = 0.7;

    private final SageMakerClassifierClient classifierClient;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final String defaultEndpointName;
    private final String defaultModelVersion;

    /** Default constructor used by Lambda runtime — reads config from env vars. */
    public MLClassifierHandler() {
        String region = System.getenv("AWS_REGION");
        Region awsRegion = region != null ? Region.of(region) : Region.US_EAST_1;

        SageMakerRuntimeClient sageMakerClient = SageMakerRuntimeClient.builder()
                .region(awsRegion)
                .build();

        this.objectMapper = DocumentObjectMapper.getInstance();
        this.classifierClient = new SageMakerClassifierClient(sageMakerClient, objectMapper);
        this.circuitBreaker = ClassifierCircuitBreakerConfig.create();
        this.defaultEndpointName = resolveEnv("SAGEMAKER_ENDPOINT_NAME", "idp-ml-classifier");
        this.defaultModelVersion = System.getenv("SAGEMAKER_ENDPOINT_VERSION");
    }

    /** Constructor for testing — allows injection of dependencies. */
    public MLClassifierHandler(SageMakerClassifierClient classifierClient,
                                CircuitBreaker circuitBreaker,
                                ObjectMapper objectMapper,
                                String defaultEndpointName,
                                String defaultModelVersion) {
        this.classifierClient = classifierClient;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.defaultEndpointName = defaultEndpointName;
        this.defaultModelVersion = defaultModelVersion;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String documentId = (String) input.get("documentId");
        String tenantId = (String) input.get("tenantId");

        log.info(String.format("MLClassifier invoked: documentId=%s tenantId=%s", documentId, tenantId));

        Map<String, Object> output = new HashMap<>(input);

        // Resolve model version: input map takes precedence over env var (tenant-specific)
        String modelVersion = input.containsKey("modelVersion")
                ? (String) input.get("modelVersion")
                : defaultModelVersion;

        String rawText = extractRawText(input);

        try {
            SageMakerResponse smResponse = CircuitBreaker.decorateCheckedSupplier(
                    circuitBreaker,
                    () -> classifierClient.classify(rawText, defaultEndpointName, modelVersion)
            ).get();

            applyClassificationResult(output, smResponse, documentId);

        } catch (CallNotPermittedException e) {
            log.warning(String.format(
                    "Circuit breaker OPEN for SageMaker, returning partial result for documentId=%s", documentId));
            applyFallback(output);

        } catch (Exception e) {
            log.severe(String.format("SageMaker error for documentId=%s: %s", documentId, e.getMessage()));
            throw new RuntimeException("MLClassifier failed for documentId=" + documentId, e);
        }

        return output;
    }

    private void applyClassificationResult(Map<String, Object> output, SageMakerResponse smResponse,
                                            String documentId) {
        double confidence = smResponse.getConfidence();
        boolean needsReview = confidence < CONFIDENCE_THRESHOLD;

        ClassificationResult.ClassificationResultBuilder builder = ClassificationResult.builder()
                .category(smResponse.getCategory())
                .confidenceScore(confidence);

        if (needsReview) {
            builder.topCandidates(smResponse.getCandidates());
            output.put("status", DocumentStatus.NEEDS_REVIEW.name());
            log.info(String.format(
                    "documentId=%s classified as %s with low confidence %.3f → NEEDS_REVIEW",
                    documentId, smResponse.getCategory(), confidence));
        } else {
            output.put("status", DocumentStatus.PROCESSING.name());
            log.info(String.format(
                    "documentId=%s classified as %s with confidence %.3f",
                    documentId, smResponse.getCategory(), confidence));
        }

        ClassificationResult result = builder.build();
        output.put("classification", objectMapper.convertValue(result, Map.class));
        output.put("kpis", objectMapper.convertValue(smResponse.getKpis(), List.class));
    }

    private void applyFallback(Map<String, Object> output) {
        ClassificationResult fallback = ClassificationResult.builder()
                .category("altro")
                .confidenceScore(0.0)
                .topCandidates(new ArrayList<>())
                .build();
        output.put("classification", objectMapper.convertValue(fallback, Map.class));
        output.put("kpis", new ArrayList<>());
        output.put("status", DocumentStatus.NEEDS_REVIEW.name());
    }

    @SuppressWarnings("unchecked")
    private String extractRawText(Map<String, Object> input) {
        Object contentObj = input.get("content");
        if (contentObj == null) return "";
        if (contentObj instanceof Map) {
            Object rawText = ((Map<String, Object>) contentObj).get("rawText");
            return rawText != null ? rawText.toString() : "";
        }
        return "";
    }

    private static String resolveEnv(String name, String defaultValue) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
