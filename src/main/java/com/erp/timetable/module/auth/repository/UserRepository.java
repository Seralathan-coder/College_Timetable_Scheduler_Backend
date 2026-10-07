package com.erp.timetable.module.auth.repository;

import com.erp.timetable.module.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    List<User> findAllByUsername(String username);

    List<User> findByDepartment_Id(Long departmentId);

    /**
     * Resolves every account whose username OR email equals the given login
     * identifier. Returns a LIST because login identifiers are only unique per
     * college — two colleges may each have the same account.
     */
    @Query("SELECT u FROM User u WHERE u.username = :key OR u.email = :key")
    List<User> findAllByUsernameOrEmail(@Param("key") String key);

    /**
     * True when a user account with this username already exists in the given
     * college ({@code null} collegeId checks the legacy global accounts that
     * have no tenant anchor).
     */
    @Query("""
        SELECT COUNT(u) > 0 FROM User u
        WHERE u.username = :username
          AND ((:collegeId IS NULL AND u.college IS NULL) OR u.college.id = :collegeId)
        """)
    boolean existsByUsernameForCollege(@Param("username") String username, @Param("collegeId") Long collegeId);

    @Query("""
        SELECT COUNT(u) > 0 FROM User u
        WHERE u.email = :email
          AND ((:collegeId IS NULL AND u.college IS NULL) OR u.college.id = :collegeId)
        """)
    boolean existsByEmailForCollege(@Param("email") String email, @Param("collegeId") Long collegeId);

    Optional<User> findByRefreshToken(String refreshToken);

    /** Finds the account holding a (hashed) one-time password-reset token. */
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    @Modifying
    @Query("UPDATE User u SET u.refreshToken = null, u.refreshTokenExpiry = null WHERE u.id = :userId")
    void revokeRefreshToken(Long userId);
}
