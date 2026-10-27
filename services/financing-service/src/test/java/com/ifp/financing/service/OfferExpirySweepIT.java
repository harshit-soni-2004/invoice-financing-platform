package com.ifp.financing.service;

import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.repository.FinancingOfferRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real scheduled sweep (same duration format as the production default) in its own context. */
@TestPropertySource(properties = {
        "financing.offer-expiry-sweep-enabled=true",
        "financing.offer-expiry-sweep-interval=1s"
})
class OfferExpirySweepIT extends AbstractIntegrationTest {

    @Autowired
    private FinancingOfferRepository offers;

    @Test
    void scheduledSweepExpiresOffersPastTheirValidity() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID offer = createOffer(UUID.randomUUID(), invoice);

        clock.advance(Duration.ofDays(4));

        Instant deadline = Instant.now().plusSeconds(15);
        while (offers.findById(offer).orElseThrow().getStatus() == OfferStatus.OFFERED && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(offers.findById(offer).orElseThrow().getStatus()).isEqualTo(OfferStatus.EXPIRED);
    }
}
