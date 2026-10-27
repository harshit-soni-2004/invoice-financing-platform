package com.ifp.financing.service;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.ConflictException;
import com.ifp.financing.domain.InvalidStatusTransitionException;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.OfferTerms;
import com.ifp.financing.repository.DisbursementRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.FinancingOfferRepository;
import com.ifp.financing.repository.RepaymentRepository;
import com.ifp.financing.security.Actor;
import com.ifp.financing.security.Role;
import com.ifp.financing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Races real threads against MySQL; every service call runs in its own transaction. */
class FinancingConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private OfferService offerService;
    @Autowired
    private AgreementService agreementService;
    @Autowired
    private FinancingOfferRepository offers;
    @Autowired
    private FinancingAgreementRepository agreements;
    @Autowired
    private DisbursementRepository disbursements;
    @Autowired
    private RepaymentRepository repayments;

    @RepeatedTest(3)
    void sellerAcceptingTwoOffersAtOnceCreatesOneAgreement() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID invoice = listedInvoice(sellerId);
        List<UUID> offerIds = List.of(offer(invoice), offer(invoice), offer(invoice));
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));

        List<Callable<Object>> attempts = new ArrayList<>();
        for (UUID offerId : offerIds) {
            attempts.add(() -> offerService.accept(seller, offerId));
        }
        List<Outcome> outcomes = race(attempts);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).allSatisfy(loser -> assertThat(loser.error())
                .isInstanceOfAny(ConflictException.class, InvalidStatusTransitionException.class, OptimisticLockingFailureException.class));
        assertThat(agreements.findByInvoiceIdAndStatusIn(invoice, OfferService.LIVE_AGREEMENT_STATUSES)).hasSize(1);
        assertThat(offerIds.stream().map(id -> offers.findById(id).orElseThrow().getStatus()))
                .containsOnlyOnce(OfferStatus.ACCEPTED);
    }

    @RepeatedTest(3)
    void concurrentDisbursementsWithSameKeyPayOutOnce() throws Exception {
        Deal deal = approvedDeal();
        Actor financier = new Actor(deal.financierId(), Set.of(Role.FINANCIER));
        String key = newKey();

        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> agreementService.disburse(financier, deal.agreementId(), key));
        }
        List<Outcome> outcomes = race(attempts);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        List<IdempotentExecutor.Result<?>> results = new ArrayList<>();
        outcomes.forEach(o -> results.add((IdempotentExecutor.Result<?>) o.value()));
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(results.stream().map(r -> ((com.ifp.financing.domain.Disbursement) r.value()).getId()).distinct()).hasSize(1);
        assertThat(disbursements.countByAgreementId(deal.agreementId())).isEqualTo(1);
    }

    @Test
    void concurrentDisbursementsWithDifferentKeysPayOutOnce() throws Exception {
        Deal deal = approvedDeal();
        Actor financier = new Actor(deal.financierId(), Set.of(Role.FINANCIER));

        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> agreementService.disburse(financier, deal.agreementId(), newKey()));
        }
        List<Outcome> outcomes = race(attempts);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(loser -> assertThat(loser.error()).isInstanceOf(InvalidStatusTransitionException.class));
        assertThat(disbursements.countByAgreementId(deal.agreementId())).isEqualTo(1);
    }

    @Test
    void concurrentRepaymentsSerializeAndNeverExceedTheTotal() throws Exception {
        Deal deal = disbursedDeal();
        Actor seller = new Actor(deal.sellerId(), Set.of(Role.SELLER));
        // Total repayable is 490468.49; five payments of 100000 cannot all fit.
        List<Callable<Object>> attempts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attempts.add(() -> agreementService.recordRepayment(seller, deal.agreementId(), new BigDecimal("100000.00"), newKey()));
        }
        List<Outcome> outcomes = race(attempts);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(4);
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).singleElement()
                .satisfies(loser -> assertThat(loser.error()).hasFieldOrPropertyWithValue("code", "REPAYMENT_EXCEEDS_OUTSTANDING"));
        var agreement = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(agreement.outstanding()).isEqualByComparingTo("90468.49");
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.REPAYMENT_IN_PROGRESS);
        assertThat(repayments.countByAgreementId(deal.agreementId())).isEqualTo(4);
    }

    private UUID offer(UUID invoice) {
        Actor financier = new Actor(UUID.randomUUID(), Set.of(Role.FINANCIER));
        return offerService.create(financier, invoice,
                new OfferTerms(new BigDecimal("400000.00"), new BigDecimal("0.1500"), BigDecimal.ZERO, 1),
                clock.instant().plus(Duration.ofDays(2))).getId();
    }

    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private static List<Outcome> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
