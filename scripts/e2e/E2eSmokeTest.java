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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * End-to-end smoke test against running auth-service, invoice-service and gateway. Started by scripts/e2e.ps1.
 * Uses the real login form (with its CSRF token) and Authorization Code + PKCE (S256); nothing is mocked.
 * Prints check names and HTTP status codes only: never tokens, passwords, or invoice amounts.
 *
 * Environment: E2E_GATEWAY_URL, E2E_AUTH_URL (the issuer), E2E_INVOICE_URL, E2E_PASSWORD.
 */
public class E2eSmokeTest {

    private static final String CLIENT_ID = "ifp-spa";
    private static final String REDIRECT_URI = "http://localhost:4200/auth/callback";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI gateway = URI.create(env("E2E_GATEWAY_URL"));
    private final URI auth = URI.create(env("E2E_AUTH_URL"));
    private final URI invoiceService = URI.create(env("E2E_INVOICE_URL"));
    private final String password = env("E2E_PASSWORD");
    private final HttpClient api = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> failures = new ArrayList<>();
    private int passed;

    public static void main(String[] args) {
        E2eSmokeTest test = new E2eSmokeTest();
        try {
            test.run();
        } catch (RuntimeException e) {
            test.failures.add("aborted: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("FAIL  aborted: " + e.getMessage());
        }
        System.out.printf("%nE2E result: %d passed, %d failed%n", test.passed, test.failures.size());
        System.exit(test.failures.isEmpty() ? 0 : 1);
    }

    private void run() {
        String runId = HexFormat.of().formatHex(randomBytes(4));
        String sellerA = "e2e-seller-a-" + runId + "@example.test";
        String sellerB = "e2e-seller-b-" + runId + "@example.test";
        String financier = "e2e-financier-" + runId + "@example.test";

        section("Registration through the gateway");
        check("seller A registers", register(sellerA, "SELLER") == 201);
        check("seller B registers", register(sellerB, "SELLER") == 201);
        check("financier registers (pending approval)", register(financier, "FINANCIER") == 201);
        check("ADMIN cannot self-register", register("e2e-admin-" + runId + "@example.test", "ADMIN") == 400);

        section("Authorization Code + PKCE through auth-service");
        URI noPkce = new Browser().authorize(authorizeParams(null), sellerA, password);
        check("authorization request without PKCE is refused",
                query(noPkce).get("code") == null && "invalid_request".equals(query(noPkce).get("error")));

        Browser wrongVerifierBrowser = new Browser();
        URI callback = wrongVerifierBrowser.authorize(authorizeParams(s256(newVerifier())), sellerA, password);
        int wrongVerifierStatus = query(callback).get("code") == null ? -1
                : wrongVerifierBrowser.exchange(query(callback).get("code"), newVerifier()).statusCode();
        check("code exchange with the wrong PKCE verifier is refused", wrongVerifierStatus == 400);

        String tokenA = new Browser().obtainAccessToken(sellerA, password);
        String tokenB = new Browser().obtainAccessToken(sellerB, password);
        check("seller A obtains an access token", tokenA != null);
        check("seller B obtains an access token", tokenB != null);

        URI financierLogin = new Browser().login(financier, password);
        check("pending financier cannot sign in (no code, no token)",
                financierLogin.getPath().equals("/login") && financierLogin.getQuery() != null
                        && financierLogin.getQuery().contains("error"));
        if (tokenA == null || tokenB == null) {
            throw new IllegalStateException("cannot continue without seller tokens");
        }

        section("Invoices through the gateway");
        String correlationId = "e2e-" + runId + "-create";
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String body = """
                {"invoiceNumber":"E2E-%s","buyerName":"E2E Buyer Pvt Ltd","description":"End-to-end smoke test",
                 "amount":"125000.00","issueDate":"%s","dueDate":"%s"}""".formatted(runId.toUpperCase(), today.minusDays(1), today.plusDays(60));
        HttpResponse<String> created = call(gateway, "POST", "/api/invoices", tokenA, body, correlationId);
        String invoiceId = jsonString(created.body(), "id");
        check("seller A creates an invoice (201, ETag, Location)", created.statusCode() == 201
                && created.headers().firstValue("ETag").isPresent() && created.headers().firstValue("Location").isPresent());
        check("correlation ID is propagated end to end",
                correlationId.equals(created.headers().firstValue("X-Correlation-Id").orElse(null)));
        if (invoiceId == null) {
            throw new IllegalStateException("invoice was not created (HTTP " + created.statusCode() + ")");
        }
        String invoicePath = "/api/invoices/" + invoiceId;

        HttpResponse<String> read = call(gateway, "GET", invoicePath, tokenA, null, null);
        check("seller A reads the invoice back", read.statusCode() == 200 && invoiceId.equals(jsonString(read.body(), "id")));
        check("seller B cannot read seller A's invoice (404)", call(gateway, "GET", invoicePath, tokenB, null, null).statusCode() == 404);

        section("Role checks");
        check("seller cannot call an admin invoice operation (403 from invoice-service)",
                call(gateway, "POST", invoicePath + "/verify", tokenA, null, null).statusCode() == 403);
        check("seller cannot call admin user APIs (403 at the gateway)",
                call(gateway, "GET", "/api/admin/users", tokenA, null, null).statusCode() == 403);
        check("seller reads own profile through the gateway",
                call(gateway, "GET", "/api/users/me", tokenA, null, null).statusCode() == 200);

        section("Invalid tokens are rejected by the gateway AND by invoice-service directly");
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("no token", null);
        invalid.put("garbage token", "not-a-jwt");
        invalid.put("tampered signature", tamperSignature(tokenA));
        invalid.put("unsigned (alg=none)", unsigned(tokenA));
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            check("gateway rejects " + entry.getKey(), call(gateway, "GET", invoicePath, entry.getValue(), null, null).statusCode() == 401);
            check("invoice-service rejects " + entry.getKey(),
                    call(invoiceService, "GET", invoicePath, entry.getValue(), null, null).statusCode() == 401);
        }
        check("invoice-service accepts the genuine token directly (validates on its own)",
                call(invoiceService, "GET", invoicePath, tokenA, null, null).statusCode() == 200);
    }

    // ---- Registration ----

    private int register(String email, String role) {
        String body = "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"E2E %s\",\"role\":\"%s\"}"
                .formatted(email, jsonEscape(password), role.toLowerCase(), role);
        for (int attempt = 0; ; attempt++) {
            int status = call(gateway, "POST", "/api/auth/register", null, body, null).statusCode();
            if (status != 429 || attempt == 6) {
                return status;
            }
            System.out.println("      registration rate limit reached; waiting 13 s");
            sleep(Duration.ofSeconds(13));
        }
    }

    // ---- Browser-like OAuth client ----

    private final class Browser {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        private String verifier;

        String obtainAccessToken(String email, String pwd) {
            verifier = newVerifier();
            URI callback = authorize(authorizeParams(s256(verifier)), email, pwd);
            String code = query(callback).get("code");
            if (code == null) {
                return null;
            }
            HttpResponse<String> token = exchange(code, verifier);
            return token.statusCode() == 200 ? jsonString(token.body(), "access_token") : null;
        }

        URI authorize(Map<String, String> params, String email, String pwd) {
            URI next = location(send(get(auth.resolve("/oauth2/authorize?" + form(params)))));
            if (next.getPath().equals("/login")) {
                URI afterLogin = login(email, pwd);
                if (!afterLogin.getPath().equals("/oauth2/authorize")) {
                    return afterLogin;
                }
                next = location(send(get(afterLogin)));
            }
            return next;
        }

        URI login(String email, String pwd) {
            HttpResponse<String> page = send(get(auth.resolve("/login")));
            Matcher csrf = CSRF.matcher(page.body());
            if (page.statusCode() != 200 || !csrf.find()) {
                throw new IllegalStateException("login page not served (HTTP " + page.statusCode() + ")");
            }
            return location(send(post(auth.resolve("/login"),
                    Map.of("username", email, "password", pwd, "_csrf", csrf.group(1)))));
        }

        HttpResponse<String> exchange(String code, String codeVerifier) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("grant_type", "authorization_code");
            fields.put("code", code);
            fields.put("redirect_uri", REDIRECT_URI);
            fields.put("client_id", CLIENT_ID);
            fields.put("code_verifier", codeVerifier);
            return send(post(auth.resolve("/oauth2/token"), fields));
        }

        private HttpResponse<String> send(HttpRequest request) {
            return E2eSmokeTest.send(http, request);
        }

        private URI location(HttpResponse<String> response) {
            return auth.resolve(response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("expected a redirect, got HTTP " + response.statusCode())));
        }
    }

    private static Map<String, String> authorizeParams(String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", CLIENT_ID);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("scope", "openid profile");
        params.put("state", HexFormat.of().formatHex(randomBytes(8)));
        if (codeChallenge != null) {
            params.put("code_challenge", codeChallenge);
            params.put("code_challenge_method", "S256");
        }
        return params;
    }

    // ---- API calls ----

    private HttpResponse<String> call(URI base, String method, String path, String token, String json, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        return send(api, request.build());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("request to " + request.uri().getPath() + " failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
    }

    private static HttpRequest post(URI uri, Map<String, String> fields) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
    }

    // ---- Token manipulation for negative tests ----

    private static String tamperSignature(String jwt) {
        int signatureStart = jwt.lastIndexOf('.') + 1;
        int i = signatureStart + (jwt.length() - signatureStart) / 2;
        char replacement = jwt.charAt(i) == 'A' ? 'B' : 'A';
        return jwt.substring(0, i) + replacement + jwt.substring(i + 1);
    }

    private static String unsigned(String jwt) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + jwt.split("\\.")[1] + ".";
    }

    // ---- Helpers ----

    private void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failures.add(name);
            System.out.println("FAIL  " + name);
        }
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                params.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                        eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String newVerifier() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
