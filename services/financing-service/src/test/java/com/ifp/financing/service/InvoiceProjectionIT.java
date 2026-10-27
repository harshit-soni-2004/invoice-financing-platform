package com.ifp.financing.service;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.InvoiceState;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.repository.FinanceableInvoiceRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.FinancingOfferRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.financing.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvoiceProjectionIT extends AbstractIntegrationTest {

    @Autowired
    private FinanceableInvoiceRepository invoices;
    @Autowired
    private FinancingOfferRepository offers;
    @Autowired
    private FinancingAgreementRepository agreements;

    @Test
    void duplicateAndOutOfOrderSnapshotsAreIgnored() {
        UUID invoiceId = UUID.randomUUID();
        UUID seller = UUID.randomUUID();

        assertThat(projection.apply(snapshot(invoiceId, seller, InvoiceState.VERIFIED, 3, 60)))
                .isEqualTo(InvoiceProjectionService.Outcome.CREATED);
        assertThat(projection.apply(snapshot(invoiceId, seller, InvoiceState.FINANCING_AVAILABLE, 5, 60)))
                .isEqualTo(InvoiceProjectionService.Outcome.UPDATED);
        assertThat(projection.apply(snapshot(invoiceId, seller, InvoiceState.FINANCING_AVAILABLE, 5, 60)))
                .isEqualTo(InvoiceProjectionService.Outcome.IGNORED_STALE);
        assertThat(projection.apply(snapshot(invoiceId, seller, InvoiceState.VERIFIED, 4, 60)))
                .isEqualTo(InvoiceProjectionService.Outcome.IGNORED_STALE);

        var stored = invoices.findById(invoiceId).orElseThrow();
        assertThat(stored.getInvoiceStatus()).isEqualTo(InvoiceState.FINANCING_AVAILABLE);
        assertThat(stored.getSourceVersion()).isEqualTo(5);
    }

    @Test
    void ineligibleInvoicesAreNotOfferedToFinanciers() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID listed = listedInvoice(seller, 60);
        UUID tooSoon = listedInvoice(seller, 3);
        UUID notListed = UUID.randomUUID();
        projection.apply(snapshot(notListed, seller, InvoiceState.VERIFIED, 1, 60));
        String financier = TestJwtSupport.financier(UUID.randomUUID());

        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", listed), financier)).andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", tooSoon), financier)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", notListed), financier)).andExpect(status().isNotFound());

        var page = body(mockMvc.perform(withToken(get("/api/financing/opportunities").param("size", "100"), financier))
                .andExpect(status().isOk()).andReturn());
        var ids = page.get("content").findValuesAsText("invoiceId");
        assertThat(ids).doesNotContain(tooSoon.toString(), notListed.toString());
    }

    @Test
    void invoiceWithdrawnFromMarketClosesOffersAndCancelsPendingAgreement() throws Exception {
        Deal deal = pendingDeal();
        UUID otherInvoice = listedInvoice(deal.sellerId());
        UUID openOffer = createOffer(UUID.randomUUID(), otherInvoice);

        projection.apply(snapshot(deal.invoiceId(), deal.sellerId(), InvoiceState.CANCELLED, 10, 60));
        projection.apply(snapshot(otherInvoice, deal.sellerId(), InvoiceState.CANCELLED, 10, 60));

        assertThat(agreements.findById(deal.agreementId()).orElseThrow().getStatus()).isEqualTo(AgreementStatus.CANCELLED);
        assertThat(offers.findById(openOffer).orElseThrow().getStatus()).isEqualTo(OfferStatus.CLOSED);
    }

    @Test
    void financedEchoDoesNotCancelTheAgreement() throws Exception {
        Deal deal = disbursedDeal();

        projection.apply(snapshot(deal.invoiceId(), deal.sellerId(), InvoiceState.FINANCED, 10, 60));

        assertThat(agreements.findById(deal.agreementId()).orElseThrow().getStatus()).isEqualTo(AgreementStatus.DISBURSED);
    }
}
