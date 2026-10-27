package com.ifp.auth.security;

import com.ifp.auth.config.AuthServerProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeysTest {

    private final DefaultResourceLoader loader = new DefaultResourceLoader();

    @TempDir
    Path dir;

    @Test
    void loadsPkcs8PemAndDerivesThumbprintKeyId() throws Exception {
        KeyPair pair = rsa(2048);
        Path pem = write("active.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());

        SigningKeys keys = SigningKeys.load(config(pem, null, false, List.of()), loader);

        RSAKey expected = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic()).build();
        assertThat(keys.activeKeyId()).isEqualTo(expected.computeThumbprint().toString());
        List<JWK> signing = select(keys.signingSource());
        assertThat(signing).hasSize(1);
        assertThat(signing.get(0).isPrivate()).isTrue();
    }

    @Test
    void explicitKeyIdIsUsed() throws Exception {
        Path pem = write("active.pem", "PRIVATE KEY", rsa(2048).getPrivate().getEncoded());

        assertThat(SigningKeys.load(config(pem, "2026-10", false, List.of()), loader).activeKeyId()).isEqualTo("2026-10");
    }

    @Test
    void refusesToStartWithoutAKeyUnlessEphemeralIsExplicitlyAllowed() {
        assertThatThrownBy(() -> SigningKeys.load(config(null, null, false, List.of()), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.jwt.private-key-location");

        assertThat(SigningKeys.load(config(null, null, true, List.of()), loader).activeKeyId()).isNotBlank();
    }

    @Test
    void rejectsKeysShorterThan2048Bits() throws Exception {
        Path pem = write("weak.pem", "PRIVATE KEY", rsa(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> SigningKeys.load(config(pem, null, false, List.of()), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void missingOrCorruptKeyFileFailsWithoutLeakingContents() throws Exception {
        assertThatThrownBy(() -> SigningKeys.load(config(dir.resolve("absent.pem"), null, false, List.of()), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");

        Path corrupt = dir.resolve("corrupt.pem");
        Files.writeString(corrupt, "-----BEGIN PRIVATE KEY-----\nTOP-SECRET-GARBAGE\n-----END PRIVATE KEY-----\n");
        assertThatThrownBy(() -> SigningKeys.load(config(corrupt, null, false, List.of()), loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("TOP-SECRET-GARBAGE");
    }

    @Test
    void retiredPublicKeysAreVerifiableButNeverUsedForSigning() throws Exception {
        Path active = write("active.pem", "PRIVATE KEY", rsa(2048).getPrivate().getEncoded());
        Path retired = write("retired.pub.pem", "PUBLIC KEY", rsa(2048).getPublic().getEncoded());

        SigningKeys keys = SigningKeys.load(config(active, "new", false,
                List.of(new AuthServerProperties.PublicKeyRef("old", retired.toUri().toString()))), loader);

        assertThat(select(keys.verificationSource())).extracting(JWK::getKeyID).containsExactly("new", "old");
        assertThat(select(keys.signingSource())).extracting(JWK::getKeyID).containsExactly("new");
    }

    @Test
    void retiredKeyIdMustNotClashWithActiveKeyId() throws Exception {
        Path active = write("active.pem", "PRIVATE KEY", rsa(2048).getPrivate().getEncoded());
        Path retired = write("retired.pub.pem", "PUBLIC KEY", rsa(2048).getPublic().getEncoded());

        assertThatThrownBy(() -> SigningKeys.load(config(active, "same", false,
                List.of(new AuthServerProperties.PublicKeyRef("same", retired.toUri().toString()))), loader))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<JWK> select(com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> source)
            throws Exception {
        return source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    }

    private static AuthServerProperties.Jwt config(Path pem, String keyId, boolean ephemeral,
                                                   List<AuthServerProperties.PublicKeyRef> previous) {
        return new AuthServerProperties.Jwt(pem == null ? null : pem.toUri().toString(), keyId, ephemeral, previous);
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private Path write(String name, String type, byte[] der) throws Exception {
        String pem = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
        Path file = dir.resolve(name);
        Files.writeString(file, pem);
        return file;
    }
}
