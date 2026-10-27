package com.ifp.invoice.web;

import com.ifp.invoice.service.ExpectedVersion;
import com.ifp.invoice.service.PreconditionFailedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETagsTest {

    @Test
    void formatsStrongQuotedVersion() {
        assertThat(ETags.of(7)).isEqualTo("\"7\"");
    }

    @Test
    void absentOrWildcardImposesNoPrecondition() {
        assertThat(ETags.parseIfMatch(null)).isEqualTo(ExpectedVersion.ANY);
        assertThat(ETags.parseIfMatch(" * ")).isEqualTo(ExpectedVersion.ANY);
    }

    @Test
    void parsesStrongTag() {
        assertThat(ETags.parseIfMatch("\"12\"")).isEqualTo(ExpectedVersion.of(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"W/\"3\"", "3", "\"abc\"", "\"1\", \"2\"", "\"\"", "\"-1\"", "\"9999999999999999999\""})
    void weakListOrMalformedTagsNeverMatch(String header) {
        assertThatThrownBy(() -> ETags.parseIfMatch(header)).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void expectedVersionComparesExactly() {
        ExpectedVersion.of(3).check(3);
        ExpectedVersion.ANY.check(42);
        assertThatThrownBy(() -> ExpectedVersion.of(3).check(4)).isInstanceOf(PreconditionFailedException.class);
    }
}
