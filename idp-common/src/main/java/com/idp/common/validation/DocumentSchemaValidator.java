package com.idp.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.DocumentDTO;
import com.idp.common.serialization.DocumentObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.Set;

/**
 * Validates DocumentDTO instances against the JSON Schema definition.
 * Uses networknt json-schema-validator for validation.
 */
public final class DocumentSchemaValidator {

    private static final ObjectMapper MAPPER = DocumentObjectMapper.getInstance();
    private static final JsonSchema SCHEMA = loadSchema();

    private DocumentSchemaValidator() {}

    private static JsonSchema loadSchema() {
        try (InputStream is = DocumentSchemaValidator.class
                .getResourceAsStream("/schemas/document-dto-schema.json")) {
            if (is == null) {
                throw new IllegalStateException("document-dto-schema.json not found on classpath");
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(is);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load DocumentDTO JSON Schema", e);
        }
    }

    /**
     * Validates a DocumentDTO against the JSON Schema.
     *
     * @param document the document to validate
     * @return set of validation messages; empty if valid
     */
    public static Set<ValidationMessage> validate(DocumentDTO document) {
        try {
            JsonNode node = MAPPER.valueToTree(document);
            return SCHEMA.validate(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to validate DocumentDTO", e);
        }
    }

    /**
     * Returns true if the document passes JSON Schema validation.
     *
     * @param document the document to validate
     * @return true if valid
     */
    public static boolean isValid(DocumentDTO document) {
        return validate(document).isEmpty();
    }
}
