package com.idp.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttling configuration for the Ingestion API.
 *
 * Tracks the number of requests currently in-flight using an AtomicInteger counter.
 * When the active request count exceeds 80% of the configured capacity limit,
 * {@link #isThrottled()} returns {@code true} and the controller should respond
 * with HTTP 429 and a {@code Retry-After: 30} header.
 *
 * Requirements: 13.4
 */
@Configuration
public class ThrottlingConfig {

    /** Maximum number of concurrent requests the system can handle at full capacity. */
    private final int capacityLimit;

    /** 80% of capacityLimit — threshold above which throttling kicks in. */
    private final int throttleThreshold;

    /** Current number of active (in-flight) requests. */
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public ThrottlingConfig(
            @Value("${idp.throttling.capacity-limit:100}") int capacityLimit) {
        this.capacityLimit = capacityLimit;
        this.throttleThreshold = (int) Math.ceil(capacityLimit * 0.80);
    }

    /**
     * Returns {@code true} when the system is at >80% capacity and new requests
     * should be rejected with HTTP 429.
     */
    public boolean isThrottled() {
        return activeRequests.get() >= throttleThreshold;
    }

    /**
     * Increments the active request counter.
     * Call this at the start of request processing.
     */
    public void acquire() {
        activeRequests.incrementAndGet();
    }

    /**
     * Decrements the active request counter.
     * Call this when request processing completes (success or error).
     */
    public void release() {
        activeRequests.decrementAndGet();
    }

    /** Exposed for testing and monitoring. */
    public int getActiveRequests() {
        return activeRequests.get();
    }

    /** Exposed for testing. */
    public int getCapacityLimit() {
        return capacityLimit;
    }

    /** Exposed for testing. */
    public int getThrottleThreshold() {
        return throttleThreshold;
    }
}
