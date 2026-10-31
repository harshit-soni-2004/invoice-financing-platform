package com.ifp.auth.web;

import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-service account changes, with tokens from the real authorization flow. */
class AccountApiIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "lantern orchard meadow";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aUserRenamesThemselvesAndCannotChangeTheirEmailOrRole() throws Exception {
        UserAccount seller = createUser(Role.SELLER);
        String token = issuedToken(seller);

        updateProfile(token, """
                {"displayName":"  Asha Rao  ","email":"someone-else@example.test","role":"ADMIN"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha Rao"))
                .andExpect(jsonPath("$.email").value(seller.getEmail()))
                .andExpect(jsonPath("$.role").value("SELLER"));

        UserAccount stored = users.findById(seller.getId()).orElseThrow();
        assertThat(stored.getDisplayName()).isEqualTo("Asha Rao");
        assertThat(stored.getEmail()).isEqualTo(seller.getEmail());
        assertThat(stored.getRole()).isEqualTo(Role.SELLER);
        assertThat(events(seller.getId(), "UserProfileUpdated")).isEqualTo(1);

        // Saving the same name again changes nothing and publishes nothing.
        updateProfile(token, "{\"displayName\":\"Asha Rao\"}").andExpect(status().isOk());
        assertThat(events(seller.getId(), "UserProfileUpdated")).isEqualTo(1);
    }

    @Test
    void aBlankOrTooLongNameIsRefusedOnThatField() throws Exception {
        String token = issuedToken(createUser(Role.SELLER));

        updateProfile(token, "{\"displayName\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        updateProfile(token, "{\"displayName\":\"" + "x".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    }

    @Test
    void aPasswordChangeNeedsTheCurrentPasswordAndAnAcceptableNewOne() throws Exception {
        UserAccount seller = createUser(Role.SELLER);
        String token = issuedToken(seller);

        changePassword(token, "not the current one", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
        changePassword(token, PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        changePassword(token, PASSWORD, seller.getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        changePassword(token, PASSWORD, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be different from the current password"));
        assertThat(passwordEncoder.matches(PASSWORD, users.findById(seller.getId()).orElseThrow().getPasswordHash()))
                .isTrue();

        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        URI withOldPassword = browser().login(seller.getEmail(), PASSWORD);
        assertThat(withOldPassword.getQuery()).contains("error");
        URI withNewPassword = browser().login(seller.getEmail(), NEW_PASSWORD);
        assertThat(withNewPassword.getPath()).isNotEqualTo("/login");

        assertThat(events(seller.getId(), "UserPasswordChanged")).isEqualTo(1);
        String envelope = jdbc.queryForObject(
                "select envelope from outbox_events where aggregate_id = ? and event_type = 'UserPasswordChanged'",
                String.class, seller.getId().toString());
        assertThat(envelope).doesNotContain(PASSWORD).doesNotContain(NEW_PASSWORD).doesNotContain("$2a$");
    }

    @Test
    void repeatedWrongCurrentPasswordsLockPasswordChangesForThatUser() throws Exception {
        String token = issuedToken(createUser(Role.SELLER));
        for (int i = 0; i < 5; i++) {
            changePassword(token, "wrong-password-" + i, NEW_PASSWORD).andExpect(status().isBadRequest());
        }

        changePassword(token, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void accountChangesRequireAToken() throws Exception {
        mockMvc.perform(put("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"X\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions updateProfile(String token, String body) throws Exception {
        return mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        String body = "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
        return mockMvc.perform(post("/api/users/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int events(UUID userId, String type) {
        Integer count = jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = ?",
                Integer.class, userId.toString(), type);
        return count == null ? 0 : count;
    }

    private String issuedToken(UserAccount user) {
        return browser().obtainTokens(user.getEmail(), PASSWORD, REDIRECT_URI).path("access_token").asText();
    }
}
