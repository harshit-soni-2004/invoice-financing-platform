package com.ifp.auth.user;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountUserDetailsServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccountUserDetailsService service = new AccountUserDetailsService(users);

    @Test
    void principalNameIsTheUserIdAndAuthorityIsTheRole() {
        UUID id = UUID.randomUUID();
        when(users.findByEmail("asha@example.test")).thenReturn(Optional.of(
                UserAccount.selfRegistered(id, "asha@example.test", "{bcrypt}hash", "Asha", Role.SELLER, Instant.now())));

        UserDetails details = service.loadUserByUsername(" Asha@Example.test ");

        assertThat(details.getUsername()).isEqualTo(id.toString());
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_SELLER");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void pendingFinancierIsDisabled() {
        when(users.findByEmail("fin@example.test")).thenReturn(Optional.of(
                UserAccount.selfRegistered(UUID.randomUUID(), "fin@example.test", "{bcrypt}hash", "Fin", Role.FINANCIER,
                        Instant.now())));

        assertThat(service.loadUserByUsername("fin@example.test").isEnabled()).isFalse();
    }

    @Test
    void unknownEmailFailsWithoutRevealingWhichPartWasWrong() {
        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.test"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Bad credentials");
    }
}
