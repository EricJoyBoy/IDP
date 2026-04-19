package com.idp.common.model;

/**
 * Represents the lifecycle status of a document in the IDP pipeline.
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    NEEDS_REVIEW,
    PERSISTENCE_ERROR
}
