package com.idp.pipeline.classifier;

import com.idp.common.model.CategoryCandidate;
import com.idp.common.model.KPI;

import java.util.List;

/**
 * Internal DTO representing the parsed response from the SageMaker classification endpoint.
 */
public class SageMakerResponse {

    private final String category;
    private final double confidence;
    private final List<CategoryCandidate> candidates;
    private final List<KPI> kpis;

    public SageMakerResponse(String category, double confidence,
                              List<CategoryCandidate> candidates, List<KPI> kpis) {
        this.category = category;
        this.confidence = confidence;
        this.candidates = candidates;
        this.kpis = kpis;
    }

    public String getCategory() { return category; }
    public double getConfidence() { return confidence; }
    public List<CategoryCandidate> getCandidates() { return candidates; }
    public List<KPI> getKpis() { return kpis; }
}
