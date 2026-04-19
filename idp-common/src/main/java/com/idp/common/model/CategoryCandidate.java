package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a candidate document category with its confidence score,
 * used when the primary classification confidence is below the threshold.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCandidate {

    @JsonProperty("category")
    private String category;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;
}
