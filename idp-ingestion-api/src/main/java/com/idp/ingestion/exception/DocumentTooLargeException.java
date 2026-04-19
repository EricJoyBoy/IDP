package com.idp.ingestion.exception;

/**
 * Thrown when an uploaded document exceeds the 50 MB size limit.
 * Maps to HTTP 413 Payload Too Large.
 * Requirements: 1.3
 */
public class DocumentTooLargeException extends RuntimeException {

    public DocumentTooLargeException(String message) {
        super(message);
    }
}
