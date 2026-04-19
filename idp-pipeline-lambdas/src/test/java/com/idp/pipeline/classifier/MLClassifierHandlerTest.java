package com.idp.pipeline.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.CategoryCandidate;
import com.idp.common.model.DocumentStatus;
import com.idp.common.model.KPI;
import com.idp.common.serialization.DocumentObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MLClassifierHandlerTest {

    @Mock
    private SageMakerClassifierClient classifierClient;

    private ObjectMapper objectMapper;
    private MLClassifierHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = DocumentObjectMapper.getInstance();
        CircuitBreaker circuitBreaker = ClassifierCircuitBreakerConfig.create();
        handler = new MLClassifierHandler(classifierClient, circuitBreaker, objectMapper,
                "test-endpoint", null);
    }

    private Map<String, Object> buildInput(String documentId, String tenantId, String rawText) {
        Map<String, Object> content = new HashMap<>();
        content.put("rawText", rawText);

        Map<String, Object> input = new HashMap<>();
        input.put("documentId", documentId);
        input.put("tenantId", tenantId);
        input.put("s3Key", tenantId + "/" + documentId);
        input.put("status", "PROCESSING");
        input.put("content", content);
        return input;
    }

    // -------------------------------------------------------------------------
    // Normal classification (confidence >= 0.7)
    // -------------------------------------------------------------------------

    @Test
    void handleRequest_highConfidence_setsProcessingStatusAndClassification() throws Exception {
        List<KPI> kpis = List.of(
                KPI.builder().name("ricavi").value(new BigDecimal("1000000")).unit("EUR").confidenceScore(0.95).build()
        );
        SageMakerResponse smResponse = new SageMakerResponse("bilancio", 0.92, List.of(), kpis);
        when(classifierClient.classify(anyString(), anyString(), any())).thenReturn(smResponse);

        Map<String, Object> result = handler.handleRequest(buildInput("doc-1", "tenant-A", "testo bilancio"), null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.PROCESSING.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) result.get("classification");
        assertThat(classification.get("category")).isEqualTo("bilancio");
        assertThat((Double) classification.get("confidenceScore")).isGreaterThanOrEqualTo(0.7);

        @SuppressWarnings("unchecked")
        List<?> resultKpis = (List<?>) result.get("kpis");
        assertThat(resultKpis).hasSize(1);
    }

    @Test
    void handleRequest_confidenceExactlyAtThreshold_setsProcessingStatus() throws Exception {
        SageMakerResponse smResponse = new SageMakerResponse("fattura", 0.7, List.of(), List.of());
        when(classifierClient.classify(anyString(), anyString(), any())).thenReturn(smResponse);

        Map<String, Object> result = handler.handleRequest(buildInput("doc-2", "tenant-B", "fattura testo"), null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.PROCESSING.name());
    }

    // -------------------------------------------------------------------------
    // NEEDS_REVIEW when confidence < 0.7
    // -------------------------------------------------------------------------

    @Test
    void handleRequest_lowConfidence_setsNeedsReviewWithTopCandidates() throws Exception {
        List<CategoryCandidate> candidates = List.of(
                CategoryCandidate.builder().category("bilancio").confidenceScore(0.45).build(),
                CategoryCandidate.builder().category("conto_economico").confidenceScore(0.30).build(),
                CategoryCandidate.builder().category("rendiconto").confidenceScore(0.15).build()
        );
        SageMakerResponse smResponse = new SageMakerResponse("bilancio", 0.45, candidates, List.of());
        when(classifierClient.classify(anyString(), anyString(), any())).thenReturn(smResponse);

        Map<String, Object> result = handler.handleRequest(buildInput("doc-3", "tenant-C", "testo ambiguo"), null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.NEEDS_REVIEW.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) result.get("classification");
        assertThat(classification.get("category")).isEqualTo("bilancio");
        assertThat((Double) classification.get("confidenceScore")).isLessThan(0.7);

        @SuppressWarnings("unchecked")
        List<?> topCandidates = (List<?>) classification.get("topCandidates");
        assertThat(topCandidates).hasSize(3);
    }

    @Test
    void handleRequest_lowConfidence_justBelowThreshold_setsNeedsReview() throws Exception {
        List<CategoryCandidate> candidates = List.of(
                CategoryCandidate.builder().category("contratto").confidenceScore(0.69).build(),
                CategoryCandidate.builder().category("fattura").confidenceScore(0.20).build(),
                CategoryCandidate.builder().category("altro").confidenceScore(0.11).build()
        );
        SageMakerResponse smResponse = new SageMakerResponse("contratto", 0.69, candidates, List.of());
        when(classifierClient.classify(anyString(), anyString(), any())).thenReturn(smResponse);

        Map<String, Object> result = handler.handleRequest(buildInput("doc-4", "tenant-D", "testo contratto"), null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.NEEDS_REVIEW.name());
    }

    // -------------------------------------------------------------------------
    // Circuit breaker fallback
    // -------------------------------------------------------------------------

    @Test
    void handleRequest_circuitBreakerOpen_returnsFallbackWithNeedsReview() throws Exception {
        // Force circuit breaker open by triggering 5 consecutive failures
        CircuitBreaker openCb = buildOpenCircuitBreaker();
        MLClassifierHandler handlerWithOpenCb = new MLClassifierHandler(
                classifierClient, openCb, objectMapper, "test-endpoint", null);

        Map<String, Object> result = handlerWithOpenCb.handleRequest(
                buildInput("doc-5", "tenant-E", "testo"), null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.NEEDS_REVIEW.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) result.get("classification");
        assertThat(classification.get("category")).isEqualTo("altro");
        assertThat((Double) classification.get("confidenceScore")).isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        List<?> kpis = (List<?>) result.get("kpis");
        assertThat(kpis).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Tenant-specific model version
    // -------------------------------------------------------------------------

    @Test
    void handleRequest_modelVersionFromInput_overridesDefault() throws Exception {
        SageMakerResponse smResponse = new SageMakerResponse("bilancio", 0.88, List.of(), List.of());
        when(classifierClient.classify(anyString(), anyString(), anyString())).thenReturn(smResponse);

        Map<String, Object> input = buildInput("doc-6", "tenant-F", "testo");
        input.put("modelVersion", "v2");

        Map<String, Object> result = handler.handleRequest(input, null);

        assertThat(result.get("status")).isEqualTo(DocumentStatus.PROCESSING.name());
    }

    @Test
    void handleRequest_preservesAllInputKeys() throws Exception {
        SageMakerResponse smResponse = new SageMakerResponse("fattura", 0.80, List.of(), List.of());
        when(classifierClient.classify(anyString(), anyString(), any())).thenReturn(smResponse);

        Map<String, Object> input = buildInput("doc-7", "tenant-G", "testo fattura");
        input.put("uploadTimestamp", "2024-01-01T00:00:00Z");
        input.put("entities", List.of());

        Map<String, Object> result = handler.handleRequest(input, null);

        assertThat(result).containsKey("documentId");
        assertThat(result).containsKey("tenantId");
        assertThat(result).containsKey("s3Key");
        assertThat(result).containsKey("uploadTimestamp");
        assertThat(result).containsKey("entities");
        assertThat(result).containsKey("classification");
        assertThat(result).containsKey("kpis");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a CircuitBreaker that is already in OPEN state by using a zero-threshold config
     * and recording failures.
     */
    private CircuitBreaker buildOpenCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(1)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("sagemaker-test-open");
        // Record one failure to open the circuit
        cb.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("forced open"));
        return cb;
    }
}
