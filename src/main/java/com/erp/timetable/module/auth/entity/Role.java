package com.erp.timetable.module.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Application role entity.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", length = 50, nullable = false, unique = true)
    private RoleName name;

    @Column(name = "description", length = 200)
    private String description;
}
