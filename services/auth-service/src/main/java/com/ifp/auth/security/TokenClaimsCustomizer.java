package com.ifp.auth.security;

import com.ifp.auth.config.AuthServerProperties;
import com.ifp.auth.user.UserAccount;
import com.ifp.auth.user.UserAccountRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Shapes tokens to the platform contract: {@code sub} = user UUID (the principal name), {@code roles} = the user's
 * role, and access-token {@code aud} = the API audience. Role and status are re-read from the database for every
 * token, so a role change or a disabled account takes effect at the next token instead of when the login session ends.
 */
@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    public static final String ROLES_CLAIM = "roles";

    private final UserAccountRepository users;
    private final String audience;

    public TokenClaimsCustomizer(UserAccountRepository users, AuthServerProperties properties) {
        this.users = users;
        this.audience = properties.audience();
    }

    @Override
    public void customize(JwtEncodingContext context) {
        boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
        boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
        if (!accessToken && !idToken) {
            return;
        }
        UserAccount account = activeAccount(context.getPrincipal().getName());
        // Claims are persisted by JdbcOAuth2AuthorizationService, whose Jackson allowlist rejects List.of(...).
        context.getClaims().claim(ROLES_CLAIM, Collections.singletonList(account.getRole().name()));
        if (accessToken) {
            context.getClaims().audience(Collections.singletonList(audience));
        } else {
            context.getClaims().claim("name", account.getDisplayName());
        }
    }

    private UserAccount activeAccount(String principalName) {
        return parseUuid(principalName)
                .flatMap(users::findById)
                .filter(UserAccount::canSignIn)
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "The account is not active", null)));
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
