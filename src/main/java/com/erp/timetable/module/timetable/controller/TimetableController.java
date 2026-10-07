package com.erp.timetable.module.timetable.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.timetable.dto.GenerateTimetableRequest;
import com.erp.timetable.module.timetable.dto.TimetableResponse;
import com.erp.timetable.module.timetable.service.TimetableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/timetable")
@RequiredArgsConstructor
public class TimetableController {

    private final TimetableService timetableService;

    @PostMapping("/generate")
    @PreAuthorize("@rbacGuard.canGenerateTimetable(authentication, #request.departmentId)")
    public ResponseEntity<ApiResponse<TimetableResponse>> generateTimetable(
            @Valid @RequestBody GenerateTimetableRequest request) {
        TimetableResponse response = timetableService.generateTimetable(request);
        return ResponseEntity.ok(ApiResponse.success("Timetable generated successfully", response));
    }

    @PostMapping("/{id}/regenerate-unlocked")
    @PreAuthorize("@rbacGuard.canManageTimetable(authentication, #id)")
    public ResponseEntity<ApiResponse<TimetableResponse>> regenerateUnlockedSlots(@PathVariable Long id) {
        TimetableResponse response = timetableService.regenerateUnlockedSlots(id);
        return ResponseEntity.ok(ApiResponse.success("Unlocked slots regenerated successfully", response));
    }

    @PatchMapping("/entries/{entryId}/lock")
    @PreAuthorize("@rbacGuard.canManageTimetableEntry(authentication, #entryId)")
    public ResponseEntity<ApiResponse<TimetableResponse>> toggleSlotLock(@PathVariable Long entryId) {
        TimetableResponse response = timetableService.toggleSlotLock(entryId);
        return ResponseEntity.ok(ApiResponse.success("Slot lock status updated", response));
    }

    /**
     * Self-scoped timetable for the current user:
     * STUDENT → their own section's timetables;
     * FACULTY → the timetables that contain their own lessons;
     * HOD / ADMIN / EXAM_COORDINATOR → their department's timetables.
     */
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getMyTimetable(
            @AuthenticationPrincipal UserPrincipal principal) {
        List<TimetableResponse> response = timetableService.getMyTimetables(principal);
        return ResponseEntity.ok(ApiResponse.success("My timetables retrieved", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbacGuard.canViewTimetable(authentication, #id)")
    public ResponseEntity<ApiResponse<TimetableResponse>> getTimetableById(@PathVariable Long id) {
        TimetableResponse response = timetableService.getTimetableById(id);
        return ResponseEntity.ok(ApiResponse.success("Timetable retrieved", response));
    }

    @GetMapping("/section/{sectionId}/semester/{semester}")
    @PreAuthorize("@rbacGuard.canViewSectionTimetable(authentication, #sectionId)")
    public ResponseEntity<ApiResponse<TimetableResponse>> getTimetableBySectionAndSemester(
            @PathVariable Long sectionId,
            @PathVariable Integer semester) {
        TimetableResponse response = timetableService.getTimetableBySectionAndSemester(sectionId, semester);
        return ResponseEntity.ok(ApiResponse.success("Timetable retrieved", response));
    }

    @GetMapping("/department/{departmentId}")
    @PreAuthorize("@rbacGuard.canViewDepartment(authentication, #departmentId)")
    public ResponseEntity<ApiResponse<List<TimetableResponse>>> getTimetablesByDepartment(
            @PathVariable Long departmentId) {
        List<TimetableResponse> response = timetableService.getTimetablesByDepartment(departmentId);
        return ResponseEntity.ok(ApiResponse.success("Department timetables retrieved", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@rbacGuard.canDeleteTimetable(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> deleteTimetable(@PathVariable Long id) {
        timetableService.deleteTimetable(id);
        return ResponseEntity.ok(ApiResponse.success("Timetable deleted successfully", null));
    }
}
