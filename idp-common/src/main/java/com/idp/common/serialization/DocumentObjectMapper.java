package com.idp.common.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Provides a pre-configured Jackson ObjectMapper for IDP DTO serialization.
 * Handles Java 8 time types (Instant) and is configured for strict round-trip fidelity.
 */
public final class DocumentObjectMapper {

    private static final ObjectMapper INSTANCE = createMapper();

    private DocumentObjectMapper() {}

    public static ObjectMapper getInstance() {
        return INSTANCE;
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
