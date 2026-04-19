package com.idp.common.observability;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Utility class that publishes custom CloudWatch metrics for the IDP pipeline.
 *
 * <p>Metrics namespace: {@code IDP/Pipeline}
 *
 * <p>Published metrics:
 * <ul>
 *   <li>{@code DocumentsProcessed}   — Count of documents that entered the pipeline</li>
 *   <li>{@code ProcessingSuccess}    — Count of successfully completed documents</li>
 *   <li>{@code ProcessingFailure}    — Count of failed documents</li>
 *   <li>{@code PhaseDurationMs}      — Milliseconds spent in a specific pipeline phase</li>
 *   <li>{@code EstimatedCostPerDocument} — Estimated AWS cost (USD) for one document</li>
 * </ul>
 *
 * <p>All metrics carry {@code TenantId} and (where applicable) {@code Phase} dimensions
 * so they can be filtered per tenant and per pipeline phase in CloudWatch.
 *
 * <p>Requirements: 11.3, 11.4, 11.5
 */
public final class CloudWatchMetricsPublisher {

    private static final Logger log = Logger.getLogger(CloudWatchMetricsPublisher.class.getName());

    /** CloudWatch custom metrics namespace. */
    public static final String NAMESPACE = "IDP/Pipeline";

    private final CloudWatchClient cloudWatch;

    /**
     * Creates a publisher using the provided {@link CloudWatchClient}.
     * Inject a real client in production; a mock/fake in tests.
     */
    public CloudWatchMetricsPublisher(CloudWatchClient cloudWatch) {
        this.cloudWatch = cloudWatch;
    }

    // -------------------------------------------------------------------------
    // Public metric methods
    // -------------------------------------------------------------------------

    /**
     * Records that a document entered the pipeline (count = 1).
     *
     * @param tenantId tenant that owns the document
     */
    public void recordDocumentProcessed(String tenantId) {
        publish("DocumentsProcessed", 1.0, StandardUnit.COUNT,
                dimension("TenantId", tenantId));
    }

    /**
     * Records a successful document processing completion.
     *
     * @param tenantId tenant that owns the document
     */
    public void recordSuccess(String tenantId) {
        publish("ProcessingSuccess", 1.0, StandardUnit.COUNT,
                dimension("TenantId", tenantId));
    }

    /**
     * Records a failed document processing.
     *
     * @param tenantId tenant that owns the document
     */
    public void recordFailure(String tenantId) {
        publish("ProcessingFailure", 1.0, StandardUnit.COUNT,
                dimension("TenantId", tenantId));
    }

    /**
     * Records the duration of a specific pipeline phase.
     *
     * @param tenantId   tenant that owns the document
     * @param phase      pipeline phase name (e.g. "TEXTRACT", "NLP", "ML_CLASSIFY")
     * @param durationMs elapsed time in milliseconds
     */
    public void recordPhaseDuration(String tenantId, String phase, long durationMs) {
        publish("PhaseDurationMs", (double) durationMs, StandardUnit.MILLISECONDS,
                dimension("TenantId", tenantId),
                dimension("Phase", phase));
    }

    /**
     * Records the estimated AWS cost for processing one document.
     *
     * @param tenantId            tenant that owns the document
     * @param estimatedCostUsd    estimated cost in USD (e.g. 0.0042)
     */
    public void recordEstimatedCostPerDocument(String tenantId, double estimatedCostUsd) {
        publish("EstimatedCostPerDocument", estimatedCostUsd, StandardUnit.NONE,
                dimension("TenantId", tenantId));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void publish(String metricName, double value, StandardUnit unit, Dimension... dimensions) {
        try {
            MetricDatum datum = MetricDatum.builder()
                    .metricName(metricName)
                    .value(value)
                    .unit(unit)
                    .timestamp(Instant.now())
                    .dimensions(dimensions)
                    .build();

            cloudWatch.putMetricData(PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(datum)
                    .build());
        } catch (Exception e) {
            // Metric publishing must never break the main processing flow
            log.warning("Failed to publish CloudWatch metric " + metricName + ": " + e.getMessage());
        }
    }

    private static Dimension dimension(String name, String value) {
        return Dimension.builder().name(name).value(value != null ? value : "unknown").build();
    }
}
