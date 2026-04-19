package com.idp.ingestion.service;

import com.idp.ingestion.exception.DocumentTooLargeException;
import com.idp.ingestion.exception.UnsupportedDocumentFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DocumentValidationService.
 * Requirements: 1.3, 1.4
 */
class DocumentValidationServiceTest {

    private final DocumentValidationService service = new DocumentValidationService();

    // -------------------------------------------------------------------------
    // Format validation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "contentType={0} → accepted")
    @CsvSource({
            "application/pdf,   invoice.pdf",
            "image/png,         scan.png",
            "image/jpeg,        photo.jpg",
            "image/tiff,        document.tiff"
    })
    void supportedContentTypes_doNotThrow(String contentType, String filename) {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename.trim(), contentType.trim(), new byte[100]);
        assertThatNoException().isThrownBy(() -> service.validate(file));
    }

    @ParameterizedTest(name = "contentType={0} → HTTP 415")
    @CsvSource({
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, doc.docx",
            "text/plain,                                                               file.txt",
            "image/gif,                                                                anim.gif",
            "application/zip,                                                          archive.zip"
    })
    void unsupportedContentTypes_throwUnsupportedFormat(String contentType, String filename) {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename.trim(), contentType.trim(), new byte[100]);
        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(UnsupportedDocumentFormatException.class);
    }

    @Test
    void nullContentType_withPdfExtension_isAccepted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.pdf", null, new byte[100]);
        assertThatNoException().isThrownBy(() -> service.validate(file));
    }

    @Test
    void nullContentType_withUnknownExtension_throwsUnsupportedFormat() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "document.xyz", null, new byte[100]);
        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(UnsupportedDocumentFormatException.class);
    }

    // -------------------------------------------------------------------------
    // Size validation
    // -------------------------------------------------------------------------

    @Test
    void fileExactly50MB_isAccepted() {
        byte[] data = new byte[(int) DocumentValidationService.MAX_SIZE_BYTES];
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.pdf", "application/pdf", data);
        assertThatNoException().isThrownBy(() -> service.validate(file));
    }

    @Test
    void fileExceeding50MB_throwsDocumentTooLarge() {
        // MockMultipartFile.getSize() returns the byte array length
        byte[] data = new byte[(int) DocumentValidationService.MAX_SIZE_BYTES + 1];
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.pdf", "application/pdf", data);
        assertThatThrownBy(() -> service.validate(file))
                .isInstanceOf(DocumentTooLargeException.class);
    }

    @Test
    void emptyFile_withValidFormat_isAccepted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatNoException().isThrownBy(() -> service.validate(file));
    }

    // -------------------------------------------------------------------------
    // Format resolution
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "contentType={0} → format={2}")
    @CsvSource({
            "application/pdf, invoice.pdf, PDF",
            "image/png,       scan.png,    PNG",
            "image/jpeg,      photo.jpg,   JPEG",
            "image/tiff,      doc.tiff,    TIFF"
    })
    void resolveFormat_returnsCanonicalName(String contentType, String filename, String expected) {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename.trim(), contentType.trim(), new byte[10]);
        assertThat(service.resolveFormat(file)).isEqualTo(expected);
    }

    @Test
    void resolveFormat_jpgExtension_returnsJpeg() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", null, new byte[10]);
        assertThat(service.resolveFormat(file)).isEqualTo("JPEG");
    }
}
