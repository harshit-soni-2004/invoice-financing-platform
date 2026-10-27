package com.ifp.audit.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.UUID;

/**
 * Stateless resource server with the platform's token contract. The audit API is ADMIN-only, enforced both by the
 * URL rule here and by @PreAuthorize on the controller.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter rolesConverter) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/api/audit-events/**", "/api/audit-events").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(requireUserIdSubject(rolesConverter))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    static Converter<Jwt, AbstractAuthenticationToken> requireUserIdSubject(JwtAuthenticationConverter delegate) {
        return jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new InvalidBearerTokenException("Token subject is not a valid user ID");
            }
            return delegate.convert(jwt);
        };
    }
}
