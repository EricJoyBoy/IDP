package com.idp.common.observability;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Utility class that emits JSON-structured log entries to CloudWatch.
 *
 * <p>Each log entry includes the mandatory IDP observability fields:
 * {@code document_id}, {@code tenant_id}, {@code phase}, {@code duration_ms}, {@code status}.
 *
 * <p>Uses {@link java.util.logging.Logger} internally so it works in both Lambda
 * and ECS Fargate environments without additional dependencies.
 *
 * <p>Requirements: 11.1, 11.2
 */
public final class StructuredLogger {

    private final Logger delegate;

    private StructuredLogger(Class<?> clazz) {
        this.delegate = Logger.getLogger(clazz.getName());
    }

    private StructuredLogger(String name) {
        this.delegate = Logger.getLogger(name);
    }

    /** Factory method — mirrors {@link Logger#getLogger(Class)}. */
    public static StructuredLogger getLogger(Class<?> clazz) {
        return new StructuredLogger(clazz);
    }

    /** Factory method — mirrors {@link Logger#getLogger(String)}. */
    public static StructuredLogger getLogger(String name) {
        return new StructuredLogger(name);
    }

    // -------------------------------------------------------------------------
    // Core structured log methods
    // -------------------------------------------------------------------------

    /**
     * Logs a structured INFO entry with all IDP observability fields.
     *
     * @param documentId  document being processed (may be null for non-document events)
     * @param tenantId    tenant owning the document
     * @param phase       pipeline phase name (e.g. "TEXTRACT", "NLP", "ML_CLASSIFY")
     * @param durationMs  elapsed time in milliseconds for this phase
     * @param status      outcome status (e.g. "SUCCESS", "FAILURE", "PROCESSING")
     * @param message     human-readable description
     */
    public void info(String documentId, String tenantId, String phase,
                     long durationMs, String status, String message) {
        delegate.info(buildJson(documentId, tenantId, phase, durationMs, status, message, null));
    }

    /**
     * Logs a structured WARNING entry.
     */
    public void warn(String documentId, String tenantId, String phase,
                     long durationMs, String status, String message) {
        delegate.warning(buildJson(documentId, tenantId, phase, durationMs, status, message, null));
    }

    /**
     * Logs a structured ERROR entry, optionally including an exception message.
     */
    public void error(String documentId, String tenantId, String phase,
                      long durationMs, String status, String message, Throwable cause) {
        String errorDetail = cause != null ? cause.getMessage() : null;
        delegate.severe(buildJson(documentId, tenantId, phase, durationMs, status, message, errorDetail));
    }

    /**
     * Logs a structured ERROR entry without a cause.
     */
    public void error(String documentId, String tenantId, String phase,
                      long durationMs, String status, String message) {
        error(documentId, tenantId, phase, durationMs, status, message, null);
    }

    // -------------------------------------------------------------------------
    // JSON builder — minimal, no external dependencies
    // -------------------------------------------------------------------------

    /**
     * Builds a JSON string with the IDP standard observability fields.
     * Keeps it simple: manual string building avoids pulling Jackson into the
     * logging path and keeps Lambda cold-start overhead minimal.
     */
    static String buildJson(String documentId, String tenantId, String phase,
                             long durationMs, String status, String message,
                             String errorDetail) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "timestamp", Instant.now().toString(), true);
        appendField(sb, "document_id", documentId, false);
        appendField(sb, "tenant_id", tenantId, false);
        appendField(sb, "phase", phase, false);
        appendLongField(sb, "duration_ms", durationMs);
        appendField(sb, "status", status, false);
        appendField(sb, "message", message, false);
        if (errorDetail != null) {
            appendField(sb, "error", errorDetail, false);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendLongField(StringBuilder sb, String key, long value) {
        sb.append(',');
        sb.append('"').append(key).append("\":").append(value);
    }

    /** Minimal JSON string escaping for control characters and quotes. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
