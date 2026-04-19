package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the bounding box coordinates of an element within a document page.
 * Coordinates are normalized (0.0 to 1.0) relative to page dimensions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoundingBox {

    @JsonProperty("left")
    private Double left;

    @JsonProperty("top")
    private Double top;

    @JsonProperty("width")
    private Double width;

    @JsonProperty("height")
    private Double height;

    @JsonProperty("page")
    private Integer page;
}
