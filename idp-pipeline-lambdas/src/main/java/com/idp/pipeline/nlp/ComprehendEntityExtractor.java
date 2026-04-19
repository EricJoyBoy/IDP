package com.idp.pipeline.nlp;

import com.idp.common.model.Entity;
import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.DetectEntitiesRequest;
import software.amazon.awssdk.services.comprehend.model.DetectEntitiesResponse;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Calls Amazon Comprehend DetectEntities API and maps results to the IDP Entity model.
 *
 * <p>Extracts entity types: ORGANIZATION, DATE, QUANTITY (covers monetary amounts and percentages).
 * Position (BoundingBox) is null for NLP entities as Comprehend returns character offsets, not spatial coordinates.
 */
public class ComprehendEntityExtractor {

    private static final Logger log = Logger.getLogger(ComprehendEntityExtractor.class.getName());

    private static final Set<String> SUPPORTED_TYPES = Set.of("ORGANIZATION", "DATE", "QUANTITY");

    private final ComprehendClient comprehendClient;
    private final String languageCode;

    public ComprehendEntityExtractor(ComprehendClient comprehendClient, String languageCode) {
        this.comprehendClient = comprehendClient;
        this.languageCode = languageCode;
    }

    /**
     * Detects entities in the given text and returns only the supported entity types.
     *
     * @param text the raw text to analyze
     * @return list of extracted Entity objects
     */
    public List<Entity> extract(String text) {
        if (text == null || text.isBlank()) {
            log.info("Empty text provided to ComprehendEntityExtractor, returning empty list");
            return List.of();
        }

        DetectEntitiesRequest request = DetectEntitiesRequest.builder()
                .text(text)
                .languageCode(languageCode)
                .build();

        DetectEntitiesResponse response = comprehendClient.detectEntities(request);

        log.info(String.format("Comprehend returned %d entities", response.entities().size()));

        return response.entities().stream()
                .filter(e -> SUPPORTED_TYPES.contains(e.typeAsString()))
                .map(e -> Entity.builder()
                        .type(e.typeAsString())
                        .value(e.text())
                        .confidenceScore((double) e.score())
                        .position(null) // NLP entities have no spatial bounding box
                        .build())
                .collect(Collectors.toList());
    }
}
