package com.erp.timetable.module.faculty.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
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
public class FacultyRequest {

    @NotBlank(message = "Employee ID is required")
    @Size(max = 50, message = "Employee ID cannot exceed 50 characters")
    private String employeeId;

    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name cannot exceed 100 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name cannot exceed 100 characters")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email cannot exceed 255 characters")
    private String email;

    @Size(max = 20, message = "Phone cannot exceed 20 characters")
    private String phone;
    private Long departmentId;

    @Size(max = 100, message = "Designation cannot exceed 100 characters")
    private String designation;

    @Size(max = 200, message = "Qualification cannot exceed 200 characters")
    private String qualification;

    @Size(max = 200, message = "Specialization cannot exceed 200 characters")
    private String specialization;

    @NotNull(message = "Max daily hours is required")
    @Min(value = 1, message = "Max daily hours must be at least 1")
    @Max(value = 24, message = "Max daily hours cannot exceed 24")
    @Builder.Default
    private Integer maxDailyHours = 6;

    @NotNull(message = "Max weekly hours is required")
    @Min(value = 1, message = "Max weekly hours must be at least 1")
    @Max(value = 100, message = "Max weekly hours cannot exceed 100")
    @Builder.Default
    private Integer maxWeeklyHours = 24;

    @Size(max = 20, message = "Status cannot exceed 20 characters")
    @Builder.Default
    private String status = "AVAILABLE";

    /**
     * Optional faculty login credentials. When provided on create, the system
     * provisions a ROLE_FACULTY user linked to this faculty record via
     * {@code Faculty.userId}. Ignored on update and when blank.
     */
    @Size(max = 100, message = "Faculty Login ID cannot exceed 100 characters")
    private String username;

    @Size(min = 6, max = 72, message = "Faculty Password must be between 6 and 72 characters")
    private String password;
}
