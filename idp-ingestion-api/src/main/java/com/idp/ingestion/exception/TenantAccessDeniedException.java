package com.idp.ingestion.exception;

/**
 * Thrown when a tenant attempts to access a document belonging to a different tenant.
 * Results in HTTP 403 without revealing the document exists.
 * Requirements: 8.3
 */
public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException() {
        super("Access denied");
    }
}
