package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.identity.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByRoleAndActiveTrue(UserRole role);
}
