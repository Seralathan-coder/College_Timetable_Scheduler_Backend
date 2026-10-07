package com.erp.timetable.module.availability.entity;

import com.erp.timetable.module.faculty.entity.Faculty;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "faculty_availability", uniqueConstraints = {
    @UniqueConstraint(name = "uk_faculty_day_slot", columnNames = {"faculty_id", "day_of_week", "time_slot_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacultyAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "faculty_id", nullable = false)
    private Faculty faculty;

    @Column(name = "day_of_week", nullable = false, length = 15)
    private String dayOfWeek; // MON, TUE, WED, THU, FRI, SAT

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "time_slot_id", nullable = false)
    private TimeSlot timeSlot;

    @Column(name = "slot_type", nullable = false, length = 20)
    @Builder.Default
    private String slotType = "AVAILABLE"; // PREFERRED, AVAILABLE, BLOCKED, BUSY
}
