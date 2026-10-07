package com.erp.timetable.module.timetable.planning.model;

import lombok.*;

import java.time.LocalTime;

/**
 * A (day, time slot) window as a Timefold problem fact and a value range source.
 * The solver assigns one window per lesson as a planning variable, so this type
 * also backs the {@code timeSlotRange} value range provider.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlannableTimeSlot {

    private Long timeSlotId;
    private String dayOfWeek; // MON, TUE, WED, THU, FRI, SAT
    private Integer slotOrder;
    private LocalTime startTime;
    private LocalTime endTime;
    private boolean isBreak;
    private String slotLabel;
}
