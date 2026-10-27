package com.ifp.auth.user;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void sellerIsActiveImmediately() {
        UserAccount seller = register(Role.SELLER);

        assertThat(seller.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(seller.canSignIn()).isTrue();
    }

    @Test
    void financierWaitsForApproval() {
        UserAccount financier = register(Role.FINANCIER);

        assertThat(financier.getStatus()).isEqualTo(UserStatus.PENDING_APPROVAL);
        assertThat(financier.canSignIn()).isFalse();
    }

    @Test
    void adminCannotBeSelfRegistered() {
        assertThatThrownBy(() -> register(Role.ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Role.ADMIN.isSelfRegistrable()).isFalse();
    }

    @Test
    void administratorFactoryCreatesActiveAdmin() {
        UserAccount admin = UserAccount.administrator(UUID.randomUUID(), "ops@example.test", "{bcrypt}hash", "Ops", NOW);

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.canSignIn()).isTrue();
    }

    @Test
    void emailIsTrimmedAndLowerCased() {
        UserAccount user = UserAccount.selfRegistered(UUID.randomUUID(), "  Asha.Rao@Example.TEST ", "{bcrypt}hash",
                " Asha Rao ", Role.SELLER, NOW);

        assertThat(user.getEmail()).isEqualTo("asha.rao@example.test");
        assertThat(user.getDisplayName()).isEqualTo("Asha Rao");
    }

    @Test
    void activationOnlyChangesPendingAccounts() {
        UserAccount financier = register(Role.FINANCIER);

        assertThat(financier.activate(LATER)).isTrue();
        assertThat(financier.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(financier.getUpdatedAt()).isEqualTo(LATER);

        assertThat(financier.activate(LATER.plusSeconds(1))).isFalse();
        assertThat(financier.getUpdatedAt()).isEqualTo(LATER);
    }

    private static UserAccount register(Role role) {
        return UserAccount.selfRegistered(UUID.randomUUID(), "user@example.test", "{bcrypt}hash", "User", role, NOW);
    }
}
