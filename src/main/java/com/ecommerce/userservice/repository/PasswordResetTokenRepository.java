// repository/PasswordResetTokenRepository.java
package com.ecommerce.userservice.repository;

import com.ecommerce.userservice.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    //    SELECT * FROM password_reset_tokens
    //    WHERE user_id = ?
    //    ORDER BY created_at DESC
    //    LIMIT 1;
    Optional<PasswordResetToken> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Issuing a new token, or spending one, kills every other outstanding token.
     * flushAutomatically pushes pending entity changes (the new password) to the DB
     * BEFORE this bulk update; clearAutomatically drops the now-stale persistence
     * context afterwards, so nothing overwrites it later.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now " +
            "WHERE t.user.id = :userId AND t.usedAt IS NULL")
    int invalidateActiveTokens(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Housekeeping — run from a scheduled job.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}