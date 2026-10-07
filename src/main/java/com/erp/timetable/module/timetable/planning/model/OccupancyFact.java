package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

/**
 * A cross-timetable occupancy claim as a Timefold problem fact (Phase 7).
 *
 * <p>Encodes that a faculty member and/or a room is already occupied for a
 * specific (day, time slot) combination by another timetable. Facts are derived
 * from the {@code timetable_entries} of every timetable <em>except</em> the one
 * being generated, mirroring Greedy's whole-college occupancy context without
 * JPA coupling. One fact is produced per foreign entry and carries both the
 * entry's faculty and room ids, so a lesson that clashes on faculty only, room
 * only, or both counts as exactly one violation against that entry.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OccupancyFact {

    private Long facultyId;
    private Long roomId;
    private String dayOfWeek;
    private Long timeSlotId;
}
