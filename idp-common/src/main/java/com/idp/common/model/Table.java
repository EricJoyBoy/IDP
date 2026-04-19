package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a table extracted from a document, including its rows, columns,
 * bounding box, and confidence score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Table {

    @JsonProperty("tableId")
    private String tableId;

    @JsonProperty("rows")
    private List<List<String>> rows;

    @JsonProperty("columnHeaders")
    private List<String> columnHeaders;

    @JsonProperty("boundingBox")
    private BoundingBox boundingBox;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;

    @JsonProperty("page")
    private Integer page;
}
