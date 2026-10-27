package com.ifp.gateway.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Stands in for auth-service and invoice-service. Records every request it receives so tests can assert what the
 * gateway forwarded (and that rejected requests never arrive).
 * <ul>
 *   <li>{@code .../fail} answers 500 with a downstream problem body</li>
 *   <li>{@code .../slow} answers after 3 seconds</li>
 *   <li>everything else answers 200 with a small JSON body, echoing {@code X-Correlation-Id}</li>
 * </ul>
 */
public final class DownstreamStub {

    public record Received(String method, String path, Map<String, List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }

        public List<String> headerNames() {
            return headers.keySet().stream().map(String::toLowerCase).toList();
        }
    }

    private static final ConcurrentLinkedDeque<Received> RECEIVED = new ConcurrentLinkedDeque<>();
    private static final HttpServer SERVER = start();

    private DownstreamStub() {
    }

    public static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    public static List<Received> receivedFor(String path) {
        return RECEIVED.stream().filter(r -> r.path().equals(path)).toList();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Headers requestHeaders = exchange.getRequestHeaders();
        RECEIVED.add(new Received(exchange.getRequestMethod(), path,
                requestHeaders.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())))));
        exchange.getRequestBody().readAllBytes();

        int status = 200;
        String body = "{\"ok\":true}";
        if (path.endsWith("/fail")) {
            status = 500;
            body = "{\"code\":\"INTERNAL_ERROR\",\"detail\":\"An unexpected error occurred\"}";
        } else if (path.endsWith("/slow")) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String correlationId = requestHeaders.getFirst("X-Correlation-Id");
        if (correlationId != null) {
            exchange.getResponseHeaders().add("X-Correlation-Id", correlationId);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("ETag", "\"0\"");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", DownstreamStub::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start downstream stub", e);
        }
    }
}
