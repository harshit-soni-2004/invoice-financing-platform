package com.ifp.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A browser-like client for the real Authorization Code + PKCE flow: it keeps cookies (the login session), never
 * follows redirects automatically (so each hop can be asserted), and submits the login form with its CSRF token.
 */
public final class OAuthFlowClient {

    public static final String CLIENT_ID = "ifp-spa";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI base;
    private final HttpClient http;

    public OAuthFlowClient(int port) {
        this.base = URI.create("http://localhost:" + port);
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI base() {
        return base;
    }

    // ---- PKCE ----

    public static String newCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> authorizeParams(String redirectUri, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", CLIENT_ID);
        params.put("redirect_uri", redirectUri);
        params.put("scope", "openid profile");
        params.put("state", "state-" + RANDOM.nextInt(1_000_000));
        if (codeChallenge != null) {
            params.put("code_challenge", codeChallenge);
            params.put("code_challenge_method", "S256");
        }
        return params;
    }

    // ---- Flow steps ----

    /** Signs in through the login form. Returns the redirect target after the POST. */
    public URI login(String email, String password) {
        HttpResponse<String> page = get(base.resolve("/login"));
        Matcher csrf = CSRF.matcher(page.body());
        if (page.statusCode() != 200 || !csrf.find()) {
            throw new IllegalStateException("Login page not served as expected: HTTP " + page.statusCode());
        }
        HttpResponse<String> response = postForm("/login", Map.of(
                "username", email, "password", password, "_csrf", csrf.group(1)));
        return location(response);
    }

    /** Runs authorize → login → authorize and returns the final redirect (to the client, carrying code or error). */
    public URI authorizeAndLogin(Map<String, String> params, String email, String password) {
        HttpResponse<String> first = get(base.resolve("/oauth2/authorize?" + form(params)));
        URI next = location(first);
        if (next.getPath().equals("/login")) {
            URI afterLogin = login(email, password);
            if (!afterLogin.getPath().equals("/oauth2/authorize")) {
                return afterLogin;
            }
            next = location(get(afterLogin));
        }
        return next;
    }

    public HttpResponse<String> exchangeCode(String code, String redirectUri, String codeVerifier) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("client_id", CLIENT_ID);
        if (codeVerifier != null) {
            body.put("code_verifier", codeVerifier);
        }
        return postForm("/oauth2/token", body);
    }

    /** Full happy path for a user; returns the token response JSON. */
    public JsonNode obtainTokens(String email, String password, String redirectUri) {
        String verifier = newCodeVerifier();
        URI callback = authorizeAndLogin(authorizeParams(redirectUri, s256(verifier)), email, password);
        String code = queryParams(callback).get("code");
        if (code == null) {
            throw new IllegalStateException("No authorization code; redirected to " + callback.getPath()
                    + " with error=" + queryParams(callback).get("error"));
        }
        HttpResponse<String> token = exchangeCode(code, redirectUri, verifier);
        if (token.statusCode() != 200) {
            throw new IllegalStateException("Token request failed: HTTP " + token.statusCode());
        }
        return json(token.body());
    }

    // ---- HTTP helpers ----

    public HttpResponse<String> get(URI uri) {
        return send(HttpRequest.newBuilder(uri).GET().build());
    }

    public HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public HttpResponse<String> postForm(String path, Map<String, String> fields) {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
                .build());
    }

    public URI location(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new IllegalStateException("Expected a redirect but got HTTP " + response.statusCode()));
        return base.resolve(location);
    }

    public static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return params;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    public static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Response is not JSON", e);
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
