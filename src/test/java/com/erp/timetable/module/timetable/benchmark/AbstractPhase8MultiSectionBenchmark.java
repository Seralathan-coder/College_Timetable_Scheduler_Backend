package com.erp.timetable.module.timetable.benchmark;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;

import java.time.Duration;

/**
 * Phase 8 — Scenario B: multi-section generation on the seeded CSE master data.
 * Section A (the seeded CS201/CS202/CS205L curriculum) is generated first, then
 * section B (an in-test curriculum sharing faculty F1/F2 and the CSE lecture
 * halls) is generated while A's timetable already exists. The harness measures
 * B's generation and re-counts cross-timetable clashes against A's entries.
 * Concrete subclasses pin one engine.
 */
public abstract class AbstractPhase8MultiSectionBenchmark extends AbstractPhase8Benchmark {

    private static final String SESSION = "2025-2026 EVEN";
    private static final int B_REQUESTED = 9; // CS301/CS302/CS303 → 3 theory periods each

    @Autowired
    protected DepartmentRepository departmentRepository;

    @Autowired
    protected SectionRepository sectionRepository;

    @Autowired
    protected FacultyRepository facultyRepository;

    @Autowired
    protected SubjectRepository subjectRepository;

    @Override
    protected String scenarioName() {
        return "B";
    }

    /** Keep the seeded master data (do not load the TT1 dump). */
    @Override
    protected void loadDataset() {
    }

    @Override
    protected RunResult runScenarioOnce(CapturedOutput output) throws Exception {
        Setup setup = prepareSectionsAndSubjects();

        String beforeA = output.getAll();
        long startA = System.nanoTime();
        JsonNode first = postGenerateExpectSuccess(setup.cseId(), setup.secA().getId(), 3, SESSION);
        long apiMsA = Duration.ofNanos(System.nanoTime() - startA).toMillis();
        String logsA = logsSince(output, beforeA);

        String beforeB = output.getAll();
        long startB = System.nanoTime();
        JsonNode second = postGenerateExpectSuccess(setup.cseId(), setup.secB().getId(), 3, SESSION);
        long apiMsB = Duration.ofNanos(System.nanoTime() - startB).toMillis();
        String logsB = logsSince(output, beforeB);

        int assignedB = second.get("entries").size();
        int unassignedB = B_REQUESTED - assignedB;

        int[] hs = parseHardSoft(logsB);
        int hard = hs != null ? hs[0] : N_A;
        int soft = hs != null ? hs[1] : N_A;

        int conflictsB = second.get("conflictCount").asInt();
        int solverInfeasibleB = countSolverInfeasible(second);

        ClashCounts clashesB = countClashes(second.get("entries"));
        int crossTimetable = countCrossTimetableClashes(second.get("entries"), first.get("entries"));

        long[] timings = parseEngineTimings(logsB);
        long engineMs = timings[0];
        long persistMs = engineMs > 0 ? Math.max(0L, apiMsB - engineMs) : N_A;

        return new RunResult(B_REQUESTED, assignedB, unassignedB, hard, soft, conflictsB, solverInfeasibleB,
            clashesB.faculty(), clashesB.room(), clashesB.section(), crossTimetable,
            apiMsB, apiMsA, timings[1], timings[2], timings[3], persistMs,
            countOtherTimetableEntries(second.get("id").asLong()),
            second.get("optimizationScore").asInt());
    }

    protected Setup prepareSectionsAndSubjects() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section secA = year1.getSections().get(0);
        Section secB = year1.getSections().get(1);
        secA.setStudentStrength(40); // seeded LAB room capacity is 40
        secB.setStudentStrength(40);
        sectionRepository.saveAndFlush(secA);
        sectionRepository.saveAndFlush(secB);

        Faculty f1 = facultyRepository.findByEmployeeId("FAC001").orElseThrow();
        Faculty f2 = facultyRepository.findByEmployeeId("FAC002").orElseThrow();

        if (subjectRepository.findBySubjectCode("CS301").isEmpty()) {
            subjectRepository.save(Subject.builder().subjectCode("CS301")
                .subjectName("Operating Systems").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f1).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
            subjectRepository.save(Subject.builder().subjectCode("CS302")
                .subjectName("Computer Networks").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
            subjectRepository.save(Subject.builder().subjectCode("CS303")
                .subjectName("Software Engineering").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
        }
        return new Setup(cse.getId(), secA, secB);
    }

    protected record Setup(long cseId, Section secA, Section secB) {
    }
}
