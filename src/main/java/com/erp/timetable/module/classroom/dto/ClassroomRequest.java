package com.erp.timetable.module.classroom.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassroomRequest {

    @NotBlank(message = "Room number is required")
    @Size(max = 20, message = "Room number cannot exceed 20 characters")
    private String roomNumber;

    @Size(max = 100, message = "Room name cannot exceed 100 characters")
    private String roomName;

    @Size(max = 100, message = "Building cannot exceed 100 characters")
    private String building;
    private Long departmentId;
    private Long academicYearId;
    private Long sectionId;

    @NotBlank(message = "Room type is required")
    @Size(max = 30, message = "Room type cannot exceed 30 characters")
    @Builder.Default
    private String roomType = "LECTURE_HALL";

    @NotNull(message = "Capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer capacity;

    private Integer floor;

    @Size(max = 20, message = "Status cannot exceed 20 characters")
    @Builder.Default
    private String status = "AVAILABLE";
}
