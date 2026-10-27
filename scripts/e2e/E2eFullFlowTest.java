import java.io.IOException;
import java.math.BigDecimal;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Full backend flow through the gateway against running services on disposable infrastructure (scripts/e2e-full.ps1):
 * registration, financier activation, invoice submission and verification, listing, offer, acceptance, approval,
 * simulated disbursement (with an idempotent replay), two repayments and settlement. Then checks that events moved
 * the invoice and the read model, that the expected emails reached MailHog exactly once, that the audit trail holds
 * every event without personal data, and that no message was dead-lettered or left unrouted.
 * Prints check names and HTTP status codes only: never tokens, passwords or amounts.
 *
 * Environment: E2E_GATEWAY_URL, E2E_AUTH_URL, E2E_PASSWORD, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, E2E_MAILHOG_URL,
 * E2E_RABBIT_ADMIN_URL, E2E_RABBIT_USER, E2E_RABBIT_PASSWORD.
 */
public class E2eFullFlowTest {

    private static final String CLIENT_ID = "ifp-spa";
    private static final String REDIRECT_URI = "http://localhost:4200/auth/callback";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration EVENTUAL = Duration.ofSeconds(45);
    private static final List<String> QUEUES = List.of("financing.invoice-events", "invoice.financing-events",
            "notification.events", "audit.events");

    private final URI gateway = URI.create(env("E2E_GATEWAY_URL"));
    private final URI auth = URI.create(env("E2E_AUTH_URL"));
    private final URI mailhog = URI.create(env("E2E_MAILHOG_URL"));
    private final URI rabbitAdmin = URI.create(env("E2E_RABBIT_ADMIN_URL"));
    private final String rabbitAuth = "Basic " + Base64.getEncoder().encodeToString(
            (env("E2E_RABBIT_USER") + ":" + env("E2E_RABBIT_PASSWORD")).getBytes(StandardCharsets.UTF_8));
    private final String password = env("E2E_PASSWORD");
    private final HttpClient api = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> failures = new ArrayList<>();
    private int passed;

    public static void main(String[] args) {
        E2eFullFlowTest test = new E2eFullFlowTest();
        try {
            test.run();
        } catch (RuntimeException e) {
            test.failures.add("aborted: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("FAIL  aborted: " + e.getMessage());
        }
        System.out.printf("%nE2E full flow: %d passed, %d failed%n", test.passed, test.failures.size());
        System.exit(test.failures.isEmpty() ? 0 : 1);
    }

    private void run() {
        String runId = HexFormat.of().formatHex(randomBytes(4));
        String sellerEmail = "e2e-seller-" + runId + "@example.test";
        String financierEmail = "e2e-financier-" + runId + "@example.test";
        String invoiceNumber = "E2E-" + runId.toUpperCase();

        section("Users");
        HttpResponse<String> sellerRegistration = register(sellerEmail, "SELLER");
        HttpResponse<String> financierRegistration = register(financierEmail, "FINANCIER");
        require("seller and financier register", sellerRegistration.statusCode() == 201 && financierRegistration.statusCode() == 201);
        String sellerId = jsonString(sellerRegistration.body(), "id");
        String financierId = jsonString(financierRegistration.body(), "id");
        String admin = new Browser().obtainAccessToken(env("E2E_ADMIN_EMAIL"), env("E2E_ADMIN_PASSWORD"));
        require("bootstrap admin signs in", admin != null);
        require("admin activates the financier",
                call("POST", "/api/admin/users/" + financierId + "/activate", admin, null, null).statusCode() == 200);
        String seller = new Browser().obtainAccessToken(sellerEmail, password);
        String financier = new Browser().obtainAccessToken(financierEmail, password);
        require("seller and activated financier sign in", seller != null && financier != null);

        section("Invoice lifecycle (invoice-service)");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        HttpResponse<String> created = call("POST", "/api/invoices", seller, """
                {"invoiceNumber":"%s","buyerName":"E2E Buyer Pvt Ltd","description":"Full-flow test",
                 "amount":"125000.00","issueDate":"%s","dueDate":"%s"}""".formatted(invoiceNumber, today.minusDays(1), today.plusDays(60)), null);
        String invoiceId = jsonString(created.body(), "id");
        require("seller creates an invoice", created.statusCode() == 201 && invoiceId != null);
        String invoicePath = "/api/invoices/" + invoiceId;
        String submitCorrelation = "e2e-" + runId + "-submit";
        check("seller submits", call("POST", invoicePath + "/submit", seller, null, submitCorrelation).statusCode() == 200);
        check("admin confirms the buyer", call("POST", invoicePath + "/buyer-confirmation", admin, null, null).statusCode() == 200);
        check("admin verifies", call("POST", invoicePath + "/verify", admin, null, null).statusCode() == 200);
        check("financier cannot see the invoice before it is listed",
                call("GET", "/api/financing/opportunities/" + invoiceId, financier, null, null).statusCode() == 404);
        check("seller lists it for financing", call("POST", invoicePath + "/list-for-financing", seller, null, null).statusCode() == 200);

        section("Invoice events reach financing-service");
        check("listed invoice appears as an opportunity", await(() ->
                call("GET", "/api/financing/opportunities/" + invoiceId, financier, null, null).statusCode() == 200));

        section("Offer, agreement, disbursement (financing-service)");
        HttpResponse<String> offer = call("POST", "/api/financing/offers", financier, """
                {"invoiceId":"%s","advanceAmount":"100000.00","annualRate":"0.1200","processingFee":"500.00",
                 "installmentCount":1,"expiresAt":"%s"}""".formatted(invoiceId, Instant.now().plus(Duration.ofDays(2))), null);
        String offerId = jsonString(offer.body(), "id");
        require("financier makes an offer", offer.statusCode() == 201 && offerId != null);
        HttpResponse<String> accepted = call("POST", "/api/financing/offers/" + offerId + "/accept", seller, null, null);
        String agreementId = jsonString(accepted.body(), "id");
        require("seller accepts (agreement created)", accepted.statusCode() == 201 && agreementId != null);
        String agreementPath = "/api/financing/agreements/" + agreementId;
        check("financier approves", call("POST", agreementPath + "/approve", financier, null, null).statusCode() == 200);
        String disburseKey = "e2e-disburse-" + runId;
        check("financier disburses (simulated)", callWithKey("POST", agreementPath + "/disburse", financier, null, disburseKey).statusCode() == 201);
        check("disbursement replay with the same key is idempotent",
                callWithKey("POST", agreementPath + "/disburse", financier, null, disburseKey).statusCode() == 200);

        section("Financing events reach invoice-service");
        check("invoice becomes FINANCED", await(() -> "FINANCED".equals(invoiceStatus(seller, invoicePath))));

        section("Repayment and settlement");
        BigDecimal total = new BigDecimal(jsonNumber(call("GET", agreementPath, seller, null, null).body(), "totalRepayable"));
        BigDecimal first = new BigDecimal("10000.00");
        check("partial repayment recorded", callWithKey("POST", agreementPath + "/repayments", seller,
                "{\"amount\":\"" + first.toPlainString() + "\"}", "e2e-repay-1-" + runId).statusCode() == 201);
        check("final repayment recorded", callWithKey("POST", agreementPath + "/repayments", seller,
                "{\"amount\":\"" + total.subtract(first).toPlainString() + "\"}", "e2e-repay-2-" + runId).statusCode() == 201);
        check("agreement is SETTLED", "SETTLED".equals(jsonString(call("GET", agreementPath, seller, null, null).body(), "status")));
        check("invoice becomes SETTLED", await(() -> "SETTLED".equals(invoiceStatus(seller, invoicePath))));

        section("Notifications (notification-service -> MailHog)");
        List<String> sellerSubjects = List.of("Welcome to the Invoice Financing Platform", "Invoice " + invoiceNumber + " submitted",
                "Invoice " + invoiceNumber + " verified", "New financing offer", "Financing approved",
                "Funds disbursed (simulated)", "Invoice " + invoiceNumber + " financed", "Financing settled",
                "Invoice " + invoiceNumber + " settled");
        List<String> financierSubjects = List.of("Welcome to the Invoice Financing Platform", "Offer accepted",
                "Funds disbursed (simulated)", "Repayment recorded", "Financing settled");
        check("seller receives 9 emails", await(() -> mailCount(sellerEmail) == 9));
        check("financier receives 6 emails (two repayments)", await(() -> mailCount(financierEmail) == 6));
        sleep(Duration.ofSeconds(3));
        String sellerMail = mail(sellerEmail);
        String financierMail = mail(financierEmail);
        check("no duplicate emails after replays and retries", mailCount(sellerEmail) == 9 && mailCount(financierEmail) == 6);
        check("seller emails cover submission through settlement", sellerSubjects.stream().allMatch(sellerMail::contains));
        check("financier emails cover acceptance through settlement", financierSubjects.stream().allMatch(financierMail::contains));
        check("emails contain no amounts", !(sellerMail + financierMail).matches("(?s).*(125000|100000|10000\\.00).*"));
        HttpResponse<String> ownNotifications = call("GET", "/api/notifications?size=50", seller, null, null);
        check("seller sees own 9 notifications through the gateway, all SENT", ownNotifications.statusCode() == 200
                && "9".equals(jsonNumber(ownNotifications.body(), "totalElements")) && !ownNotifications.body().contains("SKIPPED"));

        section("Audit trail (audit-service)");
        check("invoice has 7 audit events", await(() -> auditTotal(admin, "aggregateId=" + invoiceId) == 7));
        check("offer has 2 audit events", await(() -> auditTotal(admin, "aggregateId=" + offerId) == 2));
        check("agreement has 6 audit events", await(() -> auditTotal(admin, "aggregateId=" + agreementId) == 6));
        String invoiceTrail = call("GET", "/api/audit-events?size=50&aggregateId=" + invoiceId, admin, null, null).body();
        check("invoice trail lists every lifecycle event", List.of("InvoiceCreated", "InvoiceSubmitted", "InvoiceBuyerConfirmed",
                "InvoiceVerified", "InvoiceListedForFinancing", "InvoiceFinanced", "InvoiceSettled").stream()
                .allMatch(type -> invoiceTrail.contains("\"" + type + "\"")));
        String agreementTrail = call("GET", "/api/audit-events?size=50&aggregateId=" + agreementId, admin, null, null).body();
        check("agreement trail lists approval, disbursement, repayments and settlement", List.of("FinancingAgreementCreated",
                "FinancingApproved", "FinancingDisbursed", "FinancingRepaymentRecorded", "FinancingSettled").stream()
                .allMatch(type -> agreementTrail.contains("\"" + type + "\"")));
        check("correlation ID of the submit request is kept in the audit trail", call("GET",
                "/api/audit-events?eventType=InvoiceSubmitted&aggregateId=" + invoiceId, admin, null, null).body().contains(submitCorrelation));
        HttpResponse<String> registration = call("GET", "/api/audit-events?eventType=UserRegistered&aggregateId=" + sellerId, admin, null, null);
        check("UserRegistered is audited without the email address", "1".equals(jsonNumber(registration.body(), "totalElements"))
                && !registration.body().contains(sellerEmail));
        check("seller cannot read the audit trail (403)", call("GET", "/api/audit-events", seller, null, null).statusCode() == 403);

        section("Broker state (RabbitMQ management API)");
        sleep(Duration.ofSeconds(6));
        for (String queue : QUEUES) {
            check(queue + ".dlq is empty", queueDepth(queue + ".dlq") == 0);
        }
        check("no event was unroutable", queueDepth("ifp.events.unrouted") == 0);
    }

    // ---- Domain helpers ----

    private HttpResponse<String> register(String email, String role) {
        String body = "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"E2E %s\",\"role\":\"%s\"}"
                .formatted(email, jsonEscape(password), role.toLowerCase(), role);
        return call("POST", "/api/auth/register", null, body, null);
    }

    private String invoiceStatus(String token, String path) {
        return jsonString(call("GET", path, token, null, null).body(), "status");
    }

    private int mailCount(String email) {
        String total = jsonNumber(mail(email), "total");
        return total == null ? -1 : Integer.parseInt(total);
    }

    private String mail(String email) {
        URI uri = mailhog.resolve("/api/v2/search?kind=to&limit=50&query=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
        return send(api, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()).body();
    }

    private long auditTotal(String adminToken, String query) {
        String total = jsonNumber(call("GET", "/api/audit-events?size=1&" + query, adminToken, null, null).body(), "totalElements");
        return total == null ? -1 : Long.parseLong(total);
    }

    private long queueDepth(String queue) {
        URI uri = URI.create(rabbitAdmin + "/api/queues/%2F/" + URLEncoder.encode(queue, StandardCharsets.UTF_8));
        HttpResponse<String> response = send(api, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", rabbitAuth).GET().build());
        String messages = response.statusCode() == 200 ? jsonNumber(response.body(), "messages") : null;
        return messages == null ? -1 : Long.parseLong(messages);
    }

    // ---- Browser-like OAuth client (Authorization Code + PKCE through the real login form) ----

    private final class Browser {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

        String obtainAccessToken(String email, String pwd) {
            String verifier = newVerifier();
            URI next = location(send(http, get(auth.resolve("/oauth2/authorize?" + form(authorizeParams(s256(verifier)))))));
            if (next.getPath().equals("/login")) {
                HttpResponse<String> page = send(http, get(auth.resolve("/login")));
                Matcher csrf = CSRF.matcher(page.body());
                if (page.statusCode() != 200 || !csrf.find()) {
                    throw new IllegalStateException("login page not served (HTTP " + page.statusCode() + ")");
                }
                URI afterLogin = location(send(http, post(auth.resolve("/login"),
                        Map.of("username", email, "password", pwd, "_csrf", csrf.group(1)))));
                if (!afterLogin.getPath().equals("/oauth2/authorize")) {
                    return null;
                }
                next = location(send(http, get(afterLogin)));
            }
            String code = query(next).get("code");
            if (code == null) {
                return null;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("grant_type", "authorization_code");
            fields.put("code", code);
            fields.put("redirect_uri", REDIRECT_URI);
            fields.put("client_id", CLIENT_ID);
            fields.put("code_verifier", verifier);
            HttpResponse<String> token = send(http, post(auth.resolve("/oauth2/token"), fields));
            return token.statusCode() == 200 ? jsonString(token.body(), "access_token") : null;
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
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        return params;
    }

    // ---- HTTP ----

    private HttpResponse<String> call(String method, String path, String token, String json, String correlationId) {
        return call(method, path, token, json, correlationId, null);
    }

    private HttpResponse<String> callWithKey(String method, String path, String token, String json, String idempotencyKey) {
        return call(method, path, token, json, null, idempotencyKey);
    }

    private HttpResponse<String> call(String method, String path, String token, String json, String correlationId,
                                      String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(gateway.resolve(path)).timeout(Duration.ofSeconds(15))
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
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
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

    // ---- Checks ----

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

    /** A failed precondition makes every later step meaningless, so the run stops. */
    private void require(String name, boolean ok) {
        check(name, ok);
        if (!ok) {
            throw new IllegalStateException("cannot continue after: " + name);
        }
    }

    private static boolean await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(EVENTUAL);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(500));
        }
        return condition.getAsBoolean();
    }

    // ---- Small utilities ----

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String jsonNumber(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json == null ? "" : json);
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
