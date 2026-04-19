package com.idp.ingestion.security;

import java.util.Collections;
import java.util.List;

/**
 * ThreadLocal holder for the current request's security context.
 * Populated by JwtTenantFilter from Cognito JWT claims and cleared after the request.
 *
 * Claims mapping:
 *   tenant_id       → tenantId
 *   sub             → userId
 *   cognito:groups  → roles
 *
 * Requirements: 10.2, 10.4
 */
public final class TenantContext {

    private static final ThreadLocal<String>       TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String>       USER_ID   = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES     = new ThreadLocal<>();

    private TenantContext() {}

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static void setRoles(List<String> roles) {
        ROLES.set(roles != null ? List.copyOf(roles) : Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    /** Returns an unmodifiable list; never null. */
    public static List<String> getRoles() {
        List<String> roles = ROLES.get();
        return roles != null ? roles : Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Must be called in a finally block at the end of every request. */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLES.remove();
    }
}
