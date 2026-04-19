package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a key-value pair extracted from a document form field,
 * along with its confidence score and bounding box location.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyValuePair {

    @JsonProperty("key")
    private String key;

    @JsonProperty("value")
    private String value;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;

    @JsonProperty("keyBoundingBox")
    private BoundingBox keyBoundingBox;

    @JsonProperty("valueBoundingBox")
    private BoundingBox valueBoundingBox;
}
