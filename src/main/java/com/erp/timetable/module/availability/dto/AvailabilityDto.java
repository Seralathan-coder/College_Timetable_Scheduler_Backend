package com.erp.timetable.module.availability.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilityDto {
    private Long id;
    private Long facultyId;
    private String dayOfWeek;
    private Long timeSlotId;
    private Integer slotOrder;
    private String status; // PREFERRED, AVAILABLE, BLOCKED
}
