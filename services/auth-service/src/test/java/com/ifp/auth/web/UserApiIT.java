package com.ifp.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.auth.security.SigningKeys;
import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.user.UserStatus;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role and token-validation checks for auth-service's own API, using tokens issued by the real authorization flow. */
class UserApiIT extends AbstractIntegrationTest {

    @Autowired
    private JwtEncoder serverEncoder;

    @Autowired
    private SigningKeys signingKeys;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void meReturnsTheCallersOwnProfile() throws Exception {
        UserAccount seller = createUser(Role.SELLER);

        mockMvc.perform(withToken(get("/api/users/me"), issuedToken(seller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(seller.getId().toString()))
                .andExpect(jsonPath("$.email").value(seller.getEmail()))
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    @Test
    void tokensFailingAnyContractCheckAreUnauthorized() throws Exception {
        String subject = createUser(Role.SELLER).getId().toString();
        List<String> rejected = List.of(
                mint(c -> c.subject(subject).issuer("http://evil.example")),
                mint(c -> c.subject(subject).audience(List.of("some-other-api"))),
                mint(c -> c.subject("asha@example.test")),
                mint(c -> c.subject(subject).issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600))),
                attackerToken(subject));

        for (String token : rejected) {
            mockMvc.perform(withToken(get("/api/users/me"), token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        }
        // Control: the same server key with a valid contract is accepted.
        mockMvc.perform(withToken(get("/api/users/me"), mint(c -> c.subject(subject)))).andExpect(status().isOk());
    }

    @Test
    void onlyAdminsCanListUsers() throws Exception {
        UserAccount pending = createUser(Role.FINANCIER);

        mockMvc.perform(withToken(get("/api/admin/users"), issuedToken(createUser(Role.SELLER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(withToken(get("/api/admin/users"), activatedFinancierToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(get("/api/admin/users").param("role", "FINANCIER").param("status", "PENDING_APPROVAL")
                        .param("size", "100"), issuedToken(createUser(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(pending.getId().toString())))
                .andExpect(jsonPath("$.content[*].status", not(hasItem("ACTIVE"))))
                .andExpect(jsonPath("$.content[*].passwordHash").doesNotExist());
    }

    @Test
    void listRejectsOversizedPagesAndUnknownFilters() throws Exception {
        String admin = issuedToken(createUser(Role.ADMIN));

        mockMvc.perform(withToken(get("/api/admin/users").param("size", "101"), admin)).andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/admin/users").param("role", "ROOT"), admin)).andExpect(status().isBadRequest());
    }

    @Test
    void adminActivatesPendingFinancierWhoCanThenSignIn() throws Exception {
        UserAccount financier = createUser(Role.FINANCIER);
        String admin = issuedToken(createUser(Role.ADMIN));

        mockMvc.perform(withToken(post("/api/admin/users/{id}/activate", financier.getId()), issuedToken(createUser(Role.SELLER))))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(post("/api/admin/users/{id}/activate", financier.getId()), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String financierToken = issuedToken(financier);
        mockMvc.perform(withToken(get("/api/users/me"), financierToken))
                .andExpect(jsonPath("$.role").value("FINANCIER"));

        mockMvc.perform(withToken(post("/api/admin/users/{id}/activate", financier.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.currentStatus").value("ACTIVE"));
        mockMvc.perform(withToken(post("/api/admin/users/{id}/activate", UUID.randomUUID()), admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsCanRejectUsers() throws Exception {
        UserAccount financier = createUser(Role.FINANCIER);

        mockMvc.perform(post("/api/admin/users/{id}/reject", financier.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", financier.getId()), issuedToken(createUser(Role.SELLER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", financier.getId()), activatedFinancierToken()))
                .andExpect(status().isForbidden());

        assertThat(users.findById(financier.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING_APPROVAL);
        assertThat(rejectedEvents(financier.getId())).isZero();
    }

    @Test
    void adminRejectsPendingFinancierWhoCanNeverSignInOrBeActivated() throws Exception {
        UserAccount financier = createUser(Role.FINANCIER);
        UserAccount adminAccount = createUser(Role.ADMIN);
        String admin = issuedToken(adminAccount);

        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", financier.getId()), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Could not verify the lending licence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(financier.getId().toString()))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        UserAccount stored = users.findById(financier.getId()).orElseThrow();
        assertThat(stored.getRejectionReason()).isEqualTo("Could not verify the lending licence");
        assertThat(stored.getRejectedAt()).isNotNull();

        // Repeat calls and a late activation are refused and change nothing.
        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", financier.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
        mockMvc.perform(withToken(post("/api/admin/users/{id}/activate", financier.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
        assertThat(users.findById(financier.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.REJECTED);

        // No sign-in, hence no authorization code and no tokens.
        URI afterLogin = browser().login(financier.getEmail(), PASSWORD);
        assertThat(afterLogin.getPath()).isEqualTo("/login");
        assertThat(afterLogin.getQuery()).contains("error");
        assertThatThrownBy(() -> issuedToken(financier)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authorization code");

        // Exactly one UserRejected event, written by the admin, relayed to the broker (audit-service consumes '#').
        assertThat(rejectedEvents(financier.getId())).isEqualTo(1);
        JsonNode envelope = objectMapper.readTree(jdbc.queryForObject(
                "select envelope from outbox_events where aggregate_id = ? and event_type = 'UserRejected'",
                String.class, financier.getId().toString()));
        assertThat(envelope.path("actorId").asText()).isEqualTo(adminAccount.getId().toString());
        assertThat(envelope.path("aggregateType").asText()).isEqualTo("USER");
        assertThat(envelope.path("payload").path("role").asText()).isEqualTo("FINANCIER");
        assertThat(envelope.path("payload").path("reason").asText()).isEqualTo("Could not verify the lending licence");
        assertThat(envelope.toString()).doesNotContain(financier.getEmail());
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline) && !rejectedEventPublished(financier.getId())) {
            Thread.sleep(200);
        }
        assertThat(rejectedEventPublished(financier.getId())).as("relayed to the broker").isTrue();

        mockMvc.perform(withToken(get("/api/admin/users").param("status", "REJECTED").param("size", "100"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(financier.getId().toString())));
    }

    @Test
    void rejectionBodyIsOptionalAndValidatedAndOnlyPendingAccountsQualify() throws Exception {
        String admin = issuedToken(createUser(Role.ADMIN));
        UserAccount noReason = createUser(Role.FINANCIER);
        UserAccount tooLong = createUser(Role.FINANCIER);

        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", noReason.getId()), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(users.findById(noReason.getId()).orElseThrow().getRejectionReason()).isNull();

        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", tooLong.getId()), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        assertThat(users.findById(tooLong.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.PENDING_APPROVAL);
        assertThat(rejectedEvents(tooLong.getId())).isZero();

        UserAccount seller = createUser(Role.SELLER);
        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", seller.getId()), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("ACTIVE"));
        assertThat(rejectedEvents(seller.getId())).isZero();
        mockMvc.perform(withToken(post("/api/admin/users/{id}/reject", UUID.randomUUID()), admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void everyApiHandlerDeclaresAnAuthorizationRule() {
        List<HandlerMethod> appHandlers = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("com.ifp.auth.web"))
                .toList();

        assertThat(appHandlers).hasSizeGreaterThanOrEqualTo(4);
        assertThat(appHandlers).allSatisfy(handler -> assertThat(handler.hasMethodAnnotation(PreAuthorize.class))
                .as("%s must declare @PreAuthorize", handler.getShortLogMessage())
                .isTrue());
    }

    private int rejectedEvents(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'UserRejected'",
                Integer.class, userId.toString());
        return count == null ? 0 : count;
    }

    private boolean rejectedEventPublished(UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from outbox_events
                where aggregate_id = ? and event_type = 'UserRejected' and published_at is not null""",
                Integer.class, userId.toString());
        return count != null && count == 1;
    }

    private String issuedToken(UserAccount user) {
        return browser().obtainTokens(user.getEmail(), PASSWORD, REDIRECT_URI).path("access_token").asText();
    }

    private String activatedFinancierToken() {
        UserAccount financier = createUser(Role.FINANCIER);
        users.findById(financier.getId()).ifPresent(account -> {
            account.activate(Instant.now());
            users.saveAndFlush(account);
        });
        return issuedToken(financier);
    }

    /** Signed with the server's real key; the customizer lets each test break exactly one claim. */
    private String mint(Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("SELLER"));
        customizer.accept(claims);
        return serverEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    /** Valid claims, signed by a different key that reuses the active key ID, so only the signature check can catch it. */
    private String attackerToken(String subject) throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID(signingKeys.activeKeyId()).generate();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(attacker)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE)).subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of("ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
