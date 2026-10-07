package com.erp.timetable.module.classroom.entity;

import com.erp.timetable.common.audit.AuditableEntity;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "classrooms", uniqueConstraints = {
    @UniqueConstraint(name = "uk_building_room", columnNames = {"building", "room_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Classroom extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_number", nullable = false, length = 20)
    private String roomNumber;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(name = "building", length = 100)
    private String building;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academic_year_id")
    private AcademicYear academicYear;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private Section section;

    @Column(name = "room_type", nullable = false, length = 30)
    @Builder.Default
    private String roomType = "LECTURE_HALL"; // LECTURE_HALL, LAB, SEMINAR_ROOM, AUDITORIUM

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "AVAILABLE"; // AVAILABLE, RESERVED, MAINTENANCE
}
