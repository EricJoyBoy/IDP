package com.idp.ingestion.service;

import com.idp.common.model.DocumentDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis caching service for DocumentDTO results.
 *
 * Cache key format: {tenant_id}:{document_id}:results
 * TTL is configurable via application.yml (aws.cache.ttl-seconds, default 300s).
 * Adds X-Cache: HIT/MISS header support via the CacheResult wrapper.
 *
 * Requirements: 8.4, 8.5
 */
@Service
public class DocumentCacheService {

    private static final Logger log = LoggerFactory.getLogger(DocumentCacheService.class);

    public static final String X_CACHE_HEADER = "X-Cache";
    public static final String CACHE_HIT = "HIT";
    public static final String CACHE_MISS = "MISS";

    private final RedisTemplate<String, DocumentDTO> redisTemplate;
    private final long ttlSeconds;

    public DocumentCacheService(RedisTemplate<String, DocumentDTO> redisTemplate,
                                @Value("${aws.cache.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Looks up the cache for the given tenant/document.
     *
     * @return CacheResult with the cached DocumentDTO (hit=true) or null (hit=false)
     */
    public CacheResult get(String tenantId, String documentId) {
        String key = buildKey(tenantId, documentId);
        try {
            DocumentDTO cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("Cache HIT key={}", key);
                return new CacheResult(cached, true);
            }
        } catch (Exception e) {
            log.warn("Redis GET failed for key={}, treating as MISS: {}", key, e.getMessage());
        }
        log.debug("Cache MISS key={}", key);
        return new CacheResult(null, false);
    }

    /**
     * Stores the DocumentDTO in cache with the configured TTL.
     */
    public void put(String tenantId, String documentId, DocumentDTO document) {
        String key = buildKey(tenantId, documentId);
        try {
            redisTemplate.opsForValue().set(key, document, Duration.ofSeconds(ttlSeconds));
            log.debug("Cached document key={} ttl={}s", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("Redis SET failed for key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Evicts the cached entry for the given tenant/document.
     */
    public void evict(String tenantId, String documentId) {
        String key = buildKey(tenantId, documentId);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis DELETE failed for key={}: {}", key, e.getMessage());
        }
    }

    /** Cache key: {tenant_id}:{document_id}:results */
    public static String buildKey(String tenantId, String documentId) {
        return tenantId + ":" + documentId + ":results";
    }

    // -------------------------------------------------------------------------
    // Inner result type
    // -------------------------------------------------------------------------

    public record CacheResult(DocumentDTO document, boolean hit) {}
}
