package com.idp.pipeline.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.CategoryCandidate;
import com.idp.common.model.KPI;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Calls the SageMaker endpoint for document classification and KPI extraction.
 *
 * <p>Sends raw text to the endpoint and parses the JSON response containing:
 * category, confidence, candidates (top-3), and kpis.
 */
public class SageMakerClassifierClient {

    private static final Logger log = Logger.getLogger(SageMakerClassifierClient.class.getName());

    private final SageMakerRuntimeClient sageMakerClient;
    private final ObjectMapper objectMapper;

    public SageMakerClassifierClient(SageMakerRuntimeClient sageMakerClient, ObjectMapper objectMapper) {
        this.sageMakerClient = sageMakerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Invokes the SageMaker endpoint and returns the parsed classification response.
     *
     * @param rawText       the document text to classify
     * @param endpointName  the SageMaker endpoint name
     * @param modelVersion  optional model version (null to use endpoint default)
     * @return parsed {@link SageMakerResponse}
     */
    public SageMakerResponse classify(String rawText, String endpointName, String modelVersion) throws Exception {
        InvokeEndpointRequest.Builder requestBuilder = InvokeEndpointRequest.builder()
                .endpointName(endpointName)
                .contentType("text/plain")
                .accept("application/json")
                .body(SdkBytes.fromString(rawText != null ? rawText : "", StandardCharsets.UTF_8));

        if (modelVersion != null && !modelVersion.isBlank()) {
            requestBuilder.targetVariant(modelVersion);
        }

        InvokeEndpointResponse response = sageMakerClient.invokeEndpoint(requestBuilder.build());
        String responseBody = response.body().asUtf8String();

        log.info("SageMaker response received, parsing...");
        return parseResponse(responseBody);
    }

    private SageMakerResponse parseResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        String category = root.path("category").asText("altro");
        double confidence = root.path("confidence").asDouble(0.0);

        List<CategoryCandidate> candidates = new ArrayList<>();
        JsonNode candidatesNode = root.path("candidates");
        if (candidatesNode.isArray()) {
            for (JsonNode c : candidatesNode) {
                candidates.add(CategoryCandidate.builder()
                        .category(c.path("category").asText())
                        .confidenceScore(c.path("confidence").asDouble(0.0))
                        .build());
            }
        }

        List<KPI> kpis = new ArrayList<>();
        JsonNode kpisNode = root.path("kpis");
        if (kpisNode.isArray()) {
            for (JsonNode k : kpisNode) {
                String valueStr = k.path("value").asText("0");
                BigDecimal value;
                try {
                    value = new BigDecimal(valueStr);
                } catch (NumberFormatException e) {
                    value = BigDecimal.ZERO;
                }
                kpis.add(KPI.builder()
                        .name(k.path("name").asText())
                        .value(value)
                        .unit(k.path("unit").asText())
                        .confidenceScore(k.path("confidence").asDouble(0.0))
                        .build());
            }
        }

        return new SageMakerResponse(category, confidence, candidates, kpis);
    }
}
