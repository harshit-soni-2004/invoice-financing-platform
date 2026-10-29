package com.ifp.auth.user;

import com.ifp.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final UserAdminService service = new UserAdminService(users, Clock.fixed(NOW, ZoneOffset.UTC), outbox);

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
    void rejectsPendingAccountAndPublishesUserRejected() {
        UserAccount financier = account(Role.FINANCIER);
        UUID admin = UUID.randomUUID();
        when(users.findById(financier.getId())).thenReturn(Optional.of(financier));

        UserAccount result = service.reject(financier.getId(), admin, "  Incomplete KYC documents ");

        assertThat(result.getStatus()).isEqualTo(UserStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("Incomplete KYC documents");
        assertThat(result.getRejectedAt()).isEqualTo(NOW);
        assertThat(result.canSignIn()).isFalse();
        verify(users).saveAndFlush(financier);
        verify(outbox).append(eq(UserAdminService.USER_REJECTED), eq("UserRejected"), eq("USER"), eq(financier.getId()),
                eq(admin), eq(new UserAdminService.UserRejected(financier.getId(), "FINANCIER", "Incomplete KYC documents")));
    }

    @Test
    void blankReasonIsStoredAsNone() {
        UserAccount financier = account(Role.FINANCIER);
        when(users.findById(financier.getId())).thenReturn(Optional.of(financier));

        assertThat(service.reject(financier.getId(), UUID.randomUUID(), "   ").getRejectionReason()).isNull();
    }

    @Test
    void refusesToRejectAccountThatIsNotPendingAndPublishesNothing() {
        UserAccount seller = account(Role.SELLER);
        when(users.findById(seller.getId())).thenReturn(Optional.of(seller));

        assertThatThrownBy(() -> service.reject(seller.getId(), UUID.randomUUID(), null))
                .isInstanceOf(UserNotPendingApprovalException.class)
                .extracting(e -> ((UserNotPendingApprovalException) e).currentStatus())
                .isEqualTo(UserStatus.ACTIVE);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void unknownUserIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.reject(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(outbox);
    }

    private static UserAccount account(Role role) {
        return UserAccount.selfRegistered(UUID.randomUUID(), role + "@example.test", "{bcrypt}hash", "User", role,
                NOW.minusSeconds(3600));
    }
}
