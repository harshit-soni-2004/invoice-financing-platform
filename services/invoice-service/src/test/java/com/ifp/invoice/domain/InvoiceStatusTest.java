package com.ifp.invoice.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.ifp.invoice.domain.InvoiceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceStatusTest {

    /** Mirrors the state diagram in docs/architecture.md. */
    private static final Map<InvoiceStatus, Set<InvoiceStatus>> EXPECTED = Map.of(
            DRAFT, EnumSet.of(SUBMITTED, CANCELLED),
            SUBMITTED, EnumSet.of(BUYER_CONFIRMED, REJECTED, CANCELLED),
            BUYER_CONFIRMED, EnumSet.of(VERIFIED, REJECTED),
            VERIFIED, EnumSet.of(FINANCING_AVAILABLE, CANCELLED),
            FINANCING_AVAILABLE, EnumSet.of(FINANCED),
            FINANCED, EnumSet.of(SETTLED),
            SETTLED, EnumSet.noneOf(InvoiceStatus.class),
            REJECTED, EnumSet.noneOf(InvoiceStatus.class),
            CANCELLED, EnumSet.noneOf(InvoiceStatus.class));

    static Stream<Arguments> everyPair() {
        return Arrays.stream(values()).flatMap(from -> Arrays.stream(values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void transitionMatrixMatchesDocumentedLifecycle(InvoiceStatus from, InvoiceStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(EXPECTED.get(from).contains(to));
    }

    @Test
    void noStatusCanTransitionToItselfOrBackToDraft() {
        for (InvoiceStatus status : values()) {
            assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
            assertThat(status.canTransitionTo(DRAFT)).as(status.name()).isFalse();
        }
    }

    @Test
    void terminalStatesAreSettledRejectedAndCancelled() {
        assertThat(Arrays.stream(values()).filter(InvoiceStatus::isTerminal))
                .containsExactlyInAnyOrder(SETTLED, REJECTED, CANCELLED);
    }
}
