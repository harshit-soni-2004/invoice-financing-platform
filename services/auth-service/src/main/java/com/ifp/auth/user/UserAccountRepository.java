package com.ifp.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

    /** Callers must pass an already-normalized email (see {@link UserAccount#normalizeEmail}). */
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);
}
