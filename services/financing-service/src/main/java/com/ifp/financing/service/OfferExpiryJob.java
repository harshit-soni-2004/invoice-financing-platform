package com.ifp.financing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically marks expired offers. Acceptance never depends on this job: an offer past its expiry is refused at
 * accept time even if the sweep has not run yet.
 */
@Component
@ConditionalOnProperty(name = "financing.offer-expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
class OfferExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OfferExpiryJob.class);
    private static final int BATCH_SIZE = 200;

    private final OfferService offers;

    OfferExpiryJob(OfferService offers) {
        this.offers = offers;
    }

    @Scheduled(fixedDelayString = "${financing.offer-expiry-sweep-interval}", initialDelayString = "${financing.offer-expiry-sweep-interval}")
    void expireOffers() {
        try {
            int expired = offers.expireDueOffers(BATCH_SIZE);
            if (expired > 0) {
                log.info("Expired {} offer(s)", expired);
            }
        } catch (RuntimeException e) {
            // Concurrent changes (an offer accepted at the same moment) are retried on the next sweep.
            log.warn("Offer expiry sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
