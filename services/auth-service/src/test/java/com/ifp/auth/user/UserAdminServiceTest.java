package com.ifp.auth.user;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final UserAdminService service = new UserAdminService(users, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void activatesPendingAccount() {
        UserAccount financier = account(Role.FINANCIER);
        when(users.findById(financier.getId())).thenReturn(Optional.of(financier));

        UserAccount result = service.activate(financier.getId(), UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        verify(users).saveAndFlush(financier);
    }

    @Test
    void refusesToActivateAccountThatIsNotPending() {
        UserAccount seller = account(Role.SELLER);
        when(users.findById(seller.getId())).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.activate(seller.getId(), UUID.randomUUID()))
                .isInstanceOf(UserNotPendingApprovalException.class)
                .extracting(e -> ((UserNotPendingApprovalException) e).currentStatus())
                .isEqualTo(UserStatus.ACTIVE);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void unknownUserIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(UserNotFoundException.class);
    }

    private static UserAccount account(Role role) {
        return UserAccount.selfRegistered(UUID.randomUUID(), role + "@example.test", "{bcrypt}hash", "User", role,
                NOW.minusSeconds(3600));
    }
}
