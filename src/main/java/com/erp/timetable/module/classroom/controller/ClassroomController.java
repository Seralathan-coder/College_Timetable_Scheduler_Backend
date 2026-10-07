package com.erp.timetable.module.classroom.controller;

import com.erp.timetable.common.response.ApiResponse;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.classroom.dto.ClassroomRequest;
import com.erp.timetable.module.classroom.dto.ClassroomResponse;
import com.erp.timetable.module.classroom.service.ClassroomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/classrooms")
@RequiredArgsConstructor
@Tag(name = "Classroom Management", description = "CRUD endpoints for classrooms and labs")
public class ClassroomController {

    private final ClassroomService classroomService;

    @PostMapping
    @PreAuthorize("@rbacGuard.canManageDepartment(authentication, #request.departmentId)")
    @Operation(summary = "Create a new classroom or lab")
    public ResponseEntity<ApiResponse<ClassroomResponse>> createClassroom(
            @Valid @RequestBody ClassroomRequest request) {
        ClassroomResponse response = classroomService.createClassroom(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Classroom created successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COLLEGE_ADMIN', 'HOD', 'EXAM_COORDINATOR')")
    @Operation(summary = "Get paginated list of classrooms (college-scoped)")
    public ResponseEntity<ApiResponse<PageResponse<ClassroomResponse>>> getClassrooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String roomType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "roomNumber,asc") String sort) {
        PageResponse<ClassroomResponse> pageResponse = classroomService.getClassrooms(page, size, search, roomType, status, sort);
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@rbacGuard.canViewClassroom(authentication, #id)")
    @Operation(summary = "Get classroom by ID")
    public ResponseEntity<ApiResponse<ClassroomResponse>> getClassroomById(@PathVariable Long id) {
        ClassroomResponse response = classroomService.getClassroomById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@rbacGuard.canManageClassroom(authentication, #id)")
    @Operation(summary = "Update classroom details")
    public ResponseEntity<ApiResponse<ClassroomResponse>> updateClassroom(
            @PathVariable Long id,
            @Valid @RequestBody ClassroomRequest request) {
        ClassroomResponse response = classroomService.updateClassroom(id, request);
        return ResponseEntity.ok(ApiResponse.success("Classroom updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@rbacGuard.canDeleteClassroom(authentication, #id)")
    @Operation(summary = "Delete classroom (SUPER_ADMIN or own-college COLLEGE_ADMIN)")
    public ResponseEntity<ApiResponse<Void>> deleteClassroom(@PathVariable Long id) {
        classroomService.deleteClassroom(id);
        return ResponseEntity.ok(ApiResponse.successMessage("Classroom deleted successfully"));
    }
}
