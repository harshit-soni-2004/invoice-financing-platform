package com.ifp.invoice.service;

import com.ifp.invoice.domain.InvalidStatusTransitionException;
import com.ifp.invoice.domain.InvoiceDetails;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.domain.InvoiceStatusHistory;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceSpecifications;
import com.ifp.invoice.repository.InvoiceStatusHistoryRepository;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.security.Role;
import com.ifp.invoice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
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

/** Races real threads against MySQL; each service call runs in its own transaction. */
class ConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService service;
    @Autowired
    private InvoiceRepository invoices;
    @Autowired
    private InvoiceStatusHistoryRepository history;

    @RepeatedTest(5)
    void concurrentSubmitAndCancelProduceExactlyOneTransition() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID id = createInvoice(sellerId);
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));

        List<Outcome> outcomes = race(List.of(
                () -> service.submit(seller, id, ExpectedVersion.ANY).getStatus(),
                () -> service.cancel(seller, id, ExpectedVersion.ANY, "race").getStatus()));

        List<Outcome> winners = outcomes.stream().filter(Outcome::succeeded).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(loser -> assertThat(loser.error())
                        .isInstanceOfAny(OptimisticLockingFailureException.class, InvalidStatusTransitionException.class));

        var invoice = invoices.findById(id).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(winners.get(0).status());
        assertThat(invoice.getVersion()).isEqualTo(1L);
        assertThat(history.findByInvoiceIdOrderByOccurredAtAscIdAsc(id))
                .extracting(InvoiceStatusHistory::getToStatus)
                .containsExactly(InvoiceStatus.DRAFT, winners.get(0).status());
    }

    @Test
    void twoAdminsVerifyingSimultaneouslyRecordOneVerification() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID id = createInvoice(sellerId);
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));
        Actor adminA = new Actor(UUID.randomUUID(), Set.of(Role.ADMIN));
        Actor adminB = new Actor(UUID.randomUUID(), Set.of(Role.ADMIN));
        service.submit(seller, id, ExpectedVersion.ANY);
        service.confirmBuyer(adminA, id, ExpectedVersion.ANY);

        List<Outcome> outcomes = race(List.of(
                () -> service.verify(adminA, id, ExpectedVersion.ANY).getStatus(),
                () -> service.verify(adminB, id, ExpectedVersion.ANY).getStatus()));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(history.findByInvoiceIdOrderByOccurredAtAscIdAsc(id))
                .filteredOn(entry -> entry.getToStatus() == InvoiceStatus.VERIFIED)
                .hasSize(1);
    }

    @Test
    void concurrentCreatesWithSameNumberYieldOneInvoice() throws Exception {
        UUID sellerId = UUID.randomUUID();
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));
        InvoiceDetails details = new InvoiceDetails("RACE-001", "Buyer", null, null, new BigDecimal("1000.00"),
                TODAY.minusDays(1), TODAY.plusDays(30));

        List<Callable<InvoiceStatus>> attempts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attempts.add(() -> service.create(seller, details).getStatus());
        }
        List<Outcome> outcomes = race(attempts);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(loser -> assertThat(loser.error()).isInstanceOf(DuplicateInvoiceNumberException.class));
        assertThat(invoices.count(InvoiceSpecifications.matching(sellerId, null))).isEqualTo(1);
    }

    private record Outcome(InvoiceStatus status, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    private static List<Outcome> race(List<Callable<InvoiceStatus>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<InvoiceStatus> task : tasks) {
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
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
