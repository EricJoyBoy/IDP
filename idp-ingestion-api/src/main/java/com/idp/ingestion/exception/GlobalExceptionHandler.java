package com.idp.ingestion.exception;

import com.idp.ingestion.dto.ErrorResponse;
import com.idp.ingestion.service.DocumentValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps domain exceptions to HTTP error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** HTTP 413 – file exceeds 50 MB */
    @ExceptionHandler({DocumentTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ErrorResponse> handleTooLarge(Exception ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.builder()
                        .error("PAYLOAD_TOO_LARGE")
                        .message("File size exceeds the maximum allowed size of 50 MB")
                        .build());
    }

    /** HTTP 415 – unsupported format */
    @ExceptionHandler(UnsupportedDocumentFormatException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedFormat(UnsupportedDocumentFormatException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.builder()
                        .error("UNSUPPORTED_MEDIA_TYPE")
                        .message(ex.getMessage())
                        .acceptedFormats(DocumentValidationService.SUPPORTED_FORMATS)
                        .build());
    }

    /**
     * HTTP 403 – cross-tenant access attempt.
     * Does NOT reveal whether the document exists (Req 8.3).
     */
    @ExceptionHandler(TenantAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleTenantAccessDenied(TenantAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.builder()
                        .error("FORBIDDEN")
                        .message("Access denied")
                        .build());
    }

    /** HTTP 404 – document not found */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DocumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .error("NOT_FOUND")
                        .message(ex.getMessage())
                        .build());
    }

    /** HTTP 500 – unexpected errors */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .error("INTERNAL_SERVER_ERROR")
                        .message("An unexpected error occurred")
                        .build());
    }
}
