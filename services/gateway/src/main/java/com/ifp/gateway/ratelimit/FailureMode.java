package com.ifp.gateway.ratelimit;

/** What a rate limiter does when Redis cannot answer in time. */
public enum FailureMode {
    /** Allow the request. Authentication and authorization still apply; only throttling is lost. */
    FAIL_OPEN,
    /** Reject the request with 429. Used where unthrottled traffic is itself the abuse (e.g. account creation). */
    FAIL_CLOSED
}
