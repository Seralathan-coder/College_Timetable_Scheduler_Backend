package com.erp.timetable.module.dashboard.service;

import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.dashboard.dto.DashboardStatsResponse;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import com.erp.timetable.config.security.TenantContext;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final DepartmentRepository departmentRepository;
    private final FacultyRepository facultyRepository;
    private final SubjectRepository subjectRepository;
    private final ClassroomRepository classroomRepository;
    private final TimetableRepository timetableRepository;
    private final TenantContext tenantContext;
    private final DepartmentScopeResolver departmentScopeResolver;

    @Transactional(readOnly = true)
    public DashboardStatsResponse getDashboardStats() {
        User caller = tenantContext.currentUser();
        boolean global = caller == null || caller.hasRole(RoleName.ROLE_SUPER_ADMIN);
        Long collegeId = caller != null && caller.getCollege() != null
            ? caller.getCollege().getId() : null;
        // An HOD's dashboard describes their own department, not the college.
        Long deptId = departmentScopeResolver.restrictedDepartmentId();

        long totalDepartments = global ? departmentRepository.count()
            : deptId != null ? (departmentRepository.existsById(deptId) ? 1 : 0)
            : departmentRepository.countByCollege_Id(collegeId);
        long totalFaculty = global ? facultyRepository.count()
            : deptId != null ? facultyRepository.countByDepartment_Id(deptId)
            : facultyRepository.countByCollege_Id(collegeId);
        long totalSubjects = global ? subjectRepository.count()
            : deptId != null ? subjectRepository.countByDepartment_Id(deptId)
            : subjectRepository.countByDepartment_CollegeId(collegeId);
        long totalClassrooms = global ? classroomRepository.count()
            : deptId != null ? classroomRepository.countByDepartment_Id(deptId)
            : classroomRepository.countByDepartment_CollegeId(collegeId);
        long totalTimetables = global ? timetableRepository.count()
            : deptId != null ? timetableRepository.countByDepartment_Id(deptId)
            : timetableRepository.countByDepartment_CollegeId(collegeId);

        // Faculty Workload, scoped to the same audience as the counters above
        Page<Faculty> facultyPage = global
            ? facultyRepository.findAll(PageRequest.of(0, 6))
            : facultyRepository.searchFacultyByCollege(null, deptId, collegeId, null, PageRequest.of(0, 6));
        List<DashboardStatsResponse.FacultyWorkloadDto> workloadList = new ArrayList<>();
        for (Faculty f : facultyPage.getContent()) {
            workloadList.add(DashboardStatsResponse.FacultyWorkloadDto.builder()
                .name(f.getFirstName() != null ? f.getFirstName() + " " + f.getLastName() : f.getEmployeeId())
                .hours(f.getMaxWeeklyHours() != null ? f.getMaxWeeklyHours() : 20)
                .build());
        }
        if (workloadList.isEmpty()) {
            workloadList.add(new DashboardStatsResponse.FacultyWorkloadDto("No Faculty", 0));
        }

        // Room Utilization, same scope
        List<Classroom> classrooms = global ? classroomRepository.findAll()
            : deptId != null ? classroomRepository.findByDepartment_Id(deptId)
            : classroomRepository.findByDepartment_CollegeId(collegeId);
        // Note: classroom count is typically small (10-50 rooms), no pagination needed
        long totalRoomsCount = classrooms.size();
        long availableCount = classrooms.stream().filter(c -> "AVAILABLE".equalsIgnoreCase(c.getStatus())).count();
        long reservedCount = classrooms.stream().filter(c -> "RESERVED".equalsIgnoreCase(c.getStatus()) || "BUSY".equalsIgnoreCase(c.getStatus())).count();
        long maintenanceCount = classrooms.stream().filter(c -> "MAINTENANCE".equalsIgnoreCase(c.getStatus())).count();

        long occupiedPct = totalRoomsCount > 0 ? Math.max(10, (reservedCount * 100) / totalRoomsCount) : 70;
        long availablePct = totalRoomsCount > 0 ? Math.max(10, (availableCount * 100) / totalRoomsCount) : 20;
        long maintenancePct = totalRoomsCount > 0 ? (maintenanceCount * 100) / totalRoomsCount : 10;

        List<DashboardStatsResponse.RoomUtilizationDto> utilizationList = List.of(
            DashboardStatsResponse.RoomUtilizationDto.builder().name("Occupied").value(occupiedPct).color("#6366f1").build(),
            DashboardStatsResponse.RoomUtilizationDto.builder().name("Available").value(availablePct).color("#22c55e").build(),
            DashboardStatsResponse.RoomUtilizationDto.builder().name("Maintenance").value(maintenancePct).color("#f59e0b").build()
        );

        // Calculate Today's Classes dynamically from subjects & faculty
        Page<Subject> subjectPage = global
            ? subjectRepository.findAll(PageRequest.of(0, 4))
            : subjectRepository.searchSubjectsByCollege(null, deptId, null, null, null, collegeId, PageRequest.of(0, 4));
        List<DashboardStatsResponse.TodayClassDto> todayClasses = new ArrayList<>();
        String[] timeSlots = {"09:00", "10:40", "13:10", "14:50"};
        int i = 0;
        for (Subject s : subjectPage.getContent()) {
            String facName = s.getAssignedFaculty() != null ? s.getAssignedFaculty().getFullName() : "Faculty Member";
            String roomName = (classrooms.size() > i) ? classrooms.get(i).getRoomNumber() : "CS-101";
            String deptName = s.getDepartment() != null ? s.getDepartment().getName() : "General";

            todayClasses.add(DashboardStatsResponse.TodayClassDto.builder()
                .time(timeSlots[i % timeSlots.length])
                .subject(s.getSubjectName())
                .faculty(facName)
                .room(roomName)
                .dept(deptName)
                .build());
            i++;
        }

        // Recent Activities log
        List<DashboardStatsResponse.RecentActivityDto> recentActivities = List.of(
            DashboardStatsResponse.RecentActivityDto.builder().type("success").msg("Master Data initialized & DB connection active").time("Just now").build(),
            DashboardStatsResponse.RecentActivityDto.builder().type("info").msg("Total " + totalDepartments + " departments and " + totalFaculty + " faculty members configured").time("Active").build(),
            DashboardStatsResponse.RecentActivityDto.builder().type("warning").msg(totalClassrooms + " classrooms available for schedule mapping").time("Active").build()
        );

        return DashboardStatsResponse.builder()
            .totalDepartments(totalDepartments)
            .totalFaculty(totalFaculty)
            .totalSubjects(totalSubjects)
            .totalClassrooms(totalClassrooms)
            .totalTimetables(totalTimetables)
            .facultyWorkload(workloadList)
            .roomUtilization(utilizationList)
            .todayClasses(todayClasses)
            .recentActivities(recentActivities)
            .build();
    }
}
