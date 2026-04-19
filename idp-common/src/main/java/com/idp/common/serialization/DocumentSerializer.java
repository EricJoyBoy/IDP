package com.idp.common.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.common.model.DocumentDTO;
import com.idp.common.model.ExtractedContent;

/**
 * Utility class for serializing and deserializing IDP DTOs to/from JSON.
 */
public final class DocumentSerializer {

    private static final ObjectMapper MAPPER = DocumentObjectMapper.getInstance();

    private DocumentSerializer() {}

    /**
     * Serializes a DocumentDTO to a JSON string.
     *
     * @param document the document to serialize
     * @return JSON string representation
     * @throws JsonProcessingException if serialization fails
     */
    public static String serialize(DocumentDTO document) throws JsonProcessingException {
        return MAPPER.writeValueAsString(document);
    }

    /**
     * Deserializes a JSON string to a DocumentDTO.
     *
     * @param json the JSON string
     * @return the deserialized DocumentDTO
     * @throws JsonProcessingException if deserialization fails
     */
    public static DocumentDTO deserialize(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, DocumentDTO.class);
    }

    /**
     * Serializes an ExtractedContent to a JSON string.
     *
     * @param content the content to serialize
     * @return JSON string representation
     * @throws JsonProcessingException if serialization fails
     */
    public static String serializeContent(ExtractedContent content) throws JsonProcessingException {
        return MAPPER.writeValueAsString(content);
    }

    /**
     * Deserializes a JSON string to an ExtractedContent.
     *
     * @param json the JSON string
     * @return the deserialized ExtractedContent
     * @throws JsonProcessingException if deserialization fails
     */
    public static ExtractedContent deserializeContent(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, ExtractedContent.class);
    }
}
