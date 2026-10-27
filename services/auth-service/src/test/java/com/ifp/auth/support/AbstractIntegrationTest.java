package com.ifp.auth.support;

import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.user.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared configuration so all integration tests reuse one cached Spring context and one MySQL container.
 * A real port is used because the OAuth2 flow tests drive the server over HTTP with cookies and redirects.
 * Tests isolate their data with unique emails instead of truncating tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.issuer=" + AbstractIntegrationTest.ISSUER,
        "auth.jwt.allow-ephemeral-key=true",
        "auth.spa.redirect-uris=" + AbstractIntegrationTest.REDIRECT_URI,
        "auth.spa.allowed-origins=" + AbstractIntegrationTest.SPA_ORIGIN
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    public static final String ISSUER = "http://auth.test.ifp.local";
    public static final String AUDIENCE = "ifp-api";
    public static final String SPA_ORIGIN = "http://localhost:4200";
    public static final String REDIRECT_URI = SPA_ORIGIN + "/auth/callback";
    /** Test-only passphrase for throwaway accounts in a disposable database. */
    public static final String PASSWORD = "correct horse battery";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserAccountRepository users;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @LocalServerPort
    protected int port;

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected UserAccount createUser(Role role) {
        String email = uniqueEmail(role.name().toLowerCase());
        String hash = passwordEncoder.encode(PASSWORD);
        UserAccount account = role == Role.ADMIN
                ? UserAccount.administrator(UUID.randomUUID(), email, hash, "Test Admin", Instant.now())
                : UserAccount.selfRegistered(UUID.randomUUID(), email, hash, "Test " + role, role, Instant.now());
        return users.saveAndFlush(account);
    }

    protected OAuthFlowClient browser() {
        return new OAuthFlowClient(port);
    }
}
