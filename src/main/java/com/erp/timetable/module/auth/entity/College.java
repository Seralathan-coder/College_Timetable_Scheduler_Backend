package com.erp.timetable.module.auth.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A tenant college.
 *
 * <p>Each college has its own College Admin account ({@code ROLE_COLLEGE_ADMIN}),
 * its own departments/faculty/subjects/classrooms/timetables and fully isolated
 * data. The old single-row {@link Institution} remains for migration/fallback
 * only; all live reads/updates flow through a {@code College}.
 */
@Entity
@Table(name = "colleges", indexes = {
    @Index(name = "idx_college_code", columnList = "code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class College extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Short unique tenant code (e.g. DEV001, ABC), also used in the admin login ID. */
    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}