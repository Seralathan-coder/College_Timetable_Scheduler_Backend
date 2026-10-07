package com.erp.timetable.module.availability.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

@Entity
@Table(name = "time_slots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimeSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_order", nullable = false, unique = true)
    private Integer slotOrder; // 1, 2, 3...

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_break", nullable = false)
    @Builder.Default
    private Boolean isBreak = false;

    @Column(name = "slot_label", length = 50)
    private String slotLabel; // e.g., "Period 1", "Lunch Break"
}
