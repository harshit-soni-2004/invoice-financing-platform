package com.ifp.auth.user;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users sign in with their email, but the authenticated principal's name is the user ID. Tokens therefore carry
 * the UUID as {@code sub} (the contract every resource server enforces), and an email change never changes identity.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    public AccountUserDetailsService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        UserAccount account = users.findByEmail(UserAccount.normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
        return User.withUsername(account.getId().toString())
                .password(account.getPasswordHash())
                .authorities(new SimpleGrantedAuthority(account.getRole().authority()))
                .disabled(!account.canSignIn())
                .build();
    }
}
