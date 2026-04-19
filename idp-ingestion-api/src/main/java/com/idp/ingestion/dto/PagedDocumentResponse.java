package com.idp.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idp.common.model.DocumentDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response for GET /api/v1/documents.
 * Requirements: 8.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedDocumentResponse {

    @JsonProperty("items")
    private List<DocumentDTO> items;

    @JsonProperty("page")
    private int page;

    @JsonProperty("size")
    private int size;

    @JsonProperty("totalItems")
    private long totalItems;

    @JsonProperty("lastEvaluatedKey")
    private String lastEvaluatedKey;
}
