package com.ifp.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.support.OAuthFlowClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServerMetadataIT extends AbstractIntegrationTest {

    @Test
    void jwkSetPublishesOnlyPublicRsaSigningKeys() {
        OAuthFlowClient client = browser();
        HttpResponse<String> response = client.get(client.base().resolve("/oauth2/jwks"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode keys = OAuthFlowClient.json(response.body()).path("keys");
        assertThat(keys.size()).isEqualTo(1);
        JsonNode key = keys.get(0);
        assertThat(key.path("kty").asText()).isEqualTo("RSA");
        assertThat(key.path("use").asText()).isEqualTo("sig");
        assertThat(key.path("alg").asText()).isEqualTo("RS256");
        assertThat(key.path("kid").asText()).isNotBlank();
        // Private RSA parameters must never be published.
        assertThat(key.has("d") || key.has("p") || key.has("q") || key.has("dp") || key.has("dq") || key.has("qi")).isFalse();
    }

    @Test
    void discoveryDocumentAdvertisesTheFixedIssuerAndS256Only() {
        OAuthFlowClient client = browser();
        HttpResponse<String> response = client.get(client.base().resolve("/.well-known/openid-configuration"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metadata = OAuthFlowClient.json(response.body());
        assertThat(metadata.path("issuer").asText()).isEqualTo(ISSUER);
        assertThat(metadata.path("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
        assertThat(metadata.path("id_token_signing_alg_values_supported").toString()).contains("RS256");
    }

    @Test
    void tokenEndpointAllowsCorsFromTheSpaOriginOnly() {
        assertThat(preflight(SPA_ORIGIN).headers().firstValue("Access-Control-Allow-Origin")).contains(SPA_ORIGIN);

        HttpResponse<String> evil = preflight("https://evil.example");
        assertThat(evil.statusCode()).isEqualTo(403);
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void discoveryIsReadableCrossOriginBySpa() {
        HttpResponse<String> response = getWithOrigin("/.well-known/openid-configuration");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(SPA_ORIGIN);
    }

    @Test
    void apiEndpointsLeaveCorsToTheGateway() {
        HttpResponse<String> response = getWithOrigin("/api/users/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> getWithOrigin(String path) {
        OAuthFlowClient client = browser();
        return client.send(HttpRequest.newBuilder(client.base().resolve(path)).GET().header("Origin", SPA_ORIGIN).build());
    }

    private HttpResponse<String> preflight(String origin) {
        OAuthFlowClient client = browser();
        return client.send(HttpRequest.newBuilder(client.base().resolve("/oauth2/token"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build());
    }
}
