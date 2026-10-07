package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

/**
 * A faculty availability constraint as a Timefold problem fact. Encodes that a
 * faculty member is (un)available for a specific (day, time slot) combination,
 * mirroring {@code FacultyAvailability} without JPA coupling.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityFact {

    private Long facultyId;
    private String dayOfWeek;
    private Long timeSlotId;
    private String slotType; // PREFERRED, AVAILABLE, BLOCKED, BUSY
}
