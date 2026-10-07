package com.erp.timetable.module.auth.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.department.entity.Department;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * System user entity.
 * Represents admins, HODs, faculty members, and exam coordinators.
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_username", columnList = "username")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_users_college_username", columnNames = {"college_id", "username"}),
    @UniqueConstraint(name = "uk_users_college_email", columnNames = {"college_id", "email"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Tenant anchor — every user belongs to exactly one college. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "college_id")
    private College college;

    @Column(name = "refresh_token", length = 500)
    private String refreshToken;

    @Column(name = "refresh_token_expiry")
    private Instant refreshTokenExpiry;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;

    /**
     * Student class identity (nullable, only meaningful for ROLE_STUDENT users).
     * Plain id columns (no JPA relation) so students are never lazilly loaded
     * through the user; lookups go through the respective repositories.
     */
    @Column(name = "academic_year_id")
    private Long academicYearId;

    @Column(name = "section_id")
    private Long sectionId;

    /**
     * One-time password-reset token, stored HASHED (SHA-256 of the raw token
     * that was returned to the requester). Cleared after a successful reset or
     * when the expiry passes. Null means no reset is in flight.
     */
    @Column(name = "password_reset_token", length = 64)
    private String passwordResetToken;

    @Column(name = "password_reset_expiry")
    private Instant passwordResetExpiry;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    // ── Helpers ────────────────────────────────────────────────────────

    public void addRole(Role role) {
        this.roles.add(role);
    }

    public boolean hasRole(RoleName roleName) {
        return this.roles.stream()
            .anyMatch(r -> r.getName() == roleName);
    }
}
