package com.erp.timetable.module.timetable.service;

import com.erp.timetable.common.exception.BusinessException;
import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.config.security.DepartmentScopeResolver;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.security.UserPrincipal;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.timetable.dto.*;
import com.erp.timetable.module.timetable.engine.ScheduleEngine;
import com.erp.timetable.module.timetable.engine.shared.ConflictRecorderService;
import com.erp.timetable.module.timetable.engine.shared.WeeklyCapacityValidator;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimetableService {

    private final TimetableRepository timetableRepository;
    private final TimetableEntryRepository entryRepository;
    private final DepartmentRepository departmentRepository;
    private final SectionRepository sectionRepository;
    private final ScheduleEngine generatorEngine;
    private final UserRepository userRepository;
    private final FacultyRepository facultyRepository;
    private final ConflictRecorderService conflictRecorderService;
    private final WeeklyCapacityValidator weeklyCapacityValidator;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public TimetableResponse generateTimetable(GenerateTimetableRequest request) {
        Department dept = departmentRepository.findById(request.getDepartmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Department", "id", request.getDepartmentId()));

        Section section = sectionRepository.findById(request.getSectionId())
            .orElseThrow(() -> new ResourceNotFoundException("Section", "id", request.getSectionId()));

        // Timetables may only be generated for sections selected in the department's
        // per-year configuration (Department -> Academic Year -> Selected Sections).
        if (section.getAcademicYear() == null
                || section.getAcademicYear().getDepartment() == null
                || !Objects.equals(section.getAcademicYear().getDepartment().getId(), dept.getId())) {
            throw new BusinessException(
                "Section '" + section.getName() + "' does not belong to department '" + dept.getName()
                    + "'. Timetables can only be generated for sections configured under the department.");
        }

        // Timetables may not be generated for sections whose academic year is disabled
        // in the department's per-year configuration.
        if (!Boolean.TRUE.equals(section.getAcademicYear().getIsEnabled())) {
            throw new BusinessException(
                "Cannot generate timetable for section '" + section.getName() + "': the academic year '"
                    + section.getAcademicYear().getYearLabel() + "' is disabled for department '" + dept.getName()
                    + "'. Enable the year in Department Management to schedule it.");
        }

        // Find or create timetable
        Timetable timetable = timetableRepository.findBySectionIdAndSemester(request.getSectionId(), request.getSemester())
            .orElseGet(() -> Timetable.builder()
                .academicSession(request.getAcademicSession() != null ? request.getAcademicSession() : currentAcademicSession())
                .department(dept)
                .section(section)
                .semester(request.getSemester())
                .status("DRAFT")
                .build());

        if (timetable.getId() != null) {
            timetable.getEntries().clear();
            timetable.getConflicts().clear();
            timetable = timetableRepository.saveAndFlush(timetable);
        }

        // Weekly-capacity check: computed UPFRONT, before the engine runs, so an
        // over-capacity schedule is detected regardless of the engine. The
        // conflict row itself is attached AFTER the engine because the engine
        // clears stale conflicts at the start of generation.
        WeeklyCapacityValidator.CapacityReport capacityReport = weeklyCapacityValidator.validate(timetable);
        generatorEngine.generateSchedule(timetable, false);
        recordCapacityExceededConflict(timetable, capacityReport);
        Timetable saved = timetableRepository.save(timetable);
        log.info("Timetable generated: ID {} for section {}", saved.getId(), section.getName());
        return mapToResponse(saved);
    }

    @Transactional
    public TimetableResponse regenerateUnlockedSlots(Long timetableId) {
        // Remove unlocked entries from the DB before the engine re-places them,
        // otherwise fresh inserts can collide with the not-yet-flushed old rows
        // under the (timetable_id, day_of_week, time_slot_id) unique constraint.
        entryRepository.deleteUnlockedByTimetableId(timetableId);
        entityManager.flush();

        // Drop the stale managed instances so the engine's locked-entry
        // preservation never schedules orphan-removal deletes for rows that the
        // bulk delete above already removed (double-delete would fail the flush).
        entityManager.clear();

        Timetable timetable = timetableRepository.findById(timetableId)
            .orElseThrow(() -> new ResourceNotFoundException("Timetable", "id", timetableId));

        WeeklyCapacityValidator.CapacityReport capacityReport = weeklyCapacityValidator.validate(timetable);
        generatorEngine.generateSchedule(timetable, true); // Partial regenerate preserving locked slots
        recordCapacityExceededConflict(timetable, capacityReport);
        Timetable saved = timetableRepository.save(timetable);
        log.info("Timetable regenerated (unlocked slots only): ID {}", saved.getId());
        return mapToResponse(saved);
    }

    @Transactional
    public TimetableResponse toggleSlotLock(Long entryId) {
        TimetableEntry entry = entryRepository.findById(entryId)
            .orElseThrow(() -> new ResourceNotFoundException("TimetableEntry", "id", entryId));

        entry.setIsLocked(!Boolean.TRUE.equals(entry.getIsLocked()));
        entryRepository.save(entry);
        log.info("Timetable entry ID {} lock status toggled to {}", entryId, entry.getIsLocked());

        return mapToResponse(entry.getTimetable());
    }

    @Transactional(readOnly = true)
    public TimetableResponse getTimetableById(Long id) {
        Timetable timetable = timetableRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Timetable", "id", id));
        return mapToResponse(timetable);
    }

    @Transactional(readOnly = true)
    public TimetableResponse getTimetableBySectionAndSemester(Long sectionId, Integer semester) {
        Timetable timetable = timetableRepository.findBySectionIdAndSemester(sectionId, semester)
            .orElseThrow(() -> new ResourceNotFoundException("Timetable for section ID " + sectionId + ", semester " + semester + " not found"));
        return mapToResponse(timetable);
    }

    @Transactional(readOnly = true)
    public List<TimetableResponse> getTimetablesByDepartment(Long departmentId) {
        return timetableRepository.findByDepartmentId(departmentId).stream()
            .map(this::mapToResponse)
            .toList();
    }

    /**
     * Self-scoped timetables for the calling principal, derived from their
     * account (never from client-supplied ids):
     * STUDENT → own section's timetables; FACULTY → timetables containing their
     * lessons (with the ENTRIES narrowed to their own lessons); everyone else →
     * their own department's timetables.
     */
    @Transactional(readOnly = true)
    public List<TimetableResponse> getMyTimetables(UserPrincipal principal) {
        User user = userRepository.findById(principal.getId()).orElse(null);
        if (user == null) {
            return List.of();
        }

        List<Timetable> timetables;
        Long facultyOnlyId = null;
        // A department-restricted HOD is never widened: an unassigned one is
        // denied outright rather than falling through to the college-wide
        // branch below, which would expose every department's timetables.
        DepartmentScopeResolver.requireHodDepartment(user);
        if (user.hasRole(RoleName.ROLE_STUDENT)) {
            timetables = user.getSectionId() != null
                ? timetableRepository.findBySectionIdIn(List.of(user.getSectionId()))
                : List.of();
        } else if (user.hasRole(RoleName.ROLE_FACULTY)) {
            // The faculty row is resolved from the authenticated user's account
            // (User.userId -> Faculty.userId) — never from a client-supplied id.
            Faculty faculty = facultyRepository.findByUserId(user.getId()).orElse(null);
            timetables = faculty != null
                ? timetableRepository.findByFacultyId(faculty.getId())
                : List.of();
            if (faculty != null) {
                facultyOnlyId = faculty.getId();
            }
        } else if (user.getDepartment() != null) {
            timetables = timetableRepository.findByDepartmentId(user.getDepartment().getId());
        } else if (user.getCollege() != null) {
            // COLLEGE_ADMIN (or any college-scoped account) without a linked
            // department sees the timetables of every department in their college.
            List<Long> deptIds = departmentRepository.findByCollege_Id(user.getCollege().getId()).stream()
                .map(Department::getId).toList();
            timetables = deptIds.isEmpty() ? List.of() : timetableRepository.findByDepartmentIdIn(deptIds);
        } else {
            timetables = List.of();
        }

        final Long scopeId = facultyOnlyId;
        return timetables.stream()
            .map(t -> mapToResponse(t, scopeId))
            .filter(Objects::nonNull)
            .toList();
    }

    @Transactional
    public void deleteTimetable(Long id) {
        Timetable timetable = timetableRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Timetable", "id", id));
        timetableRepository.delete(timetable);
        log.info("Timetable deleted: ID {}", id);
    }

    /**
     * Derives the current academic session from the calendar instead of a stale
     * hardcoded label: July–December is the ODD (current-year → next-year)
     * semester, January–June the EVEN (previous-year → current-year) one.
     */
    private String currentAcademicSession() {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        if (now.getMonthValue() >= 7) {
            return year + "-" + (year + 1) + " ODD";
        }
        return (year - 1) + "-" + year + " EVEN";
    }

    /**
     * Weekly-capacity validation at the shared generation entry point.
     *
     * <p>The check itself is an UPFRONT calculation (see the callers) run before
     * the engine schedules anything, so an over-capacity curriculum cannot be
     * silently scheduled to completion by either engine. The conflict row is
     * attached after the engine completes — before persistence — because the
     * engine clears stale conflicts at the start of generation.
     *
     * <p>If the section/semester curriculum's total weekly demand
     * (theory + practical hours across every applicable subject) exceeds the
     * configured weekly capacity (working days × non-break teaching slots), a
     * {@code CAPACITY_EXCEEDED} conflict is added through the existing conflict
     * model and the reported {@code conflictCount} is bumped to stay in sync.
     * The scheduling engines' clash constraints still prevent any slot from
     * being double-booked, so the timetable is marked — never silently
     * pretended to be complete.
     */
    private void recordCapacityExceededConflict(Timetable timetable, WeeklyCapacityValidator.CapacityReport report) {
        if (!report.exceeded()) {
            return;
        }
        log.warn("Capacity check failed for timetable for section {} semester {}: {}",
            timetable.getSection() != null ? timetable.getSection().getName() : "?",
            timetable.getSemester(), report.message());
        if (conflictRecorderService.addConflict(timetable,
                WeeklyCapacityValidator.CAPACITY_EXCEEDED, report.message(), "HIGH")) {
            timetable.setConflictCount((timetable.getConflictCount() == null ? 0 : timetable.getConflictCount()) + 1);
        }
    }

    private TimetableResponse mapToResponse(Timetable t) {
        return mapToResponse(t, null);
    }

    private TimetableResponse mapToResponse(Timetable t, Long facultyOnlyId) {
        List<TimetableEntry> entries = t.getEntries() == null ? List.of() : t.getEntries();
        if (facultyOnlyId != null) {
            entries = entries.stream()
                .filter(e -> e.getFaculty() != null && Objects.equals(e.getFaculty().getId(), facultyOnlyId))
                .toList();
            if (entries.isEmpty()) {
                return null;
            }
        }
        List<TimetableEntryDto> entryDtos = entries.stream().map(e -> TimetableEntryDto.builder()
                .id(e.getId())
                .dayOfWeek(e.getDayOfWeek())
                .timeSlotId(e.getTimeSlot().getId())
                .timeSlotLabel(e.getTimeSlot().getSlotLabel())
                .timeSlotTime(e.getTimeSlot().getStartTime() + " - " + e.getTimeSlot().getEndTime())
                .subjectId(e.getSubject().getId())
                .subjectCode(e.getSubject().getSubjectCode())
                .subjectName(e.getSubject().getSubjectName())
                .subjectType(e.getSubject().getSubjectType())
                .facultyId(e.getFaculty().getId())
                .facultyName(e.getFaculty().getFullName())
                .classroomId(e.getClassroom().getId())
                .roomNumber(e.getClassroom().getRoomNumber())
                .roomName(e.getClassroom().getRoomName())
                .isLocked(Boolean.TRUE.equals(e.getIsLocked()))
                .isLab(Boolean.TRUE.equals(e.getIsLab()))
                .build()).toList();

        List<TimetableConflictDto> conflictDtos = t.getConflicts() == null ? List.of() :
            t.getConflicts().stream().map(c -> TimetableConflictDto.builder()
                .id(c.getId())
                .conflictType(c.getConflictType())
                .description(c.getDescription())
                .severity(c.getSeverity())
                .build()).toList();

        AcademicYear year = t.getSection() != null ? t.getSection().getAcademicYear() : null;

        return TimetableResponse.builder()
            .id(t.getId())
            .academicSession(t.getAcademicSession())
            .departmentId(t.getDepartment().getId())
            .departmentName(t.getDepartment().getName())
            // Read through the existing timetable -> section -> academicYear
            // relationship (the same path ReportService already uses) so a caller
            // can render the class as DEPARTMENT-YEAR-SECTION without the frontend
            // having to resolve the year from a separate departments lookup.
            .academicYearId(year != null ? year.getId() : null)
            .yearLabel(year != null ? year.getYearLabel() : null)
            .sectionId(t.getSection().getId())
            .sectionName(t.getSection().getName())
            .semester(t.getSemester())
            .status(t.getStatus())
            .conflictCount(t.getConflictCount())
            .optimizationScore(t.getOptimizationScore() != null ? t.getOptimizationScore() : 100)
            .createdAt(t.getCreatedAt() != null ? String.valueOf(t.getCreatedAt()) : null)
            .entries(entryDtos)
            .conflicts(conflictDtos)
            .build();
    }
}
