package com.idp.ingestion.service;

import com.idp.ingestion.exception.UnsupportedDocumentFormatException;
import com.idp.ingestion.exception.DocumentTooLargeException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/**
 * Validates uploaded documents for format and size constraints.
 * Requirements: 1.3, 1.4
 */
@Service
public class DocumentValidationService {

    /** Maximum allowed file size: 50 MB */
    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    static final List<String> SUPPORTED_FORMATS = List.of("PDF", "PNG", "JPEG", "TIFF");

    private static final List<String> SUPPORTED_CONTENT_TYPES = List.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/tiff"
    );

    /**
     * Validates the file size and format.
     *
     * @throws DocumentTooLargeException          if file exceeds 50 MB (→ HTTP 413)
     * @throws UnsupportedDocumentFormatException if format is not PDF/PNG/JPEG/TIFF (→ HTTP 415)
     */
    public void validate(MultipartFile file) {
        validateSize(file);
        validateFormat(file);
    }

    /**
     * Resolves the canonical format name (PDF, PNG, JPEG, TIFF) from the file.
     */
    public String resolveFormat(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "application/pdf" -> "PDF";
                case "image/png" -> "PNG";
                case "image/jpeg" -> "JPEG";
                case "image/tiff" -> "TIFF";
                default -> resolveFormatFromFilename(file.getOriginalFilename());
            };
        }
        return resolveFormatFromFilename(file.getOriginalFilename());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validateSize(MultipartFile file) {
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new DocumentTooLargeException(
                    "File size " + file.getSize() + " bytes exceeds the maximum allowed size of 50 MB");
        }
    }

    private void validateFormat(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return;
        }
        // Fall back to filename extension check
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String ext = getExtension(filename).toUpperCase(Locale.ROOT);
            if (SUPPORTED_FORMATS.contains(ext) || "JPG".equals(ext)) {
                return;
            }
        }
        throw new UnsupportedDocumentFormatException(
                "Unsupported document format. Accepted formats: " + SUPPORTED_FORMATS);
    }

    private String resolveFormatFromFilename(String filename) {
        if (filename == null) return "UNKNOWN";
        String ext = getExtension(filename).toUpperCase(Locale.ROOT);
        return "JPG".equals(ext) ? "JPEG" : ext;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
