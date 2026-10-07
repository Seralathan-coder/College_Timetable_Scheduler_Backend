package com.erp.timetable.module.timetable.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "timetables", indexes = {
    @Index(name = "idx_timetable_dept_section", columnList = "department_id, section_id, semester")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Timetable extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academic_session", nullable = false, length = 50)
    private String academicSession; // e.g. "2025-2026 EVEN"

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(name = "semester", nullable = false)
    private Integer semester;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT"; // DRAFT, GENERATED, PUBLISHED, ARCHIVED

    @Column(name = "conflict_count", nullable = false)
    @Builder.Default
    private Integer conflictCount = 0;

    @Column(name = "optimization_score", nullable = false)
    @Builder.Default
    private Integer optimizationScore = 100;

    @OneToMany(mappedBy = "timetable", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TimetableEntry> entries = new ArrayList<>();

    @OneToMany(mappedBy = "timetable", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TimetableConflict> conflicts = new ArrayList<>();

    public void addEntry(TimetableEntry entry) {
        entries.add(entry);
        entry.setTimetable(this);
    }

    public void addConflict(TimetableConflict conflict) {
        conflicts.add(conflict);
        conflict.setTimetable(this);
    }

    public void clearEntriesExceptLocked() {
        entries.removeIf(entry -> Boolean.FALSE.equals(entry.getIsLocked()));
    }
}
