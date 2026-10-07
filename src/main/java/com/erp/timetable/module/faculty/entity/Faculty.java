package com.erp.timetable.module.faculty.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.department.entity.Department;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "faculty", indexes = {
    @Index(name = "idx_faculty_dept", columnList = "department_id"),
    @Index(name = "idx_faculty_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Faculty extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true, length = 50)
    private String employeeId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department; // Primary Department

    /** Tenant anchor — the faculty member belongs to exactly one college. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "college_id")
    private College college;

    @Column(name = "teaching_departments", length = 500)
    private String teachingDepartments; // Comma separated IDs/Names of shared departments

    @Column(name = "designation", length = 100)
    private String designation; // e.g. Professor, Associate Professor

    @Column(name = "qualification", length = 200)
    private String qualification;

    @Column(name = "specialization", length = 200)
    private String specialization;

    @Column(name = "assigned_subject_codes", length = 500)
    private String assignedSubjectCodes; // Comma separated subject codes (between 2 and 4 subjects)

    @Column(name = "max_daily_hours", nullable = false)
    @Builder.Default
    private Integer maxDailyHours = 6;

    @Column(name = "max_weekly_hours", nullable = false)
    @Builder.Default
    private Integer maxWeeklyHours = 24;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "AVAILABLE"; // AVAILABLE, BUSY, LEAVE

    @Column(name = "user_id")
    private Long userId;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
