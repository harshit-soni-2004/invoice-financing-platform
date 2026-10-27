package com.ifp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.auth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void livenessAndReadinessProbesAreUpWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void requestLogsAreJsonWithServiceNameAndCorrelationIdAndNoPersonalData(CapturedOutput output) throws Exception {
        String correlationId = "obs-test-" + System.nanoTime();
        String email = uniqueEmail("obs");
        mockMvc.perform(post("/api/auth/register").header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Obs User","role":"SELLER"}"""
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated());

        List<String> lines = output.getOut().lines().filter(line -> line.contains(correlationId)).toList();
        assertThat(lines).as("log lines carrying the correlation id").isNotEmpty();
        for (String line : lines) {
            JsonNode entry = json.readTree(line);
            assertThat(entry.at("/service/name").asText()).isEqualTo("auth-service");
            assertThat(entry.path("correlationId").asText()).isEqualTo(correlationId);
            assertThat(entry.path("message").asText()).isNotBlank();
        }
        assertThat(output.getOut()).doesNotContain(email).doesNotContain(PASSWORD);
    }
}
