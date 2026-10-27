package com.ifp.financing.service;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.repository.DisbursementRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.IdempotencyRecordRepository;
import com.ifp.financing.repository.RepaymentInstallmentRepository;
import com.ifp.financing.repository.RepaymentRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A failure late in a money operation must leave no partial state behind, and the same key must be usable again. */
class TransactionRollbackIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FinancingAgreementRepository agreements;
    @Autowired
    private DisbursementRepository disbursements;
    @Autowired
    private RepaymentInstallmentRepository installments;
    @Autowired
    private RepaymentRepository repayments;
    @Autowired
    private IdempotencyRecordRepository idempotency;

    @Test
    void disbursementFailingAtScheduleCreationRollsBackEverything() throws Exception {
        Deal deal = approvedDeal();
        String key = newKey();
        // A conflicting installment row makes the last write of the disbursement (the schedule) fail.
        UUID planted = UUID.randomUUID();
        jdbc.update("""
                        insert into repayment_installments (id, agreement_id, sequence_no, due_date, principal_due, fee_due)
                        values (?, ?, 1, ?, 1.00, 0.00)""",
                bytes(planted), bytes(deal.agreementId()), today().plusDays(10));

        MvcResult failed = disburse(deal.financierId(), deal.agreementId(), key);

        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        var agreement = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.APPROVED);
        assertThat(agreement.getFeeAmount()).isNull();
        assertThat(agreement.getDisbursedAt()).isNull();
        assertThat(disbursements.findByAgreementId(deal.agreementId())).isEmpty();
        assertThat(idempotency.findByOperationAndActorIdAndIdempotencyKey("DISBURSE", deal.financierId(), key)).isEmpty();

        jdbc.update("delete from repayment_installments where id = ?", (Object) bytes(planted));
        MvcResult retried = disburse(deal.financierId(), deal.agreementId(), key);

        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(retried.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        assertThat(installments.findByAgreementIdOrderBySequenceNoAsc(deal.agreementId())).hasSize(2);
    }

    @Test
    void rejectedRepaymentChangesNothing() throws Exception {
        Deal deal = disbursedDeal();
        String key = newKey();

        MvcResult rejected = repay(deal.sellerId(), deal.agreementId(), "490468.50", key);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
        var agreement = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.DISBURSED);
        assertThat(agreement.outstanding()).isEqualByComparingTo("490468.49");
        assertThat(repayments.countByAgreementId(deal.agreementId())).isZero();
        assertThat(installments.findByAgreementIdOrderBySequenceNoAsc(deal.agreementId()))
                .allSatisfy(i -> assertThat(i.getFeePaid()).isEqualByComparingTo("0"));
        assertThat(idempotency.findByOperationAndActorIdAndIdempotencyKey("REPAYMENT", deal.sellerId(), key)).isEmpty();
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
