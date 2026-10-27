package com.ifp.auth.web;

import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationApiIT extends AbstractIntegrationTest {

    @Test
    void sellerRegistersAndIsActive() throws Exception {
        String email = uniqueEmail("Seller");

        register(email, PASSWORD, "SELLER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        UserAccount stored = users.findByEmail(email.toLowerCase()).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
    }

    @Test
    void financierRegistersPendingApproval() throws Exception {
        register(uniqueEmail("fin"), PASSWORD, "FINANCIER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void adminSelfRegistrationIsRejected() throws Exception {
        String email = uniqueEmail("wannabe-admin");

        register(email, PASSWORD, "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        register(uniqueEmail("root"), PASSWORD, "SUPERUSER").andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailInAnyCaseIsConflict() throws Exception {
        String email = uniqueEmail("dup");
        register(email, PASSWORD, "SELLER").andExpect(status().isCreated());

        register(email.toUpperCase(), PASSWORD, "FINANCIER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void weakPasswordIsRejectedWithoutEchoingIt() throws Exception {
        register(uniqueEmail("weak"), "hunter2-abc", "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(content().string(not(containsString("hunter2-abc"))));
    }

    @Test
    void otherApiPathsAreClosedWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginPageIsServedWithCsrfProtection() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mockMvc.perform(post("/login").param("username", "x@example.test").param("password", "whatever-long"))
                .andExpect(status().isForbidden());
    }

    private ResultActions register(String email, String password, String role) throws Exception {
        String body = """
                {"email":"%s","password":"%s","displayName":"Test User","role":"%s"}""".formatted(email, password, role);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
