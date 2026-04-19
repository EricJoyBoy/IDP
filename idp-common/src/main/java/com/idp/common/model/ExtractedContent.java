package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Contains all content extracted from a document by the Textract Adapter,
 * including raw text, key-value pairs, tables, and bounding boxes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedContent {

    @JsonProperty("rawText")
    private String rawText;

    @JsonProperty("keyValuePairs")
    private List<KeyValuePair> keyValuePairs;

    @JsonProperty("tables")
    private List<Table> tables;

    @JsonProperty("boundingBoxes")
    private List<BoundingBox> boundingBoxes;
}
