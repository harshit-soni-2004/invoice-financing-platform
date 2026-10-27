package com.ifp.invoice.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.invoice.support.AbstractIntegrationTest;
import com.ifp.invoice.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvoiceApiIT extends AbstractIntegrationTest {

    private final UUID sellerId = UUID.randomUUID();
    private final String seller = TestJwtSupport.seller(sellerId);
    private final UUID adminId = UUID.randomUUID();
    private final String admin = TestJwtSupport.admin(adminId);

    @Test
    void sellerAndAdminWalkInvoiceToFinancingAvailable() throws Exception {
        String number = uniqueNumber();
        MvcResult created = mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody(number))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.amount").value(500000.00))
                .andExpect(jsonPath("$.invoiceNumber").value(number))
                .andReturn();
        String id = body(created).get("id").asText();
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/api/invoices/" + id);

        mockMvc.perform(withToken(get("/api/invoices/{id}", id), seller))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"0\""));

        transition(seller, id, "submit", "\"0\"", "SUBMITTED", "\"1\"");
        transition(admin, id, "buyer-confirmation", "\"1\"", "BUYER_CONFIRMED", "\"2\"");
        transition(admin, id, "verify", "\"2\"", "VERIFIED", "\"3\"");
        transition(seller, id, "list-for-financing", null, "FINANCING_AVAILABLE", "\"4\"");

        mockMvc.perform(withToken(get("/api/invoices/{id}/history", id), seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].fromStatus").isEmpty())
                .andExpect(jsonPath("$[0].toStatus").value("DRAFT"))
                .andExpect(jsonPath("$[2].actorRole").value("ADMIN"))
                .andExpect(jsonPath("$[2].actorId").isEmpty())
                .andExpect(jsonPath("$[4].toStatus").value("FINANCING_AVAILABLE"));
        mockMvc.perform(withToken(get("/api/invoices/{id}/history", id), admin))
                .andExpect(jsonPath("$[2].actorId").value(adminId.toString()));
    }

    @Test
    void clientSuppliedSellerIdAndCurrencyAreIgnored() throws Exception {
        UUID victim = UUID.randomUUID();
        Map<String, Object> body = invoiceBody(uniqueNumber());
        body.put("sellerId", victim.toString());
        body.put("currency", "USD");
        body.put("status", "VERIFIED");

        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void invalidFieldsReturnProblemWithFieldErrors() throws Exception {
        Map<String, Object> body = invoiceBody("BAD NUMBER");
        body.put("amount", "12.345");
        body.put("buyerGstin", "NOT-A-GSTIN");
        body.remove("dueDate");

        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("amount", "buyerGstin", "dueDate", "invoiceNumber")));
    }

    @Test
    void malformedJsonAndBadPathParametersReturn400() throws Exception {
        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/invoices/not-a-uuid"), seller))
                .andExpect(status().isBadRequest());
    }

    @Test
    void businessRuleViolationsReturn422WithCode() throws Exception {
        Map<String, Object> futureIssue = invoiceBody(uniqueNumber());
        futureIssue.put("issueDate", TODAY.plusDays(1).toString());
        expectRule(futureIssue, "ISSUE_DATE_IN_FUTURE");

        Map<String, Object> dueBeforeIssue = invoiceBody(uniqueNumber());
        dueBeforeIssue.put("dueDate", TODAY.minusDays(2).toString());
        expectRule(dueBeforeIssue, "DUE_DATE_NOT_AFTER_ISSUE_DATE");

        Map<String, Object> tooLarge = invoiceBody(uniqueNumber());
        tooLarge.put("amount", "100000000.01");
        expectRule(tooLarge, "AMOUNT_TOO_LARGE");
    }

    @Test
    void submissionRequiresMinimumTimeToMaturity() throws Exception {
        Map<String, Object> body = invoiceBody(uniqueNumber());
        body.put("dueDate", TODAY.plusDays(3).toString());
        String id = body(mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();

        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", id), seller))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUE_DATE_TOO_SOON"));
        assertThat(getInvoice(UUID.fromString(id), seller).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void duplicateInvoiceNumberPerSellerIsRejectedCaseInsensitively() throws Exception {
        String number = uniqueNumber();
        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody(number))))
                .andExpect(status().isCreated());

        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody(number.toLowerCase()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INVOICE_NUMBER"));

        mockMvc.perform(withToken(post("/api/invoices"), TestJwtSupport.seller(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody(number))))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidTransitionsReturn409WithStatuses() throws Exception {
        UUID id = createInvoice(sellerId);

        mockMvc.perform(withToken(post("/api/invoices/{id}/verify", id), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("DRAFT"))
                .andExpect(jsonPath("$.requestedStatus").value("VERIFIED"));

        mockMvc.perform(withToken(post("/api/invoices/{id}/cancel", id), seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Order withdrawn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusReason").value("Order withdrawn"));
        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", id), seller))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
    }

    @Test
    void rejectRequiresReason() throws Exception {
        UUID id = createInvoice(sellerId);
        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", id), seller)).andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/invoices/{id}/reject", id), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mockMvc.perform(withToken(post("/api/invoices/{id}/reject", id), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Buyer denies the debt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Buyer denies the debt"));
    }

    @Test
    void updateRequiresMatchingIfMatchAndOnlyWorksOnDrafts() throws Exception {
        UUID id = createInvoice(sellerId);
        Map<String, Object> changes = invoiceBody(uniqueNumber());
        changes.put("amount", "480000.50");

        mockMvc.perform(withToken(put("/api/invoices/{id}", id), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(changes)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        mockMvc.perform(withToken(put("/api/invoices/{id}", id), seller).header(HttpHeaders.IF_MATCH, "\"5\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(changes)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.currentETag").value("\"0\""));
        mockMvc.perform(withToken(put("/api/invoices/{id}", id), seller).header(HttpHeaders.IF_MATCH, "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(changes)))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(withToken(put("/api/invoices/{id}", id), seller).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(changes)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.amount").value(480000.50))
                .andExpect(jsonPath("$.invoiceNumber").value(changes.get("invoiceNumber")));

        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", id), seller).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(put("/api/invoices/{id}", id), seller).header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(changes)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_EDITABLE"));
    }

    @Test
    void staleIfMatchOnTransitionLeavesInvoiceUnchanged() throws Exception {
        UUID id = createInvoice(sellerId);

        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", id), seller).header(HttpHeaders.IF_MATCH, "\"7\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));

        JsonNode invoice = getInvoice(id, seller);
        assertThat(invoice.get("status").asText()).isEqualTo("DRAFT");
        assertThat(invoice.get("version").asLong()).isZero();
    }

    @Test
    void listIsPaginatedSortedFilteredAndScopedToCaller() throws Exception {
        UUID owner = UUID.randomUUID();
        String token = TestJwtSupport.seller(owner);
        for (String amount : new String[]{"300.00", "100.00", "200.00"}) {
            Map<String, Object> body = invoiceBody(uniqueNumber());
            body.put("amount", amount);
            mockMvc.perform(withToken(post("/api/invoices"), token)
                    .contentType(MediaType.APPLICATION_JSON).content(json(body))).andExpect(status().isCreated());
        }
        UUID someoneElse = UUID.randomUUID();
        createInvoice(someoneElse);

        mockMvc.perform(withToken(get("/api/invoices").param("size", "2").param("sort", "amount,asc"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].amount").value(100.00))
                .andExpect(jsonPath("$.content[1].amount").value(200.00));

        // A seller cannot widen the scope by naming another seller.
        mockMvc.perform(withToken(get("/api/invoices").param("sellerId", someoneElse.toString()), token))
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(withToken(get("/api/invoices").param("status", "SUBMITTED"), token))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(withToken(get("/api/invoices").param("size", "101"), token)).andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/invoices").param("page", "-1"), token)).andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/invoices").param("sort", "sellerId"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"));
        mockMvc.perform(withToken(get("/api/invoices").param("status", "PAID"), token)).andExpect(status().isBadRequest());
    }

    private void transition(String token, String id, String action, String ifMatch, String expectedStatus,
                            String expectedETag) throws Exception {
        var request = withToken(post("/api/invoices/{id}/" + action, id), token);
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(header().string(HttpHeaders.ETAG, expectedETag));
    }

    private void expectRule(Map<String, Object> body, String code) throws Exception {
        mockMvc.perform(withToken(post("/api/invoices"), seller)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code));
    }
}
