package com.ifp.invoice.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared configuration for all integration tests, so they reuse one cached Spring context and one MySQL container.
 * Tests isolate their data by using fresh random seller IDs instead of truncating tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtSupport::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtSupport.AUDIENCE);
    }

    protected static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected static Map<String, Object> invoiceBody(String number) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceNumber", number);
        body.put("buyerName", "Acme Buyers Pvt Ltd");
        body.put("buyerGstin", "27AAPFU0939F1ZV");
        body.put("description", "Steel coils");
        body.put("amount", "500000.00");
        body.put("issueDate", TODAY.minusDays(1).toString());
        body.put("dueDate", TODAY.plusDays(60).toString());
        return body;
    }

    protected static String uniqueNumber() {
        return "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Creates a DRAFT invoice through the API and returns its id. */
    protected UUID createInvoice(UUID sellerId) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/invoices"), TestJwtSupport.seller(sellerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invoiceBody(uniqueNumber()))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    protected JsonNode getInvoice(UUID id, String token) throws Exception {
        return body(mockMvc.perform(withToken(get("/api/invoices/{id}", id), token))
                .andExpect(status().isOk())
                .andReturn());
    }
}
