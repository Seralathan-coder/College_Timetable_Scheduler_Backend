package com.erp.timetable.module.report.service;

import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {

    /** Canonical working-day ordering for the Faculty Schedule Report: MON=1 ... SAT=6. */
    private static final Map<String, Integer> DAY_ORDER = Map.of(
        "MON", 1, "TUE", 2, "WED", 3, "THU", 4, "FRI", 5, "SAT", 6);

    private final FacultyRepository facultyRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableEntryRepository entryRepository;
    private final TenantContext tenantContext;
    private final DepartmentScopeResolver departmentScopeResolver;

    @Transactional(readOnly = true)
    public Map<String, Object> getFacultyReport(Long facultyId) {
        Faculty faculty = facultyRepository.findById(facultyId).orElse(null);
        List<TimetableEntry> entries = entryRepository.findByFacultyId(facultyId);

        Map<String, Object> report = new HashMap<>();
        report.put("faculty", faculty != null ? faculty.getFullName() : "Unknown");
        report.put("employeeId", faculty != null ? faculty.getEmployeeId() : "N/A");
        report.put("assignedPeriodsCount", entries.size());
        report.put("entries", entries.stream()
            .sorted(Comparator
                .comparingInt((TimetableEntry e) -> DAY_ORDER.getOrDefault(e.getDayOfWeek(), Integer.MAX_VALUE))
                .thenComparingInt(e -> e.getTimeSlot() != null ? e.getTimeSlot().getSlotOrder() : Integer.MAX_VALUE))
            .<Map<String, Object>>map(e -> Map.of(
                "day", e.getDayOfWeek(),
                "timeSlotId", e.getTimeSlot() != null ? e.getTimeSlot().getId() : 0L,
                "subjectCode", e.getSubject() != null ? e.getSubject().getSubjectCode() : "N/A",
                "subjectName", e.getSubject() != null ? e.getSubject().getSubjectName() : "N/A",
                "isLab", e.getIsLab() != null && e.getIsLab(),
                "sectionName", e.getSection() != null ? e.getSection().getName() : "N/A",
                "yearLabel", e.getSection() != null && e.getSection().getAcademicYear() != null
                    ? e.getSection().getAcademicYear().getYearLabel() : "N/A",
                "roomNumber", e.getClassroom() != null ? e.getClassroom().getRoomNumber() : "N/A"
            ))
            .toList());
        return report;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRoomUtilizationReport() {
        User caller = tenantContext.currentUser();
        boolean global = caller == null || caller.hasRole(RoleName.ROLE_SUPER_ADMIN);
        // An HOD's room utilization describes their own department. An HOD with
        // no department is denied by the resolver rather than widened to the
        // whole college.
        Long deptId = departmentScopeResolver.restrictedDepartmentId();
        List<Classroom> rooms = global
                ? classroomRepository.findAll()
                : deptId != null
                    ? classroomRepository.findByDepartment_Id(deptId)
                    : (caller.getCollege() != null
                        ? classroomRepository.findByDepartment_CollegeId(caller.getCollege().getId())
                        : List.of());
        Map<String, Object> report = new HashMap<>();
        report.put("totalRooms", rooms.size());
        report.put("availableRooms", rooms.stream().filter(r -> "AVAILABLE".equals(r.getStatus())).count());
        report.put("reservedRooms", rooms.stream().filter(r -> "RESERVED".equals(r.getStatus())).count());
        report.put("maintenanceRooms", rooms.stream().filter(r -> "MAINTENANCE".equals(r.getStatus())).count());
        return report;
    }
}
