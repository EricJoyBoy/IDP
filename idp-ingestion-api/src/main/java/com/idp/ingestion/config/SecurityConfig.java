package com.idp.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.ingestion.dto.ErrorResponse;
import com.idp.ingestion.security.JwtTenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security configuration for the Ingestion API.
 *
 * - Validates Cognito JWT on every request via OAuth2 Resource Server
 * - Returns HTTP 401 for missing / expired / invalid tokens  (Req 10.3)
 * - Returns HTTP 403 for authenticated requests that lack the required role (Req 10.5)
 * - Populates TenantContext after successful JWT validation via JwtTenantFilter
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTenantFilter jwtTenantFilter;
    private final ObjectMapper    objectMapper;

    public SecurityConfig(JwtTenantFilter jwtTenantFilter, ObjectMapper objectMapper) {
        this.jwtTenantFilter = jwtTenantFilter;
        this.objectMapper    = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )

            // OAuth2 Resource Server – validates Cognito JWT (issuer-uri from application.yml)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
                // HTTP 401 for missing / expired / invalid token  (Req 10.3)
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    ErrorResponse body = ErrorResponse.builder()
                            .error("UNAUTHORIZED")
                            .message("Authentication required: " + ex.getMessage())
                            .build();
                    response.getWriter().write(objectMapper.writeValueAsString(body));
                })
            )

            // HTTP 403 for authenticated users without the required permission  (Req 10.5)
            .exceptionHandling(ex -> ex
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    ErrorResponse body = ErrorResponse.builder()
                            .error("FORBIDDEN")
                            .message("Access denied: insufficient permissions")
                            .build();
                    response.getWriter().write(objectMapper.writeValueAsString(body));
                })
            )

            // Populate TenantContext after JWT is validated
            .addFilterAfter(jwtTenantFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
