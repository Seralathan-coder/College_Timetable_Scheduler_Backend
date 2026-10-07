package com.erp.timetable.module.subject.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.faculty.entity.Faculty;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subjects", indexes = {
    @Index(name = "idx_subjects_code", columnList = "subject_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subject extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_code", nullable = false, length = 30)
    private String subjectCode;

    @Column(name = "subject_name", nullable = false, length = 200)
    private String subjectName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "section_id")
    private Section section;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_faculty_id")
    private Faculty assignedFaculty;

    @Column(name = "semester", nullable = false)
    private Integer semester;

    @Column(name = "credits", nullable = false)
    @Builder.Default
    private Integer credits = 3;

    @Column(name = "theory_hours", nullable = false)
    @Builder.Default
    private Integer theoryHours = 3;

    @Column(name = "practical_hours", nullable = false)
    @Builder.Default
    private Integer practicalHours = 0;

    @Column(name = "subject_type", nullable = false, length = 20)
    @Builder.Default
    private String subjectType = "THEORY"; // THEORY, LAB, GAME, OTHER

    @Column(name = "total_semester_hours", nullable = false)
    @Builder.Default
    private Integer totalSemesterHours = 45;

    @Column(name = "teaching_weeks", nullable = false)
    @Builder.Default
    private Integer teachingWeeks = 15;

    /**
     * Number of CONSECUTIVE periods this subject occupies each time it is placed.
     * 1 = single-period sessions spread across days (default). 2 = a double-period
     * block back-to-back on the same day. Only affects THEORY scheduling —
     * LAB subjects always schedule their full practical hours as one continuous block.
     */
    @Column(name = "session_block_size", nullable = false)
    @Builder.Default
    private Integer sessionBlockSize = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public int getCalculatedWeeklyHours() {
        int theory = theoryHours != null ? theoryHours : 0;
        int practical = practicalHours != null ? practicalHours : 0;
        return theory + practical;
    }
}
