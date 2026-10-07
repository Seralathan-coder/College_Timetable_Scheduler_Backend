package com.erp.timetable.module.timetable.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "timetable_conflicts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "timetable_id", nullable = false)
    private Timetable timetable;

    @Column(name = "conflict_type", nullable = false, length = 50)
    private String conflictType; // FACULTY_CLASH, ROOM_CLASH, SECTION_CLASH, CAPACITY_EXCEEDED, WORKLOAD_EXCEEDED

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private String severity = "HIGH"; // HIGH, MEDIUM, LOW

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id_1")
    private TimetableEntry entry1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id_2")
    private TimetableEntry entry2;

    @Column(name = "is_resolved", nullable = false)
    @Builder.Default
    private Boolean isResolved = false;
}
