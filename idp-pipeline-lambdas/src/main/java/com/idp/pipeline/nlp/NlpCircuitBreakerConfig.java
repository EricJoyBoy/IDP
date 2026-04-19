package com.idp.pipeline.nlp;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

/**
 * Configures the Resilience4j CircuitBreaker for Amazon Comprehend calls.
 *
 * <p>Configuration:
 * <ul>
 *   <li>slidingWindowSize: 5 calls</li>
 *   <li>failureRateThreshold: 100% (opens after 5 consecutive failures)</li>
 *   <li>waitDurationInOpenState: 60 seconds</li>
 *   <li>permittedNumberOfCallsInHalfOpenState: 1 probe request</li>
 * </ul>
 */
public class NlpCircuitBreakerConfig {

    public static final String CIRCUIT_BREAKER_NAME = "comprehend";

    private NlpCircuitBreakerConfig() {}

    public static CircuitBreaker create() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .failureRateThreshold(100.0f)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        return registry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }
}
