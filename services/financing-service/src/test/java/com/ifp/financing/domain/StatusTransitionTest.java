package com.ifp.financing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTransitionTest {

    @Test
    void agreementTransitionsMatchDocumentedStateMachine() {
        assertThat(AgreementStatus.PENDING_APPROVAL.allowedTargets())
                .containsExactlyInAnyOrder(AgreementStatus.APPROVED, AgreementStatus.REJECTED, AgreementStatus.CANCELLED);
        assertThat(AgreementStatus.APPROVED.allowedTargets())
                .containsExactlyInAnyOrder(AgreementStatus.DISBURSED, AgreementStatus.CANCELLED);
        assertThat(AgreementStatus.DISBURSED.allowedTargets())
                .containsExactlyInAnyOrder(AgreementStatus.REPAYMENT_IN_PROGRESS, AgreementStatus.SETTLED);
        assertThat(AgreementStatus.REPAYMENT_IN_PROGRESS.allowedTargets()).containsExactly(AgreementStatus.SETTLED);
        assertThat(AgreementStatus.REJECTED.allowedTargets()).isEmpty();
        assertThat(AgreementStatus.CANCELLED.allowedTargets()).isEmpty();
        assertThat(AgreementStatus.SETTLED.allowedTargets()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AgreementStatus.class)
    void onlyApprovedAgreementsCanBeDisbursed(AgreementStatus status) {
        assertThat(status.canTransitionTo(AgreementStatus.DISBURSED)).isEqualTo(status == AgreementStatus.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(AgreementStatus.class)
    void onlyDisbursedAgreementsAcceptRepayments(AgreementStatus status) {
        Set<AgreementStatus> accepting = EnumSet.of(AgreementStatus.DISBURSED, AgreementStatus.REPAYMENT_IN_PROGRESS);
        assertThat(status.acceptsRepayments()).isEqualTo(accepting.contains(status));
    }

    @Test
    void liveStatusesMatchTheUniqueConstraint() {
        assertThat(EnumSet.allOf(AgreementStatus.class).stream().filter(AgreementStatus::isLive))
                .containsExactlyInAnyOrder(AgreementStatus.PENDING_APPROVAL, AgreementStatus.APPROVED,
                        AgreementStatus.DISBURSED, AgreementStatus.REPAYMENT_IN_PROGRESS, AgreementStatus.SETTLED);
    }

    @ParameterizedTest
    @EnumSource(OfferStatus.class)
    void offersOnlyLeaveTheOpenState(OfferStatus from) {
        for (OfferStatus to : OfferStatus.values()) {
            boolean expected = from == OfferStatus.OFFERED && to != OfferStatus.OFFERED;
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
        }
    }
}
