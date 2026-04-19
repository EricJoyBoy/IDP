package com.idp.ingestion.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JwtTenantFilter.
 * Verifies extraction of tenant_id, user_id (sub) and roles (cognito:groups) from JWT claims.
 * Requirements: 10.2, 10.4
 */
class JwtTenantFilterTest {

    private JwtTenantFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtTenantFilter();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Jwt buildJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(claims))
                .build();
    }

    private void authenticateWith(Jwt jwt) {
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // -------------------------------------------------------------------------
    // Happy path – all claims present
    // -------------------------------------------------------------------------

    @Test
    void extractsAllClaimsIntoTenantContext() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",            "user-abc",
                "tenant_id",      "tenant-001",
                "cognito:groups", List.of("ADMIN", "USER")
        ));
        authenticateWith(jwt);

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        // After the filter chain completes, TenantContext is cleared (finally block).
        // We capture values inside the chain by checking them during filter execution.
        // Re-run with a capturing chain:
        final String[] capturedTenant = new String[1];
        final String[] capturedUser   = new String[1];
        final List<?>[] capturedRoles = new List<?>[1];

        authenticateWith(jwt);
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> {
                    capturedTenant[0] = TenantContext.getTenantId();
                    capturedUser[0]   = TenantContext.getUserId();
                    capturedRoles[0]  = TenantContext.getRoles();
                }
        );

        assertThat(capturedTenant[0]).isEqualTo("tenant-001");
        assertThat(capturedUser[0]).isEqualTo("user-abc");
        assertThat(capturedRoles[0]).containsExactlyInAnyOrder("ADMIN", "USER");
    }

    @Test
    void extractsTenantId_fromJwtClaim() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",       "user-xyz",
                "tenant_id", "tenant-finance"
        ));
        authenticateWith(jwt);

        final String[] captured = new String[1];
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured[0] = TenantContext.getTenantId()
        );

        assertThat(captured[0]).isEqualTo("tenant-finance");
    }

    @Test
    void extractsUserId_fromSubClaim() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",       "user-sub-123",
                "tenant_id", "tenant-001"
        ));
        authenticateWith(jwt);

        final String[] captured = new String[1];
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured[0] = TenantContext.getUserId()
        );

        assertThat(captured[0]).isEqualTo("user-sub-123");
    }

    @Test
    void extractsRoles_fromCognitoGroupsClaim() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",            "user-001",
                "tenant_id",      "tenant-001",
                "cognito:groups", List.of("REVIEWER")
        ));
        authenticateWith(jwt);

        final List<?>[] captured = new List<?>[1];
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured[0] = TenantContext.getRoles()
        );

        assertThat(captured[0]).containsExactly("REVIEWER");
    }

    // -------------------------------------------------------------------------
    // Missing / absent claims
    // -------------------------------------------------------------------------

    @Test
    void missingTenantId_tenantContextRemainsNull() throws Exception {
        Jwt jwt = buildJwt(Map.of("sub", "user-001"));
        authenticateWith(jwt);

        final String[] captured = new String[1];
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured[0] = TenantContext.getTenantId()
        );

        assertThat(captured[0]).isNull();
    }

    @Test
    void missingCognitoGroups_rolesIsEmptyList() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",       "user-001",
                "tenant_id", "tenant-001"
        ));
        authenticateWith(jwt);

        final List<?>[] captured = new List<?>[1];
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> captured[0] = TenantContext.getRoles()
        );

        assertThat(captured[0]).isEmpty();
    }

    // -------------------------------------------------------------------------
    // No authentication in SecurityContext (unauthenticated request)
    // -------------------------------------------------------------------------

    @Test
    void noAuthentication_tenantContextRemainsEmpty() throws Exception {
        // SecurityContext has no authentication (token missing / rejected upstream)
        SecurityContextHolder.clearContext();

        final String[] capturedTenant = new String[1];
        final String[] capturedUser   = new String[1];

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> {
                    capturedTenant[0] = TenantContext.getTenantId();
                    capturedUser[0]   = TenantContext.getUserId();
                }
        );

        assertThat(capturedTenant[0]).isNull();
        assertThat(capturedUser[0]).isNull();
    }

    // -------------------------------------------------------------------------
    // TenantContext is cleared after request (finally block)
    // -------------------------------------------------------------------------

    @Test
    void tenantContextClearedAfterFilterChain() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",       "user-001",
                "tenant_id", "tenant-001"
        ));
        authenticateWith(jwt);

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        // After the filter completes, context must be cleared
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(TenantContext.getRoles()).isEmpty();
    }

    @Test
    void tenantContextClearedEvenWhenFilterChainThrows() throws Exception {
        Jwt jwt = buildJwt(Map.of(
                "sub",       "user-001",
                "tenant_id", "tenant-001"
        ));
        authenticateWith(jwt);

        try {
            filter.doFilter(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    (req, res) -> { throw new RuntimeException("downstream failure"); }
            );
        } catch (RuntimeException ignored) {
            // expected
        }

        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }
}
