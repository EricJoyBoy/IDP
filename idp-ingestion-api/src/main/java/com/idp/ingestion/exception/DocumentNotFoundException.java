package com.idp.ingestion.exception;

/**
 * Thrown when a document is not found in DynamoDB.
 * Results in HTTP 404 (or HTTP 403 for cross-tenant access).
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String documentId) {
        super("Document not found: " + documentId);
    }
}
