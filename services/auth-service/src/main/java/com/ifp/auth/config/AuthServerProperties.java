package com.ifp.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * @param issuer         fixed issuer ({@code iss}); never derived from request headers
 * @param audience       audience every resource server requires in {@code aud}
 * @param accessTokenTtl lifetime of access tokens
 */
@Validated
@ConfigurationProperties("auth")
public record AuthServerProperties(
        @NotNull URI issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @Valid @NotNull Spa spa,
        @Valid @NotNull Jwt jwt) {

    /** The Angular app: a public client, so it has no secret and must use PKCE. */
    public record Spa(
            @NotBlank String clientId,
            @NotEmpty List<String> redirectUris,
            @DefaultValue List<String> postLogoutRedirectUris,
            @NotEmpty List<String> allowedOrigins) {
    }

    /**
     * @param privateKeyLocation PKCS#8 PEM private key (Spring resource location, e.g. {@code file:/run/keys/jwt.pem})
     * @param keyId              optional {@code kid}; defaults to the key's RFC 7638 thumbprint
     * @param allowEphemeralKey  development/test only: generate an in-memory key when no key is configured
     * @param previousPublicKeys retired keys still published for verification during a rotation
     */
    public record Jwt(
            String privateKeyLocation,
            String keyId,
            boolean allowEphemeralKey,
            @DefaultValue List<@Valid PublicKeyRef> previousPublicKeys) {
    }

    public record PublicKeyRef(@NotBlank String keyId, @NotBlank String location) {
    }
}
