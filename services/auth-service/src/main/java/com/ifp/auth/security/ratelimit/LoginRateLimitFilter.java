package com.ifp.auth.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Throttles {@code POST /login} and {@code POST /oauth2/token} per client address, and refuses logins for a
 * client address + username combination that is locked out after repeated failures. Sits in the security filter
 * chains after CORS (so a 429 is readable cross-origin) and before CSRF and authentication.
 *
 * <p>The client address is the TCP peer address. {@code X-Forwarded-For} is deliberately ignored: these endpoints
 * are reached directly rather than through the gateway, and the header is client-controlled.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN = "/login";
    static final String TOKEN = "/oauth2/token";

    private static final String BODY = """
            {"type":"about:blank","title":"Too Many Requests","status":429,\
            "detail":"Too many attempts. Try again later.","code":"RATE_LIMITED"}""";

    private final LoginAttemptLimiter limiter;
    private final boolean enabled;

    public LoginRateLimitFilter(LoginAttemptLimiter limiter, boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || !"POST".equals(request.getMethod())) {
            return true;
        }
        String path = path(request);
        return !LOGIN.equals(path) && !TOKEN.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        String clientAddress = request.getRemoteAddr();
        String username = LOGIN.equals(path) ? request.getParameter("username") : null;
        Optional<Duration> wait = Optional.empty();
        if (StringUtils.hasText(username)) {
            wait = limiter.checkLockout(clientAddress, username);
        }
        if (wait.isEmpty()) {
            wait = limiter.checkRequestRate(path, clientAddress);
        }
        if (wait.isPresent()) {
            reject(response, wait.get());
            return;
        }
        chain.doFilter(request, response);
        // The authenticated principal is named by user id, so the reset uses the submitted username instead.
        if (StringUtils.hasText(username) && loggedIn()) {
            limiter.recordSuccess(clientAddress, username);
        }
    }

    private static boolean loggedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated();
    }

    private static void reject(HttpServletResponse response, Duration wait) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000)));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(BODY);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
