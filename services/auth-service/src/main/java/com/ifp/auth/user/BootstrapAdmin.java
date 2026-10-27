package com.ifp.auth.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Creates the first ADMIN when configured and none exists yet. Public registration can never create one. */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final BootstrapAdminProperties properties;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public BootstrapAdmin(BootstrapAdminProperties properties, UserAccountRepository users,
                          PasswordEncoder passwordEncoder, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured() || users.existsByRole(Role.ADMIN)) {
            return;
        }
        String email = UserAccount.normalizeEmail(properties.email());
        PasswordPolicy.violation(properties.password(), email).ifPresent(reason -> {
            throw new IllegalStateException("auth.bootstrap-admin.password " + reason);
        });
        if (users.existsByEmail(email)) {
            log.warn("Bootstrap administrator not created: the configured email belongs to an existing non-admin account");
            return;
        }
        UserAccount admin = UserAccount.administrator(UUID.randomUUID(), email,
                passwordEncoder.encode(properties.password()), properties.displayNameOrDefault(), clock.instant());
        users.save(admin);
        log.info("Bootstrap administrator created: id={}", admin.getId());
    }
}
