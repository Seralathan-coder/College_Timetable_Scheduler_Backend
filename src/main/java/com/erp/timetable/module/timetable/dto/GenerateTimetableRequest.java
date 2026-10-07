package com.erp.timetable.module.timetable.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateTimetableRequest {

    @NotNull(message = "Department ID is required")
    private Long departmentId;

    private Long academicYearId;

    @NotNull(message = "Section ID is required")
    private Long sectionId;

    @NotNull(message = "Semester is required")
    private Integer semester;

    /**
     * Optional academic session label. When null/absent,
     * {@link com.erp.timetable.module.timetable.service.TimetableService}
     * derives the current session from the calendar instead of a hardcoded label.
     */
    private String academicSession;
}
