package com.ifp.gateway.security;

import com.ifp.gateway.config.GatewaySettings;
import com.ifp.gateway.web.ProblemResponses;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Edge checks: every /api request except registration needs a valid access token, validated with exactly the rules
 * invoice-service applies (JWK set from auth-service, RS256 only, issuer, audience, expiry, UUID subject).
 * The original Authorization header is forwarded unchanged and no identity headers are added, so every service
 * re-validates the token itself; the gateway is an additional boundary, not the only one.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySettings.class)
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        // Coarse edge rules; the owning service enforces the same role again on each endpoint.
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/audit-events", "/api/audit-events/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(rolesConverter())))
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .build();
    }

    /**
     * Fetches keys from {@code jwk-set-uri}. An unknown {@code kid} triggers a refetch of the JWK set, so a new
     * signing key published by auth-service is picked up without restarting the gateway.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(tokenValidator(jwt.getIssuerUri(), jwt.getAudiences()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, List<String> audiences) {
        if (issuer == null || issuer.isBlank() || audiences == null || audiences.isEmpty()) {
            throw new IllegalStateException("Gateway token validation needs issuer-uri and audiences");
        }
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(audiences::contains)),
                // The default timestamp validator skips tokens without exp; a token that never expires is refused.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, exp -> exp != null),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, SecurityConfig::isUuid));
    }

    /** Same mapping as every service: {@code roles: ["ADMIN"]} becomes {@code ROLE_ADMIN}. */
    static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** Fixed 401 body and challenge; the decoder's error text (which can describe the token) is never echoed. */
    static ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> {
            boolean tokenPresented = exchange.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION);
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                    tokenPresented ? "Bearer error=\"invalid_token\"" : "Bearer");
            return ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    tokenPresented ? "The access token is missing, malformed, expired or not trusted" : "Authentication is required");
        };
    }

    static ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewaySettings settings) {
        GatewaySettings.Cors cors = settings.cors();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.allowedOrigins());
        config.setAllowedMethods(cors.allowedMethods());
        config.setAllowedHeaders(cors.allowedHeaders());
        config.setExposedHeaders(cors.exposedHeaders());
        config.setAllowCredentials(false);
        config.setMaxAge(cors.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
