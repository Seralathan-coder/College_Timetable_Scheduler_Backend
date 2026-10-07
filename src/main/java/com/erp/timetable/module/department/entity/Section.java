package com.erp.timetable.module.department.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sections", uniqueConstraints = {
    @UniqueConstraint(name = "uk_year_section", columnNames = {"academic_year_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Section extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_year_id", nullable = false)
    private AcademicYear academicYear;

    @Column(name = "name", nullable = false, length = 10)
    private String name; // e.g., "A", "B", "CS-1"

    @Column(name = "student_strength")
    private Integer studentStrength;

    @Column(name = "faculty_advisor_id")
    private Long facultyAdvisorId;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
