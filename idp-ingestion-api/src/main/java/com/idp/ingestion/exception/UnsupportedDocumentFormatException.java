package com.idp.ingestion.exception;

/**
 * Thrown when an uploaded document has an unsupported format.
 * Maps to HTTP 415 Unsupported Media Type.
 * Requirements: 1.4
 */
public class UnsupportedDocumentFormatException extends RuntimeException {

    public UnsupportedDocumentFormatException(String message) {
        super(message);
    }
}
