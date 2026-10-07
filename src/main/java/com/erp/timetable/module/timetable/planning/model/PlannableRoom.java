package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

/**
 * A classroom as a Timefold problem fact and a value range source. The solver
 * assigns one room per lesson as a planning variable, so this type also backs
 * the {@code roomRange} value range provider.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlannableRoom {

    private Long roomId;
    private String roomNumber;
    private String roomName;
    private String building;
    private String roomType; // LECTURE_HALL, LAB, SEMINAR_ROOM, AUDITORIUM
    private Integer capacity;
    private Long departmentId;
    private Long academicYearId;
    private Long sectionId;
}
