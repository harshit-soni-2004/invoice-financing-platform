package com.ifp.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.support.OAuthFlowClient;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.ifp.auth.support.OAuthFlowClient.authorizeParams;
import static com.ifp.auth.support.OAuthFlowClient.newCodeVerifier;
import static com.ifp.auth.support.OAuthFlowClient.queryParams;
import static com.ifp.auth.support.OAuthFlowClient.s256;
import static org.assertj.core.api.Assertions.assertThat;

/** Drives the real Authorization Code + PKCE flow over HTTP and checks the issued tokens against the platform contract. */
class AuthorizationCodeFlowIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sellerTokenSatisfiesTheResourceServerContract() throws Exception {
        UserAccount seller = createUser(Role.SELLER);

        JsonNode tokens = browser().obtainTokens(seller.getEmail(), PASSWORD, REDIRECT_URI);

        assertThat(tokens.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.path("expires_in").asLong()).isBetween(890L, 900L);
        assertThat(tokens.has("refresh_token")).as("public SPA client gets no refresh token").isFalse();

        String accessToken = tokens.path("access_token").asText();
        SignedJWT parsed = SignedJWT.parse(accessToken);
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(parsed.getHeader().getKeyID()).isNotBlank();

        // Decode exactly as invoice-service does: JWK set over HTTP, RS256 only, issuer + audience, roles -> ROLE_*.
        Jwt jwt = resourceServerDecoder().decode(accessToken);
        assertThat(jwt.getSubject()).isEqualTo(seller.getId().toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SELLER");
        assertThat(rolesConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_SELLER")
                .doesNotContain("ROLE_ADMIN", "ROLE_FINANCIER");
    }

    @Test
    void idTokenIsForTheSpaAndCarriesDisplayClaims() throws Exception {
        UserAccount admin = createUser(Role.ADMIN);

        JsonNode tokens = browser().obtainTokens(admin.getEmail(), PASSWORD, REDIRECT_URI);

        var idToken = SignedJWT.parse(tokens.path("id_token").asText()).getJWTClaimsSet();
        assertThat(idToken.getSubject()).isEqualTo(admin.getId().toString());
        assertThat(idToken.getAudience()).containsExactly(OAuthFlowClient.CLIENT_ID);
        assertThat(idToken.getStringListClaim("roles")).containsExactly("ADMIN");
        assertThat(idToken.getStringClaim("name")).isEqualTo("Test Admin");
        assertThat(idToken.getClaims()).doesNotContainKeys("email", "password");
    }

    @Test
    void userInfoEndpointAcceptsTheAccessToken() {
        UserAccount seller = createUser(Role.SELLER);
        OAuthFlowClient browser = browser();
        String accessToken = browser.obtainTokens(seller.getEmail(), PASSWORD, REDIRECT_URI).path("access_token").asText();

        // UserInfo reloads the persisted authorization, so this also proves stored token metadata deserializes.
        HttpResponse<String> userInfo = browser.send(java.net.http.HttpRequest.newBuilder(browser.base().resolve("/userinfo"))
                .header("Authorization", "Bearer " + accessToken).GET().build());

        assertThat(userInfo.statusCode()).isEqualTo(200);
        assertThat(OAuthFlowClient.json(userInfo.body()).path("sub").asText()).isEqualTo(seller.getId().toString());
    }

    @Test
    void authorizationCodeIsSingleUse() {
        UserAccount seller = createUser(Role.SELLER);
        OAuthFlowClient browser = browser();
        String verifier = newCodeVerifier();
        String code = queryParams(browser.authorizeAndLogin(authorizeParams(REDIRECT_URI, s256(verifier)),
                seller.getEmail(), PASSWORD)).get("code");

        assertThat(browser.exchangeCode(code, REDIRECT_URI, verifier).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = browser.exchangeCode(code, REDIRECT_URI, verifier);

        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(OAuthFlowClient.json(replay.body()).path("error").asText()).isEqualTo("invalid_grant");
    }

    @Test
    void wrongOrMissingCodeVerifierIsRejected() {
        UserAccount seller = createUser(Role.SELLER);
        OAuthFlowClient browser = browser();
        String verifier = newCodeVerifier();
        String code = queryParams(browser.authorizeAndLogin(authorizeParams(REDIRECT_URI, s256(verifier)),
                seller.getEmail(), PASSWORD)).get("code");

        HttpResponse<String> wrong = browser.exchangeCode(code, REDIRECT_URI, newCodeVerifier());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(OAuthFlowClient.json(wrong.body()).path("error").asText()).isEqualTo("invalid_grant");

        HttpResponse<String> missing = browser.exchangeCode(code, REDIRECT_URI, null);
        assertThat(missing.statusCode()).isIn(400, 401);
    }

    @Test
    void authorizationRequestWithoutPkceIsRejected() {
        UserAccount seller = createUser(Role.SELLER);

        URI callback = browser().authorizeAndLogin(authorizeParams(REDIRECT_URI, null), seller.getEmail(), PASSWORD);

        assertThat(callback.toString()).startsWith(REDIRECT_URI);
        assertThat(queryParams(callback)).containsEntry("error", "invalid_request").doesNotContainKey("code");
    }

    @Test
    void plainPkceMethodIsRejected() {
        UserAccount seller = createUser(Role.SELLER);
        Map<String, String> params = authorizeParams(REDIRECT_URI, "a".repeat(43));
        params.put("code_challenge_method", "plain");

        URI callback = browser().authorizeAndLogin(params, seller.getEmail(), PASSWORD);

        assertThat(queryParams(callback)).containsEntry("error", "invalid_request").doesNotContainKey("code");
    }

    @Test
    void unregisteredRedirectUriIsNeverRedirectedTo() {
        OAuthFlowClient browser = browser();
        String query = "response_type=code&client_id=ifp-spa&scope=openid&state=s&code_challenge_method=S256"
                + "&code_challenge=" + s256(newCodeVerifier())
                + "&redirect_uri=" + java.net.URLEncoder.encode("https://evil.example/callback", java.nio.charset.StandardCharsets.UTF_8);

        HttpResponse<String> response = browser.get(browser.base().resolve("/oauth2/authorize?" + query));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void pendingFinancierCannotSignIn() {
        UserAccount financier = createUser(Role.FINANCIER);

        URI afterLogin = browser().login(financier.getEmail(), PASSWORD);

        assertThat(afterLogin.getPath()).isEqualTo("/login");
        assertThat(afterLogin.getQuery()).contains("error");
    }

    @Test
    void wrongPasswordCannotSignIn() {
        UserAccount seller = createUser(Role.SELLER);

        URI afterLogin = browser().login(seller.getEmail(), "not the right passphrase");

        assertThat(afterLogin.getPath()).isEqualTo("/login");
        assertThat(afterLogin.getQuery()).contains("error");
    }

    @Test
    void accountDisabledAfterLoginReceivesNoToken() {
        UserAccount seller = createUser(Role.SELLER);
        OAuthFlowClient browser = browser();
        String verifier = newCodeVerifier();
        String code = queryParams(browser.authorizeAndLogin(authorizeParams(REDIRECT_URI, s256(verifier)),
                seller.getEmail(), PASSWORD)).get("code");

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = UUID_TO_BIN(?)", seller.getId().toString());
        HttpResponse<String> token = browser.exchangeCode(code, REDIRECT_URI, verifier);

        assertThat(token.statusCode()).isEqualTo(400);
        assertThat(OAuthFlowClient.json(token.body()).path("error").asText()).isEqualTo("invalid_grant");
    }

    private NimbusJwtDecoder resourceServerDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port + "/oauth2/jwks")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE))));
        return decoder;
    }

    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
