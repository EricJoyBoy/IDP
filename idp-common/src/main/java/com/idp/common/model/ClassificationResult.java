package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents the ML classification result for a document.
 * If confidence is below 0.7, topCandidates contains the top-3 alternative categories.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationResult {

    /** Document category: bilancio, conto_economico, rendiconto, contratto, fattura, altro */
    @JsonProperty("category")
    private String category;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;

    /** Top-3 candidates populated when confidenceScore < 0.7 */
    @JsonProperty("topCandidates")
    private List<CategoryCandidate> topCandidates;
}
