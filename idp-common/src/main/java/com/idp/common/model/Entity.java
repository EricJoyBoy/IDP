package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a named entity extracted by the NLP Processor (Amazon Comprehend).
 * Entity types include: organizations, dates, monetary amounts, percentages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Entity {

    /** Entity type: ORGANIZATION, DATE, QUANTITY, OTHER */
    @JsonProperty("type")
    private String type;

    @JsonProperty("value")
    private String value;

    @JsonProperty("position")
    private BoundingBox position;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;
}
