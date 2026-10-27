package com.ifp.auth.security;

import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the default limits: 30 requests per address per minute, lockout after 5 failures. */
class LoginRateLimitIT extends AbstractIntegrationTest {

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void fiveBadPasswordsLockOutThatAddressAndUsernameEvenForTheRightPassword() throws Exception {
        UserAccount user = createUser(Role.SELLER);
        String address = uniqueAddress();
        for (int i = 0; i < 5; i++) {
            login(address, user.getEmail(), "wrong-password-" + i).andExpect(redirectedUrl("/login?error"));
        }

        login(address, user.getEmail(), PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        login(address, user.getEmail().toUpperCase(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void lockoutIsScopedToTheAddressAndUsernamePair() throws Exception {
        UserAccount victim = createUser(Role.SELLER);
        UserAccount other = createUser(Role.SELLER);
        String attacker = uniqueAddress();
        for (int i = 0; i < 5; i++) {
            login(attacker, victim.getEmail(), "wrong-password-" + i);
        }

        login(uniqueAddress(), victim.getEmail(), PASSWORD).andExpect(redirectedUrl("/"));
        login(attacker, other.getEmail(), PASSWORD).andExpect(redirectedUrl("/"));
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        UserAccount user = createUser(Role.SELLER);
        String address = uniqueAddress();
        for (int i = 0; i < 4; i++) {
            login(address, user.getEmail(), "wrong-password-" + i);
        }
        login(address, user.getEmail(), PASSWORD).andExpect(redirectedUrl("/"));
        for (int i = 0; i < 4; i++) {
            login(address, user.getEmail(), "wrong-password-" + i).andExpect(redirectedUrl("/login?error"));
        }
        login(address, user.getEmail(), PASSWORD).andExpect(redirectedUrl("/"));
    }

    @Test
    void unknownUsernamesAreThrottledLikeBadPasswords() throws Exception {
        String address = uniqueAddress();
        String nobody = uniqueEmail("nobody");
        for (int i = 0; i < 5; i++) {
            login(address, nobody, "wrong-password-" + i).andExpect(redirectedUrl("/login?error"));
        }
        login(address, nobody, "wrong-password-x").andExpect(status().isTooManyRequests());
    }

    @Test
    void tokenEndpointIsThrottledPerClientAddress() throws Exception {
        String address = uniqueAddress();
        for (int i = 0; i < 30; i++) {
            token(address).andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }
        token(address)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(header().string("Content-Type", endsWith("problem+json")));
        token(uniqueAddress()).andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    @Test
    void redisKeysContainNeitherTheUsernameNorTheAddress() throws Exception {
        UserAccount user = createUser(Role.SELLER);
        String address = uniqueAddress();
        login(address, user.getEmail(), "wrong-password");

        assertThat(redis.keys("auth:rl:*")).isNotEmpty()
                .noneMatch(key -> key.contains(user.getEmail()) || key.contains(address));
    }

    private ResultActions login(String address, String username, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf()).with(from(address))
                .param("username", username).param("password", password));
    }

    private ResultActions token(String address) throws Exception {
        return mockMvc.perform(post("/oauth2/token").with(from(address))
                .param("grant_type", "authorization_code").param("client_id", "ifp-spa")
                .param("code", "not-a-real-code").param("code_verifier", "x".repeat(43))
                .param("redirect_uri", REDIRECT_URI));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String uniqueAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "198.51.100." + n;
    }
}
