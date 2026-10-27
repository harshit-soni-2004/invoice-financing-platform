package com.ifp.auth.security.ratelimit;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Counts failed username/password logins towards the lockout. The failed token still carries the submitted
 * username. Unknown usernames are reported as bad credentials too, so probing for accounts is throttled the same
 * way. Bearer-token and OAuth2 client authentications are ignored.
 */
public class LoginOutcomeListener {

    private final LoginAttemptLimiter limiter;

    public LoginOutcomeListener(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        Authentication authentication = event.getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken
                && authentication.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            limiter.recordFailure(details.getRemoteAddress(), authentication.getName());
        }
    }
}
