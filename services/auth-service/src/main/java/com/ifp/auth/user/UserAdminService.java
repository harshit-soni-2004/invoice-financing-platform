package com.ifp.auth.user;

import com.ifp.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
    public static final String USER_REJECTED = "user.rejected";

    private final UserAccountRepository users;
    private final Clock clock;
    private final OutboxWriter outbox;

    public UserAdminService(UserAccountRepository users, Clock clock, OutboxWriter outbox) {
        this.users = users;
        this.clock = clock;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public UserAccount get(UUID id) {
        return users.findById(id).orElseThrow(UserNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<UserAccount> list(Role role, UserStatus status, Pageable pageable) {
        Specification<UserAccount> spec = (root, query, cb) -> cb.conjunction();
        if (role != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return users.findAll(spec, pageable);
    }

    /** Approves a pending financier (or any pending account). Optimistic locking guards concurrent approvals. */
    @Transactional
    public UserAccount activate(UUID id, UUID adminId) {
        UserAccount account = get(id);
        if (!account.activate(clock.instant())) {
            throw new UserNotPendingApprovalException(account.getStatus());
        }
        users.saveAndFlush(account);
        log.info("User activated: id={} by admin={}", account.getId(), adminId);
        return account;
    }

    /**
     * Turns down a pending account for good. The {@code UserRejected} event commits with the status change, and
     * audit-service records it like every other event. Optimistic locking makes a racing activate/reject fail.
     */
    @Transactional
    public UserAccount reject(UUID id, UUID adminId, String reason) {
        UserAccount account = get(id);
        if (!account.reject(reason, clock.instant())) {
            throw new UserNotPendingApprovalException(account.getStatus());
        }
        users.saveAndFlush(account);
        outbox.append(USER_REJECTED, "UserRejected", "USER", account.getId(), adminId,
                new UserRejected(account.getId(), account.getRole().name(), account.getRejectionReason()));
        log.info("User rejected: id={} by admin={}", account.getId(), adminId);
        return account;
    }

    public record UserRejected(UUID userId, String role, String reason) {
    }
}
