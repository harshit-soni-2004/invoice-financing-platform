package com.ifp.auth.user;

import java.time.Duration;

/** Too many wrong current passwords; the caller must wait before trying again. */
public class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super("Too many incorrect attempts. Try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
