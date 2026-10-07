package com.erp.timetable.module.department.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.auth.entity.College;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "departments", indexes = {
    @Index(name = "idx_dept_name", columnList = "name")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_departments_college_name", columnNames = {"college_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "hod_name", length = 150)
    private String hodName;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "building", length = 100)
    private String building;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_archived", nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    /** Tenant anchor — the department belongs to exactly one college. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "college_id")
    private College college;

    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AcademicYear> academicYears = new ArrayList<>();

    public void addAcademicYear(AcademicYear year) {
        academicYears.add(year);
        year.setDepartment(this);
    }
}
