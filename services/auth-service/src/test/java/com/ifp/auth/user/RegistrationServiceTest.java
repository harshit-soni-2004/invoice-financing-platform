package com.ifp.auth.user;

import com.ifp.messaging.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(users, encoder, Clock.fixed(NOW, ZoneOffset.UTC), outbox);
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesNormalizedEmailAndBcryptHash() {
        UserAccount account = service.register(" Asha@Example.TEST ", "correct horse battery", "Asha", Role.SELLER);

        assertThat(account.getEmail()).isEqualTo("asha@example.test");
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}").doesNotContain("correct horse battery");
        assertThat(encoder.matches("correct horse battery", account.getPasswordHash())).isTrue();
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void registrationEmitsUserRegisteredWithoutThePassword() {
        UserAccount account = service.register("asha@example.test", "correct horse battery", "Asha", Role.FINANCIER);

        verify(outbox).append(eq(RegistrationService.USER_REGISTERED), eq("UserRegistered"), eq("USER"),
                eq(account.getId()), eq(account.getId()),
                eq(new RegistrationService.UserRegistered(account.getId(), "asha@example.test", "Asha", "FINANCIER")));
    }

    @Test
    void duplicateEmailIsRejectedBeforeHashing() {
        when(users.existsByEmail("asha@example.test")).thenReturn(true);

        assertThatThrownBy(() -> service.register("ASHA@example.test", "correct horse battery", "Asha", Role.SELLER))
                .isInstanceOf(DuplicateEmailException.class);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void concurrentDuplicateDetectedByUniqueKeyIsReportedAsDuplicate() {
        when(users.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("Duplicate entry for key 'users.uk_users_email'"));

        assertThatThrownBy(() -> service.register("asha@example.test", "correct horse battery", "Asha", Role.SELLER))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void otherIntegrityViolationsPropagate() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ck_users_role"));

        assertThatThrownBy(() -> service.register("asha@example.test", "correct horse battery", "Asha", Role.SELLER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adminCannotBeRegisteredEvenIfValidationIsBypassed() {
        assertThatThrownBy(() -> service.register("root@example.test", "correct horse battery", "Root", Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).saveAndFlush(any());
    }
}
