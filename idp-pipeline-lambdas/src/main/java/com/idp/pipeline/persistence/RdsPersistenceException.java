package com.idp.pipeline.persistence;

/**
 * Thrown when a transactional write to RDS fails (after rollback).
 */
public class RdsPersistenceException extends RuntimeException {

    public RdsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
