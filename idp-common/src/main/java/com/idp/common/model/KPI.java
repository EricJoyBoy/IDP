package com.idp.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a financial KPI extracted from a document.
 * KPI names include: ricavi, EBITDA, utile_netto, totale_attivo.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KPI {

    /** KPI name: ricavi, EBITDA, utile_netto, totale_attivo */
    @JsonProperty("name")
    private String name;

    @JsonProperty("value")
    private BigDecimal value;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("confidenceScore")
    private Double confidenceScore;
}
