package com.ifp.financing.persistence;

import com.ifp.financing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The database itself enforces the invariants the services rely on, even if application checks were bypassed. */
class SchemaConstraintsIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheSchema() {
        assertThat(jdbc.queryForObject("select max(version) from flyway_schema_history where success = 1", String.class))
                .isEqualTo("601");
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()", String.class);
        assertThat(tables).contains("financeable_invoices", "financing_offers", "financing_agreements", "disbursements",
                "repayment_installments", "repayments", "repayment_allocations", "idempotency_records",
                "financing_status_history", "outbox_events", "processed_events", "repayment_reminders",
                "penalty_accruals");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'processed_events' and index_name = 'ix_processed_events_processed_at'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void secondOpenOfferFromSameFinancierIsRejectedButClosedOffersAreNot() {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID financier = UUID.randomUUID();
        insertOffer(UUID.randomUUID(), invoice, financier, "WITHDRAWN", "1000.00");
        insertOffer(UUID.randomUUID(), invoice, financier, "OFFERED", "1000.00");

        assertThatThrownBy(() -> insertOffer(UUID.randomUUID(), invoice, financier, "OFFERED", "1000.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_financing_offers_open_per_financier");
        insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "OFFERED", "1000.00");
    }

    @Test
    void invoiceCannotHaveTwoLiveAgreements() {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offerA = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        UUID offerB = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        UUID offerC = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        insertAgreement(UUID.randomUUID(), offerA, invoice, seller, "CANCELLED");
        insertAgreement(UUID.randomUUID(), offerB, invoice, seller, "PENDING_APPROVAL");

        assertThatThrownBy(() -> insertAgreement(UUID.randomUUID(), offerC, invoice, seller, "APPROVED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_financing_agreements_live_invoice");
    }

    @Test
    void agreementCanHaveOnlyOneDisbursement() {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        UUID agreement = insertAgreement(UUID.randomUUID(), offer, invoice, seller, "DISBURSED");
        insertDisbursement(agreement, "SIM-0000000000000001");

        assertThatThrownBy(() -> insertDisbursement(agreement, "SIM-0000000000000002"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_disbursements_agreement");
    }

    @Test
    void checkConstraintsRejectInvalidMoney() {
        UUID invoice = listedInvoice(UUID.randomUUID());
        assertThatThrownBy(() -> insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "OFFERED", "-1.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_financing_offers_advance");
        assertThatThrownBy(() -> insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "BOGUS", "1.00"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_financing_offers_status");
    }

    @Test
    void repaymentComponentsMustAddUp() {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        UUID agreement = insertAgreement(UUID.randomUUID(), offer, invoice, seller, "DISBURSED");

        assertThatThrownBy(() -> jdbc.update("""
                        insert into repayments (id, agreement_id, amount, principal_component, fee_component, currency,
                                                recorded_by, received_at)
                        values (?, ?, 100.00, 50.00, 10.00, 'INR', ?, ?)""",
                bytes(UUID.randomUUID()), bytes(agreement), bytes(seller), now()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_repayments_amount");
        jdbc.update("""
                        insert into repayments (id, agreement_id, amount, principal_component, fee_component, penalty_component,
                                                currency, recorded_by, received_at)
                        values (?, ?, 100.00, 50.00, 10.00, 40.00, 'INR', ?, ?)""",
                bytes(UUID.randomUUID()), bytes(agreement), bytes(seller), now());
    }

    @Test
    void penaltyCannotBeRepaidBeyondWhatAccruedOrChargedTwiceForADay() {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = insertOffer(UUID.randomUUID(), invoice, UUID.randomUUID(), "ACCEPTED", "1000.00");
        UUID agreement = insertAgreement(UUID.randomUUID(), offer, invoice, seller, "DISBURSED");
        UUID installment = UUID.randomUUID();
        jdbc.update("""
                        insert into repayment_installments (id, agreement_id, sequence_no, due_date, principal_due, fee_due)
                        values (?, ?, 1, ?, 1000.00, 10.00)""", bytes(installment), bytes(agreement), today().minusDays(5));

        assertThatThrownBy(() -> jdbc.update("update repayment_installments set penalty_paid = 1.00 where id = ?", (Object) bytes(installment)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_repayment_installments_penalty");
        assertThatThrownBy(() -> jdbc.update("update financing_agreements set penalty_repaid = 1.00 where id = ?", (Object) bytes(agreement)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_financing_agreements_penalty");

        String accrual = """
                insert into penalty_accruals (installment_id, accrual_date, agreement_id, base_amount, daily_rate, amount, created_at)
                values (?, ?, ?, 1010.00, 0.000500, 0.51, ?)""";
        jdbc.update(accrual, bytes(installment), today().minusDays(1), bytes(agreement), now());
        assertThatThrownBy(() -> jdbc.update(accrual, bytes(installment), today().minusDays(1), bytes(agreement), now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerOperationAndCaller() {
        UUID actor = UUID.randomUUID();
        insertIdempotency("DISBURSE", actor, "same-key-123");
        insertIdempotency("REPAYMENT", actor, "same-key-123");
        insertIdempotency("DISBURSE", UUID.randomUUID(), "same-key-123");

        assertThatThrownBy(() -> insertIdempotency("DISBURSE", actor, "same-key-123"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_idempotency_records_key");
    }

    private UUID insertOffer(UUID id, UUID invoice, UUID financier, String status, String advance) {
        UUID seller = jdbc.queryForObject("select seller_id from financeable_invoices where invoice_id = ?",
                (rs, n) -> uuid(rs.getBytes(1)), bytes(invoice));
        jdbc.update("""
                        insert into financing_offers (id, invoice_id, seller_id, financier_id, advance_amount, annual_rate,
                                                      processing_fee, installment_count, currency, expires_at, status,
                                                      created_at, updated_at)
                        values (?, ?, ?, ?, ?, 0.1200, 0.00, 1, 'INR', ?, ?, ?, ?)""",
                bytes(id), bytes(invoice), bytes(seller), bytes(financier), new java.math.BigDecimal(advance),
                Timestamp.from(Instant.now().plusSeconds(3600)), status, now(), now());
        return id;
    }

    private UUID insertAgreement(UUID id, UUID offer, UUID invoice, UUID seller, String status) {
        UUID financier = jdbc.queryForObject("select financier_id from financing_offers where id = ?",
                (rs, n) -> uuid(rs.getBytes(1)), bytes(offer));
        boolean disbursed = status.equals("DISBURSED");
        jdbc.update("""
                        insert into financing_agreements (id, offer_id, invoice_id, seller_id, financier_id, invoice_amount,
                                                          advance_amount, annual_rate, processing_fee, installment_count,
                                                          currency, maturity_date, status, fee_amount, total_repayable,
                                                          created_at, updated_at)
                        values (?, ?, ?, ?, ?, 500000.00, 1000.00, 0.1200, 0.00, 1, 'INR', ?, ?, ?, ?, ?, ?)""",
                bytes(id), bytes(offer), bytes(invoice), bytes(seller), bytes(financier), today().plusDays(60), status,
                disbursed ? new java.math.BigDecimal("10.00") : null, disbursed ? new java.math.BigDecimal("1010.00") : null,
                now(), now());
        return id;
    }

    private void insertDisbursement(UUID agreement, String reference) {
        jdbc.update("""
                        insert into disbursements (id, agreement_id, amount, currency, status, reference, initiated_by, disbursed_at)
                        values (?, ?, 1000.00, 'INR', 'SIMULATED_COMPLETED', ?, ?, ?)""",
                bytes(UUID.randomUUID()), bytes(agreement), reference, bytes(UUID.randomUUID()), now());
    }

    private void insertIdempotency(String operation, UUID actor, String key) {
        jdbc.update("""
                        insert into idempotency_records (operation, actor_id, idempotency_key, request_hash, resource_id, created_at)
                        values (?, ?, ?, ?, ?, ?)""",
                operation, bytes(actor), key, "0".repeat(64), bytes(UUID.randomUUID()), now());
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
