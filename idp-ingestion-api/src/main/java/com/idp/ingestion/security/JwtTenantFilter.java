package com.idp.ingestion.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Runs after Spring Security's BearerTokenAuthenticationFilter (JWT already validated).
 * Extracts Cognito-specific claims from the validated JWT and populates TenantContext
 * so that all downstream application layers can access tenant/user information.
 *
 * Claim mapping:
 *   tenant_id       → TenantContext.tenantId
 *   sub             → TenantContext.userId
 *   cognito:groups  → TenantContext.roles
 *
 * Requirements: 10.2, 10.4
 */
@Component
public class JwtTenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                populateTenantContext(jwt);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void populateTenantContext(Jwt jwt) {
        // tenant_id – custom Cognito claim
        String tenantId = jwt.getClaimAsString("tenant_id");
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }

        // sub – standard OIDC subject, used as user_id
        String userId = jwt.getSubject();
        if (userId != null) {
            TenantContext.setUserId(userId);
        }

        // cognito:groups – list of Cognito group names used as roles
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        TenantContext.setRoles(groups != null ? groups : Collections.emptyList());
    }
}
