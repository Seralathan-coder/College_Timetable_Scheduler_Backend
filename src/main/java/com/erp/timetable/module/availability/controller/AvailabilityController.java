package com.erp.timetable.module.availability.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.availability.dto.AvailabilityDto;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/availability")
@RequiredArgsConstructor
@Tag(name = "Faculty Availability", description = "Endpoints for faculty time preferences")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    @GetMapping("/time-slots")
    @Operation(summary = "Get all schedule period time slots")
    public ResponseEntity<ApiResponse<List<TimeSlot>>> getTimeSlots() {
        return ResponseEntity.ok(ApiResponse.success(availabilityService.getAllTimeSlots()));
    }

    @GetMapping("/faculty/{facultyId}")
    @PreAuthorize("@rbacGuard.canReadAvailability(authentication, #facultyId)")
    @Operation(summary = "Get availability matrix for a faculty member (own college / HOD own dept / FACULTY self)")
    public ResponseEntity<ApiResponse<List<AvailabilityDto>>> getFacultyAvailability(@PathVariable Long facultyId) {
        return ResponseEntity.ok(ApiResponse.success(availabilityService.getFacultyAvailability(facultyId)));
    }

    @PostMapping("/faculty/{facultyId}")
    @PreAuthorize("@rbacGuard.canSaveAvailability(authentication, #facultyId)")
    @Operation(summary = "Save faculty availability matrix")
    public ResponseEntity<ApiResponse<List<AvailabilityDto>>> saveFacultyAvailability(
            @PathVariable Long facultyId,
            @RequestBody List<AvailabilityDto> availabilityList) {
        List<AvailabilityDto> response = availabilityService.saveFacultyAvailability(facultyId, availabilityList);
        return ResponseEntity.ok(ApiResponse.success("Availability saved successfully", response));
    }
}
