package com.ifp.auth.persistence;

import com.ifp.auth.support.AbstractIntegrationTest;
import com.ifp.auth.user.Role;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountRepositoryIT extends AbstractIntegrationTest {

    @Test
    void roundTripsAllFieldsWithMicrosecondPrecision() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID id = UUID.randomUUID();
        String email = uniqueEmail("Round.Trip");
        users.saveAndFlush(UserAccount.selfRegistered(id, email, "{bcrypt}hash", "Round Trip", Role.FINANCIER, now));

        UserAccount loaded = users.findByEmail(UserAccount.normalizeEmail(email)).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(loaded.getRole()).isEqualTo(Role.FINANCIER);
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.PENDING_APPROVAL);
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(UserAccount.selfRegistered(id, uniqueEmail("lock"), "{bcrypt}hash", "Lock", Role.FINANCIER,
                Instant.now()));
        UserAccount first = users.findById(id).orElseThrow();
        UserAccount stale = users.findById(id).orElseThrow();

        first.activate(Instant.now());
        users.saveAndFlush(first);
        stale.activate(Instant.now().plusSeconds(1));
        // The stale copy is still PENDING_APPROVAL in memory, so it also changes and must be rejected on flush.
        assertThatThrownBy(() -> users.saveAndFlush(stale)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void existsByRoleReportsAdministrators() {
        users.saveAndFlush(UserAccount.administrator(UUID.randomUUID(), uniqueEmail("admin"), "{bcrypt}hash", "Admin",
                Instant.now()));

        assertThat(users.existsByRole(Role.ADMIN)).isTrue();
    }
}
