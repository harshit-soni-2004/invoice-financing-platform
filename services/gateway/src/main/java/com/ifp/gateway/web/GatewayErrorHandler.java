package com.ifp.gateway.web;

import com.ifp.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Turns gateway-side failures into fixed problem responses: an unreachable service becomes 503, a slow one 504,
 * a missing route 404. Ordered ahead of Spring Boot's default handler, whose body could echo exception messages
 * (for example downstream host names). Responses that downstream services return, including their own errors,
 * are passed through unchanged and never reach this handler.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String correlationId = exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
        if (correlationId == null) {
            return respond(exchange, ex);
        }
        // Reactor does not carry the MDC across threads; the log calls below run synchronously on this one.
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.MDC_KEY, correlationId)) {
            return respond(exchange, ex);
        }
    }

    private Mono<Void> respond(ServerWebExchange exchange, Throwable ex) {
        if (isUnreachable(ex)) {
            log.warn("Downstream unavailable: path={} cause={}", exchange.getRequest().getPath(),
                    ex.getClass().getSimpleName());
            return ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE",
                    "The service is temporarily unavailable. Try again later.");
        }
        if (ex instanceof ResponseStatusException statusException) {
            HttpStatusCode code = statusException.getStatusCode();
            if (code.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                log.warn("Downstream timed out: path={}", exchange.getRequest().getPath());
                return ProblemResponses.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "DOWNSTREAM_TIMEOUT",
                        "The service did not respond in time. Try again later.");
            }
            if (code.value() == HttpStatus.NOT_FOUND.value()) {
                return ProblemResponses.write(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint");
            }
            if (code.is4xxClientError()) {
                HttpStatus status = HttpStatus.resolve(code.value());
                return ProblemResponses.write(exchange, status == null ? HttpStatus.BAD_REQUEST : status, "BAD_REQUEST",
                        "The request could not be processed");
            }
        }
        log.error("Unhandled gateway error: path={}", exchange.getRequest().getPath(), ex);
        return ProblemResponses.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred");
    }

    /** Netty's connect timeout ({@code io.netty.channel.ConnectTimeoutException}) is also a ConnectException. */
    private static boolean isUnreachable(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }
}
