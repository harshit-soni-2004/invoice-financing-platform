package com.ifp.invoice.service;

import com.ifp.invoice.config.InvoiceProperties;
import com.ifp.invoice.domain.InvalidStatusTransitionException;
import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceDetails;
import com.ifp.invoice.domain.InvoiceNotEditableException;
import com.ifp.invoice.domain.InvoicePolicy;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.domain.InvoiceStatusHistory;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceStatusHistoryRepository;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.security.Role;
import com.ifp.messaging.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-27");

    @Mock
    private InvoiceRepository invoices;
    @Mock
    private InvoiceStatusHistoryRepository history;
    @Mock
    private OutboxWriter outbox;

    private InvoiceService service;

    private final Actor seller = new Actor(UUID.randomUUID(), Set.of(Role.SELLER));
    private final Actor otherSeller = new Actor(UUID.randomUUID(), Set.of(Role.SELLER));
    private final Actor admin = new Actor(UUID.randomUUID(), Set.of(Role.ADMIN));
    private final Actor financier = new Actor(UUID.randomUUID(), Set.of(Role.FINANCIER));

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvoicePolicy policy = new InvoicePolicy(
                new InvoiceProperties(ZoneId.of("Asia/Kolkata"), new BigDecimal("100000000.00"), 180, 7), clock);
        service = new InvoiceService(invoices, history, policy, clock, outbox);
    }

    private static InvoiceDetails details(String number, LocalDate dueDate) {
        return new InvoiceDetails(number, "Buyer Co", null, null, new BigDecimal("500000.00"), TODAY.minusDays(1), dueDate);
    }

    private Invoice persistedDraft(Actor owner, LocalDate dueDate) {
        Invoice invoice = Invoice.createDraft(UUID.randomUUID(), owner.userId(), details("INV-1", dueDate), NOW);
        ReflectionTestUtils.setField(invoice, "version", 0L);
        return invoice;
    }

    @Test
    void createAssignsSellerFromAuthenticatedActorAndRecordsHistory() {
        when(invoices.saveAndFlush(any())).thenAnswer(call -> {
            Invoice saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "version", 0L);
            return saved;
        });
        Invoice created = service.create(seller, details("INV-1", TODAY.plusDays(60)));
        verify(outbox).append(eq("invoice.created"), eq("InvoiceCreated"), eq("INVOICE"), eq(created.getId()),
                eq(seller.userId()), eq(InvoiceEvents.InvoiceChanged.of(created, null)));

        assertThat(created.getSellerId()).isEqualTo(seller.userId());
        assertThat(created.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        verify(invoices).saveAndFlush(created);
        ArgumentCaptor<InvoiceStatusHistory> entry = ArgumentCaptor.forClass(InvoiceStatusHistory.class);
        verify(history).save(entry.capture());
        assertThat(entry.getValue().getFromStatus()).isNull();
        assertThat(entry.getValue().getToStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(entry.getValue().getActorId()).isEqualTo(seller.userId());
        assertThat(entry.getValue().getActorRole()).isEqualTo("SELLER");
    }

    @Test
    void onlySellersCanCreate() {
        assertThatThrownBy(() -> service.create(financier, details("INV-1", TODAY.plusDays(60))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(admin, details("INV-1", TODAY.plusDays(60))))
                .isInstanceOf(AccessDeniedException.class);
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNumberDetectedByPreCheck() {
        when(invoices.existsBySellerIdAndInvoiceNumber(seller.userId(), "INV-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(seller, details(" INV-1 ", TODAY.plusDays(60))))
                .isInstanceOf(DuplicateInvoiceNumberException.class);
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void duplicateNumberRaceTranslatedFromUniqueConstraint() {
        when(invoices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new SQLIntegrityConstraintViolationException("Duplicate entry for key 'invoices.uk_invoices_seller_number'")));

        assertThatThrownBy(() -> service.create(seller, details("INV-1", TODAY.plusDays(60))))
                .isInstanceOf(DuplicateInvoiceNumberException.class);
        verify(history, never()).save(any());
    }

    @Test
    void otherConstraintViolationsAreNotMisreportedAsDuplicates() {
        when(invoices.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check",
                new SQLIntegrityConstraintViolationException("Check constraint 'ck_invoices_amount_positive' is violated")));

        assertThatThrownBy(() -> service.create(seller, details("INV-1", TODAY.plusDays(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sellerCannotSeeAnotherSellersInvoice() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        when(invoices.findByIdAndSellerId(invoice.getId(), otherSeller.userId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(otherSeller, invoice.getId())).isInstanceOf(InvoiceNotFoundException.class);
        assertThatThrownBy(() -> service.submit(otherSeller, invoice.getId(), ExpectedVersion.ANY))
                .isInstanceOf(InvoiceNotFoundException.class);
        verify(invoices, never()).findById(any());
    }

    @Test
    void adminCanSeeAnyInvoiceButFinancierCannot() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThat(service.get(admin, invoice.getId())).isSameAs(invoice);
        assertThatThrownBy(() -> service.get(financier, invoice.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verificationActionsRequireAdmin() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.verify(seller, id, ExpectedVersion.ANY)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.confirmBuyer(seller, id, ExpectedVersion.ANY)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reject(financier, id, ExpectedVersion.ANY, "no")).isInstanceOf(AccessDeniedException.class);
        verify(invoices, never()).findById(any());
    }

    @Test
    void sellerLifecycleActionsRequireSellerRole() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.submit(admin, id, ExpectedVersion.ANY)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(financier, id, ExpectedVersion.ANY, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listForFinancing(admin, id, ExpectedVersion.ANY)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staleIfMatchFailsBeforeAnyChange() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        when(invoices.findByIdAndSellerId(invoice.getId(), seller.userId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.submit(seller, invoice.getId(), ExpectedVersion.of(4)))
                .isInstanceOf(PreconditionFailedException.class)
                .extracting("currentVersion").isEqualTo(0L);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void invalidTransitionReportedBeforeMaturityRule() {
        // Due date is too soon AND the invoice is cancelled: the caller should learn about the state conflict.
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(2));
        invoice.cancel(null, NOW);
        when(invoices.findByIdAndSellerId(invoice.getId(), seller.userId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.submit(seller, invoice.getId(), ExpectedVersion.ANY))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void transitionFlushesAndRecordsFromAndToStatus() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        invoice.submit(NOW);
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        service.reject(admin, invoice.getId(), ExpectedVersion.of(0), "  Buyer disputes  ");

        verify(invoices).saveAndFlush(invoice);
        ArgumentCaptor<InvoiceStatusHistory> entry = ArgumentCaptor.forClass(InvoiceStatusHistory.class);
        verify(history).save(entry.capture());
        assertThat(entry.getValue().getFromStatus()).isEqualTo(InvoiceStatus.SUBMITTED);
        assertThat(entry.getValue().getToStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(entry.getValue().getActorRole()).isEqualTo("ADMIN");
        assertThat(entry.getValue().getReason()).isEqualTo("Buyer disputes");
        assertThat(entry.getValue().getOccurredAt()).isEqualTo(NOW);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq("invoice.rejected"), eq("InvoiceRejected"), eq("INVOICE"), eq(invoice.getId()),
                eq(admin.userId()), payload.capture());
        InvoiceEvents.InvoiceChanged event = (InvoiceEvents.InvoiceChanged) payload.getValue();
        assertThat(event.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(event.previousStatus()).isEqualTo(InvoiceStatus.SUBMITTED);
        assertThat(event.sellerId()).isEqualTo(seller.userId());
        assertThat(event.amount()).isEqualByComparingTo("500000.00");
    }

    @Test
    void failedTransitionPublishesNothing() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.verify(admin, invoice.getId(), ExpectedVersion.ANY))
                .isInstanceOf(InvalidStatusTransitionException.class);
        verify(outbox, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void settlementOvertakingDisbursementAppliesBothStepsAsSystem() {
        Invoice invoice = listedInvoice();
        UUID eventActor = UUID.randomUUID();

        assertThat(service.applyFinancingOutcome(invoice.getId(), InvoiceStatus.SETTLED, eventActor)).isTrue();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(invoice.getFinancedAt()).isEqualTo(NOW);
        assertThat(invoice.getSettledAt()).isEqualTo(NOW);
        ArgumentCaptor<InvoiceStatusHistory> entries = ArgumentCaptor.forClass(InvoiceStatusHistory.class);
        verify(history, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues()).extracting(InvoiceStatusHistory::getToStatus)
                .containsExactly(InvoiceStatus.FINANCED, InvoiceStatus.SETTLED);
        assertThat(entries.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getActorRole()).isEqualTo("SYSTEM");
            assertThat(entry.getActorId()).isEqualTo(eventActor);
        });
        verify(outbox).append(eq("invoice.financed"), eq("InvoiceFinanced"), any(), any(), eq(eventActor), any());
        verify(outbox).append(eq("invoice.settled"), eq("InvoiceSettled"), any(), any(), eq(eventActor), any());
    }

    @Test
    void alreadyAppliedFinancingOutcomesAreNoOps() {
        Invoice invoice = listedInvoice();
        invoice.markFinanced(NOW);
        invoice.markSettled(NOW);

        assertThat(service.applyFinancingOutcome(invoice.getId(), InvoiceStatus.FINANCED, null)).isFalse();
        assertThat(service.applyFinancingOutcome(invoice.getId(), InvoiceStatus.SETTLED, null)).isFalse();
        verify(invoices, never()).saveAndFlush(any());
        verify(outbox, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void financingOutcomeForAnInvoiceNotListedIsRejected() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        invoice.submit(NOW);
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.applyFinancingOutcome(invoice.getId(), InvoiceStatus.FINANCED, null))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SUBMITTED);
        verify(outbox, never()).append(any(), any(), any(), any(), any(), any());
    }

    private Invoice listedInvoice() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        invoice.submit(NOW);
        invoice.confirmBuyer(admin.userId(), NOW);
        invoice.verify(admin.userId(), NOW);
        invoice.listForFinancing(NOW);
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void updateRejectedOutsideDraftWithoutWriting() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        invoice.submit(NOW);
        when(invoices.findByIdAndSellerId(invoice.getId(), seller.userId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.update(seller, invoice.getId(), ExpectedVersion.of(0), details("INV-2", TODAY.plusDays(60))))
                .isInstanceOf(InvoiceNotEditableException.class);
        verify(invoices, never()).saveAndFlush(any());
        verify(invoices, never()).existsBySellerIdAndInvoiceNumber(any(), anyString());
    }

    @Test
    void renamingToExistingNumberIsRejected() {
        Invoice invoice = persistedDraft(seller, TODAY.plusDays(60));
        when(invoices.findByIdAndSellerId(invoice.getId(), seller.userId())).thenReturn(Optional.of(invoice));
        when(invoices.existsBySellerIdAndInvoiceNumber(eq(seller.userId()), eq("INV-2"))).thenReturn(true);

        assertThatThrownBy(() -> service.update(seller, invoice.getId(), ExpectedVersion.of(0), details("INV-2", TODAY.plusDays(60))))
                .isInstanceOf(DuplicateInvoiceNumberException.class);
    }
}
