package com.ifp.financing.service;

import com.ifp.financing.domain.IdempotencyRecord;
import com.ifp.financing.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a non-idempotent operation at most once per (operation, caller, Idempotency-Key).
 * <ul>
 *   <li>First use: the business change and the idempotency record commit in one transaction.</li>
 *   <li>Replay with the same request: the stored resource is returned and nothing new is written.</li>
 *   <li>Same key with a different request: {@link IdempotencyKeyReusedException} (409).</li>
 *   <li>Two identical requests racing: the unique key (and the business constraints) let only one commit; the loser
 *       re-reads in a new transaction and returns the winner's result as a replay.</li>
 * </ul>
 */
@Component
public class IdempotentExecutor {

    public record Result<T>(T value, boolean replayed) {
    }

    private final IdempotencyRecordRepository records;
    private final TransactionTemplate tx;
    private final Clock clock;

    public IdempotentExecutor(IdempotencyRecordRepository records, PlatformTransactionManager transactionManager, Clock clock) {
        this.records = records;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public <T> Result<T> run(String operation, UUID actorId, String key, String requestHash,
                             Supplier<T> action, Function<T, UUID> idOf, Function<UUID, T> loader) {
        try {
            return tx.execute(status -> {
                Optional<Result<T>> replay = replay(operation, actorId, key, requestHash, loader);
                if (replay.isPresent()) {
                    return replay.get();
                }
                T value = action.get();
                records.saveAndFlush(new IdempotencyRecord(operation, actorId, key, requestHash, idOf.apply(value), clock.instant()));
                return new Result<>(value, false);
            });
        } catch (RuntimeException first) {
            // A concurrent request with the same key may have committed first; if so, its result wins.
            Optional<Result<T>> replay;
            try {
                replay = tx.execute(status -> replay(operation, actorId, key, requestHash, loader));
            } catch (IdempotencyKeyReusedException reused) {
                throw reused;
            } catch (RuntimeException ignored) {
                throw first;
            }
            return replay.orElseThrow(() -> first);
        }
    }

    private <T> Optional<Result<T>> replay(String operation, UUID actorId, String key, String requestHash,
                                           Function<UUID, T> loader) {
        return records.findByOperationAndActorIdAndIdempotencyKey(operation, actorId, key).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException();
            }
            return new Result<>(loader.apply(existing.getResourceId()), true);
        });
    }

    /** SHA-256 over the request's identifying fields, joined with a separator that cannot appear in them. */
    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u001f", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
