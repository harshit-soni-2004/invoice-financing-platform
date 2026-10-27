package com.ifp.auth.security;

import com.ifp.auth.config.AuthServerProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * The active RS256 signing key plus any retired public keys that must still verify tokens during a rotation.
 * Private key material never leaves this class: the published JWK set contains public parameters only.
 */
public final class SigningKeys {

    static final int MIN_KEY_BITS = 2048;
    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final RSAKey active;
    private final List<RSAKey> retired;

    private SigningKeys(RSAKey active, List<RSAKey> retired) {
        this.active = active;
        this.retired = List.copyOf(retired);
    }

    public static SigningKeys load(AuthServerProperties.Jwt config, ResourceLoader resourceLoader) {
        RSAKey active;
        if (config.privateKeyLocation() != null && !config.privateKeyLocation().isBlank()) {
            RSAPrivateKey privateKey = read(resourceLoader.getResource(config.privateKeyLocation()), RsaKeyConverters.pkcs8());
            active = toJwk(publicKeyOf(privateKey), privateKey, config.keyId());
        } else if (config.allowEphemeralKey()) {
            active = ephemeral();
            log.warn("Using an ephemeral JWT signing key (kid={}). Tokens become invalid on restart; development and tests only.",
                    active.getKeyID());
        } else {
            throw new IllegalStateException("No JWT signing key configured. Set auth.jwt.private-key-location "
                    + "(see scripts/generate-dev-jwt-key.sh), or auth.jwt.allow-ephemeral-key=true for local development.");
        }

        List<RSAKey> retired = new ArrayList<>();
        for (AuthServerProperties.PublicKeyRef ref : config.previousPublicKeys()) {
            RSAPublicKey publicKey = read(resourceLoader.getResource(ref.location()), RsaKeyConverters.x509());
            RSAKey key = toJwk(publicKey, null, ref.keyId());
            if (key.getKeyID().equals(active.getKeyID())) {
                throw new IllegalStateException("Retired key ID " + key.getKeyID() + " clashes with the active key ID");
            }
            retired.add(key);
        }
        log.info("JWT signing key loaded: kid={}, retired keys published={}", active.getKeyID(), retired.size());
        return new SigningKeys(active, retired);
    }

    /** Signs with the active key only, so token headers always name exactly one {@code kid}. */
    public JWKSource<SecurityContext> signingSource() {
        return new ImmutableJWKSet<>(new JWKSet(active));
    }

    /** Active and retired keys; also backs the public {@code /oauth2/jwks} endpoint (which strips private parts). */
    public JWKSource<SecurityContext> verificationSource() {
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        keys.add(active);
        keys.addAll(retired);
        return new ImmutableJWKSet<>(new JWKSet(keys));
    }

    public String activeKeyId() {
        return active.getKeyID();
    }

    private static RSAKey toJwk(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId) {
        if (publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalStateException("JWT RSA keys must be at least " + MIN_KEY_BITS + " bits");
        }
        RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }
        try {
            if (keyId == null || keyId.isBlank()) {
                builder.keyIDFromThumbprint();
            } else {
                builder.keyID(keyId);
            }
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not compute JWK thumbprint", e);
        }
        return builder.build();
    }

    private static RSAPublicKey publicKeyOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException("The JWT private key must include its CRT parameters (standard PKCS#8)");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive the JWT public key", e);
        }
    }

    private static RSAKey ephemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            KeyPair pair = generator.generateKeyPair();
            return toJwk((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(), null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an ephemeral JWT key", e);
        }
    }

    private static <T> T read(Resource resource, org.springframework.core.convert.converter.Converter<InputStream, T> converter) {
        if (!resource.exists()) {
            throw new IllegalStateException("JWT key file not found: " + resource.getDescription());
        }
        try (InputStream in = resource.getInputStream()) {
            return converter.convert(in);
        } catch (IOException | IllegalArgumentException e) {
            // The message names the file but never includes its contents.
            throw new IllegalStateException("Could not read JWT key from " + resource.getDescription(), e);
        }
    }
}
