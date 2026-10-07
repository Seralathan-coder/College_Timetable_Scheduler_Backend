package com.erp.timetable.module.auth.repository;

import com.erp.timetable.module.auth.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshToken(String refreshToken);

    Optional<UserSession> findByIdAndUserId(Long id, Long userId);

    /** All rows for a user (any state) — used by bulk-revocation flows. */
    List<UserSession> findByUserId(Long userId);

    /** Active (not revoked, not expired) sessions for the user, newest first. */
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId "
        + "AND s.isRevoked = false AND s.expiresAt > :now ORDER BY s.createdAt DESC")
    List<UserSession> findActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("SELECT COUNT(s) FROM UserSession s WHERE s.user.id = :userId "
        + "AND s.isRevoked = false AND s.expiresAt > :now")
    long countActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}