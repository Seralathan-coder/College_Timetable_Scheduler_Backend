package com.erp.timetable.module.auth.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

/**
 * Global institution/college information — a SINGLE row (id = 1).
 *
 * <p>Holds the college name and address shown on the profile. It is
 * intentionally a separate single-row entity (not per-user columns) so that
 * institution data is not duplicated on every {@link User}. The row is
 * readable by any authenticated user; it can be created/updated only by a
 * {@code SUPER_ADMIN}.
 */
@Entity
@Table(name = "institution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Institution extends AuditableEntity {

    /** The single row's fixed primary key. Enforced by migration check + service. */
    public static final Long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
}