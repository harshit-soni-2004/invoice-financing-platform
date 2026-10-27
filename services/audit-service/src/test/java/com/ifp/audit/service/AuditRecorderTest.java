package com.ifp.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecorderTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void contactDetailsAreRemovedAtAnyDepthAndTheOriginalIsUntouched() throws Exception {
        JsonNode original = mapper.readTree("""
                {"userId":"u1","email":"a@example.test","displayName":"Asha","role":"SELLER",
                 "nested":{"email":"b@example.test","keep":1},"list":[{"displayName":"X","id":2}]}""");

        JsonNode sanitized = AuditRecorder.sanitize(original);

        assertThat(sanitized.toString()).doesNotContain("example.test").doesNotContain("Asha").doesNotContain("\"X\"");
        assertThat(sanitized.path("userId").asText()).isEqualTo("u1");
        assertThat(sanitized.path("nested").path("keep").asInt()).isEqualTo(1);
        assertThat(sanitized.path("list").get(0).path("id").asInt()).isEqualTo(2);
        assertThat(original.path("email").asText()).isEqualTo("a@example.test");
    }
}
