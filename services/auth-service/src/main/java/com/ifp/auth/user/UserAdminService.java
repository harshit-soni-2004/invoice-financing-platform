package com.ifp.auth.user;

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

    private final UserAccountRepository users;
    private final Clock clock;

    public UserAdminService(UserAccountRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
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
}
