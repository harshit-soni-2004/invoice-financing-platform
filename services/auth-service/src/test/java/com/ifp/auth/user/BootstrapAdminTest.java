package com.ifp.auth.user;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void doesNothingWhenNotConfigured() {
        bootstrap(new BootstrapAdminProperties("", "", null)).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(users.existsByRole(Role.ADMIN)).thenReturn(true);

        bootstrap(new BootstrapAdminProperties("ops@example.test", "a long admin passphrase", null)).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void createsActiveAdminWithHashedPassword() {
        bootstrap(new BootstrapAdminProperties(" Ops@Example.test ", "a long admin passphrase", null)).run(null);

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getEmail()).isEqualTo("ops@example.test");
        assertThat(saved.getValue().canSignIn()).isTrue();
        assertThat(encoder.matches("a long admin passphrase", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void weakPasswordFailsStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> bootstrap(new BootstrapAdminProperties("ops@example.test", "short", null)).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("short")
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    void neverPromotesAnExistingNonAdminAccount() {
        when(users.existsByEmail("seller@example.test")).thenReturn(true);

        bootstrap(new BootstrapAdminProperties("seller@example.test", "a long admin passphrase", null)).run(null);

        verify(users, never()).save(any());
    }

    private BootstrapAdmin bootstrap(BootstrapAdminProperties properties) {
        return new BootstrapAdmin(properties, users, encoder, Clock.systemUTC());
    }
}
