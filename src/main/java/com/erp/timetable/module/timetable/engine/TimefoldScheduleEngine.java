package com.erp.timetable.module.timetable.engine;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.FacultyAvailabilityRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.timetable.engine.shared.ConflictRecorderService;
import com.erp.timetable.module.timetable.engine.shared.CurriculumDataLoader;
import com.erp.timetable.module.timetable.engine.shared.LockPreservationService;
import com.erp.timetable.module.timetable.engine.shared.ReportingService;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider;
import com.erp.timetable.module.timetable.planning.mapper.SolverResultMapper;
import com.erp.timetable.module.timetable.planning.mapper.TimetablePlanningMapper;
import com.erp.timetable.module.timetable.planning.model.OccupancyFact;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Timefold-based scheduling engine (Phase 4).
 *
 * <p>Registered behind the {@link ScheduleEngine} interface when
 * {@code timetable.scheduler.engine=timefold}. Reuses the existing pipeline —
 * {@link CurriculumDataLoader}, {@link SubjectDemandService},
 * {@link LockPreservationService}, {@link TimetablePlanningMapper},
 * {@link SolverResultMapper}, {@link ConflictRecorderService},
 * {@link ReportingService} — and solves the section's placement problem with the
 * shared {@link SolverFactory} built by {@code TimefoldSolverConfig}.
 *
 * <p>Each solve runs synchronously through
 * {@code solverFactory.buildSolver(SolverConfigOverride)} with termination that is
 * <em>adaptive</em>: the spent / unimproved-spent safety nets scale with the lesson
 * count (bounded by {@code timetable.solver.termination.*}) so small timetables never
 * wait out the full cap. There is no {@code bestScoreFeasible} early exit — since the
 * planning variables allow unassigned, the all-unassigned solution already has a zero
 * hard score, so the solver must be free to keep assigning until the soft "minimize
 * unassigned lessons" score stops improving.
 *
 * <p>The engine never saves the timetable; {@code TimetableService} owns the
 * transaction and persistence, exactly like the Greedy engine.
 *
 * <p>How locked entries are preserved:
 * <ol>
 *   <li>{@link LockPreservationService#prepareForGeneration} keeps locked entries
 *       and drops unlocked ones on partial regeneration.</li>
 *   <li>Each surviving locked entry becomes a {@link PlanningLesson} whose room
 *       and (day, time-slot) window are pre-initialised to the entry's current
 *       placement and marked {@code @PlanningPin} — Timefold never moves it.</li>
 *   <li>Curriculum demand (theory + practical hours per subject) is added as
 *       fresh lessons; locked demand is already counted, so no double-scheduling.</li>
 *   <li>{@link SolverResultMapper} writes the solver's assignment back into the
 *       existing entry rows and appends solver-created rows.</li>
 * </ol>
 *
 * <p>Modeling notes: each subject contributes one lesson per THEORY hour (isLab
 * false) and one lesson per PRACTICAL hour (isLab true) — practical hours need a
 * LAB room and are grouped into consecutive windows of the subject's per-subject
 * practical block size (its sessionBlockSize 1/2/3, falling back to
 * {@code practical-block-size} only when null) by the
 * {@code LabConsecutiveBlockConstraint}.
 * The planning variables are nullable (see {@link PlanningLesson}): a lesson with no
 * legal placement — e.g. a practical lesson when the section has no LAB room — is
 * left unassigned, costs one soft point and never violates a hard rule, so the
 * engine finishes feasible (hard 0) instead of infeasible.
 *
 * <p><b>Cross-timetable clash avoidance (Phase 7).</b> The engine pre-loads the
 * entries of every other timetable for the relevant faculty (one batch DB call,
 * exactly like the Greedy engine) and passes them through
 * {@link TimetablePlanningMapper#toOccupancyFacts} as immutable
 * {@link com.erp.timetable.module.timetable.planning.model.OccupancyFact}
 * problem facts. The {@code Cross-timetable occupancy conflict} hard constraint
 * then forbids a lesson from reusing a (faculty, day, slot) or (room, day, slot)
 * window another timetable already occupies, closing audit gap #21 while every
 * other constraint still operates on the section's own solution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "timetable.scheduler.engine", havingValue = "timefold")
public class TimefoldScheduleEngine implements ScheduleEngine {

    private static final List<String> WORKING_DAYS = SubjectDemandService.WORKING_DAYS;
    private static final int DEFAULT_REQUIRED_CAPACITY = 40;

    private final TimeSlotRepository timeSlotRepository;
    private final FacultyRepository facultyRepository;
    private final ClassroomRepository classroomRepository;
    private final FacultyAvailabilityRepository availabilityRepository;
    private final TimetableEntryRepository entryRepository;

    private final CurriculumDataLoader curriculumDataLoader;
    private final SubjectDemandService subjectDemandService;
    private final LockPreservationService lockPreservationService;
    private final ConflictRecorderService conflictRecorderService;
    private final ReportingService reportingService;
    private final TimetablePlanningMapper planningMapper;
    private final SolverResultMapper resultMapper;
    private final SolverFactory<SchedulingSolution> solverFactory;

    /** Upper bounds for the adaptive termination (see {@code application.yml}). */
    @Value("${timetable.solver.termination.seconds:30}")
    private int terminationSeconds;

    @Value("${timetable.solver.termination.unimproved-seconds:5}")
    private int unimprovedSeconds;

    /**
     * Global fallback consecutive periods per practical session (mirrors the
     * Greedy engine). A subject's own {@code sessionBlockSize} (1, 2 or 3)
     * overrides this per subject; this fallback applies only when the stored
     * value is null/absent.
     */
    @Value("${timetable.scheduler.practical-block-size:2}")
    private int practicalBlockSize;

    @Override
    public void generateSchedule(Timetable timetable) {
        generateSchedule(timetable, false);
    }

    @Override
    public void generateSchedule(Timetable timetable, boolean regenerateOnlyUnlocked) {
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("  TIMEFOLD SCHEDULING ENGINE — Starting Generation");
        log.info("  Department : {}", timetable.getDepartment() != null ? timetable.getDepartment().getName() : "N/A");
        log.info("  Section    : {}", timetable.getSection() != null ? timetable.getSection().getName() : "N/A");
        log.info("  Semester   : {}", timetable.getSemester());
        log.info("═══════════════════════════════════════════════════════════════");
        long engineStartNanos = System.nanoTime();

        // ── 1. Curriculum ─────────────────────────────────────────────────────
        List<Subject> subjects = curriculumDataLoader.loadSubjectsForTimetable(timetable);
        if (subjects.isEmpty()) {
            log.warn("No subjects found for section '{}' semester {}. Timetable generation aborted.",
                timetable.getSection() != null ? timetable.getSection().getName() : "?",
                timetable.getSemester());
            conflictRecorderService.addConflict(timetable, "MISSING_CURRICULUM",
                "No subjects found for section '"
                    + (timetable.getSection() != null ? timetable.getSection().getName() : "?")
                    + "' semester " + timetable.getSemester()
                    + ". Please assign subjects to this section before generating.",
                "HIGH");
            timetable.setStatus("GENERATED");
            timetable.setConflictCount(1);
            timetable.setOptimizationScore(0);
            return;
        }

        // ── 2. Time slots (breaks excluded from the value range) ─────────────
        List<TimeSlot> allSlots = timeSlotRepository.findAllByOrderBySlotOrderAsc();
        List<TimeSlot> teachingSlots = allSlots.stream()
            .filter(s -> !Boolean.TRUE.equals(s.getIsBreak()))
            .toList();
        if (teachingSlots.isEmpty()) {
            log.error("No teaching slots configured. Aborting generation.");
            conflictRecorderService.addConflict(timetable, "NO_TIME_SLOTS",
                "No teaching time slots configured in the system. Please configure time slots.",
                "HIGH");
            timetable.setStatus("GENERATED");
            timetable.setConflictCount(1);
            timetable.setOptimizationScore(0);
            return;
        }

        // ── 3. Resources ─────────────────────────────────────────────────────
        List<Classroom> allRooms = classroomRepository.findByStatus("AVAILABLE");
        Map<Long, Faculty> facultyById = new HashMap<>();
        facultyRepository.findAll().forEach(f -> facultyById.put(f.getId(), f));

        // ── 4. Availability map (reporting score) ────────────────────────────
        List<FacultyAvailability> availabilities = availabilityRepository.findAll();
        Map<String, String> availabilityMap = new HashMap<>();
        for (FacultyAvailability fa : availabilities) {
            availabilityMap.put(fa.getFaculty().getId() + "_" + fa.getDayOfWeek() + "_" + fa.getTimeSlot().getId(),
                fa.getSlotType());
        }

        // ── 5. Locked-entry preservation ─────────────────────────────────────
        List<TimetableEntry> lockedEntries = lockPreservationService
            .prepareForGeneration(timetable, regenerateOnlyUnlocked);

        // ── 6. Weekly demand per subject (locked demand already counted) ─────
        Map<Long, Integer> weeklyHoursMap = subjectDemandService.calculateWeeklyHours(subjects);
        lockPreservationService.reduceWeeklyHoursForLockedSubjects(timetable, subjects, weeklyHoursMap);
        subjectDemandService.logDemandBreakdown(subjects, null);

        // ── 7. Build the planning model ──────────────────────────────────────
        List<PlannableRoom> plannableRooms = planningMapper.toPlannableRooms(allRooms);
        List<PlannableTimeSlot> plannableTimeSlots = planningMapper.toPlannableTimeSlots(allSlots, WORKING_DAYS);

        Map<Long, PlannableRoom> roomByKey = new HashMap<>();
        for (PlannableRoom r : plannableRooms) {
            roomByKey.put(r.getRoomId(), r);
        }
        Map<String, PlannableTimeSlot> windowByKey = new HashMap<>();
        for (PlannableTimeSlot w : plannableTimeSlots) {
            windowByKey.put(w.getTimeSlotId() + "|" + w.getDayOfWeek(), w);
        }

        Map<Long, PlannableFaculty> facultyFactByFacultyId = new HashMap<>();
        Map<Long, PlannableFaculty> facultyFactBySubjectId = new HashMap<>();
        facultyById.values().forEach(f -> facultyFactByFacultyId.put(f.getId(), planningMapper.toPlannableFaculty(f)));
        for (Subject s : subjects) {
            if (s.getAssignedFaculty() != null) {
                PlannableFaculty fact = facultyFactByFacultyId.computeIfAbsent(s.getAssignedFaculty().getId(),
                    id -> planningMapper.toPlannableFaculty(s.getAssignedFaculty()));
                facultyFactBySubjectId.put(s.getId(), fact);
            }
        }

        List<PlanningLesson> lessons = new ArrayList<>();

        // Locked entries keep their placement (pinned by @PlanningPin). Their
        // practical-session metadata is reconstructed from the actual consecutive
        // runs so the lab-block constraint never flags a preserved placement.
        Map<Long, long[]> lockedLabSessions = TimetablePlanningMapper.labSessionMetadataByEntryId(lockedEntries);
        for (TimetableEntry entry : lockedEntries) {
            if (entry.getSubject() == null || entry.getFaculty() == null) continue;
            long[] session = lockedLabSessions.get(entry.getId());
            lessons.add(PlanningLesson.builder()
                .id(entry.getId())
                .sourceEntryId(entry.getId())
                .subject(planningMapper.toPlannableSubject(entry.getSubject()))
                .faculty(planningMapper.toPlannableFaculty(entry.getFaculty()))
                .sectionId(timetable.getSection() != null ? timetable.getSection().getId() : null)
                .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
                .academicYearId(timetable.getSection() != null && timetable.getSection().getAcademicYear() != null
                    ? timetable.getSection().getAcademicYear().getId()
                    : null)
                .requiredCapacity(requiredCapacity(timetable))
                .isLab(Boolean.TRUE.equals(entry.getIsLab()))
                .practicalSessionId(Boolean.TRUE.equals(entry.getIsLab())
                    && session != null ? session[0] : null)
                .practicalSessionSize(Boolean.TRUE.equals(entry.getIsLab())
                    && session != null ? (int) session[1] : null)
                .locked(true)
                .room(entry.getClassroom() != null ? roomByKey.get(entry.getClassroom().getId()) : null)
                .timeSlot(entry.getTimeSlot() != null
                    ? windowByKey.get(entry.getTimeSlot().getId() + "|" + entry.getDayOfWeek())
                    : null)
                .build());
        }

        // Curriculum demand as fresh lessons: one lesson per THEORY hour (non-lab
        // component) and one lesson per PRACTICAL hour (lab component). The lab
        // consecutive-block rule groups a subject's practical lessons into
        // consecutive windows of the subject's per-subject practical block size
        // (its sessionBlockSize: 1, 2 or 3), falling back to `practical-block-size`
        // (default 2) only when the stored value is null/absent.
        for (Subject subject : subjects) {
            if (!Boolean.TRUE.equals(subject.getIsActive())) continue;
            int theory = subject.getTheoryHours() != null ? subject.getTheoryHours() : 0;
            int practical = subject.getPracticalHours() != null ? subject.getPracticalHours() : 0;
            if (theory + practical <= 0) continue;

            // Partial regeneration: locked entries (pinned below) already cover
            // part of this subject's demand, so only the uncovered remainder is
            // added as fresh lessons — one per theory hour, one per practical hour.
            if (regenerateOnlyUnlocked) {
                for (TimetableEntry e : lockedEntries) {
                    if (e.getSubject() == null || !e.getSubject().getId().equals(subject.getId())) continue;
                    if (Boolean.TRUE.equals(e.getIsLab())) practical--; else theory--;
                }
                theory = Math.max(0, theory);
                practical = Math.max(0, practical);
            }

            PlannableFaculty faculty = facultyFactBySubjectId.get(subject.getId());
            PlannableSubject plannableSubject = planningMapper.toPlannableSubject(subject);

            for (int i = 0; i < theory; i++) {
                lessons.add(PlanningLesson.builder()
                    .id(null)
                    .sourceEntryId(null)
                    .subject(plannableSubject)
                    .faculty(faculty)
                    .sectionId(timetable.getSection() != null ? timetable.getSection().getId() : null)
                    .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
                    .requiredCapacity(requiredCapacity(timetable))
                    .isLab(false)
                    .locked(false)
                    .build());
            }

            // Practical demand is grouped into Greedy-equivalent sessions:
            // Part 2: lab uses the subject's per-subject sessionBlockSize (1/2/3),
            // falling back to the global practical-block-size when null.
            if (practical > 0) {
                long sessionSeq = 1L;
                // The PRACTICAL block size must come from getPracticalBlockSize, which
                // honours the subject's configured sessionBlockSize (1/2/3) and only
                // falls back to the global practical-block-size when it is null.
                // getSessionBlockSize is the THEORY accessor and forces 1 for every
                // LAB subject, so calling it here collapsed a configured
                // sessionBlockSize=3 lab into three INDEPENDENT size-1 sessions: the
                // labConsecutiveBlock hard rule then validated each one-period session
                // on its own, so nothing tied the three periods together and the
                // solver could scatter them across three separate days (score
                // 0hard, result [1,1,1]) instead of one consecutive 3-period block.
                int blockSize = subjectDemandService.getPracticalBlockSize(subject);
                int blockSessions = practical / blockSize;
                int remainder = practical % blockSize;
                long subjectIdKey = subject.getId() != null ? subject.getId() : -1L;
                for (int b = 0; b < blockSessions; b++) {
                    long sessionId = subjectIdKey * 1_000_000L + sessionSeq;
                    for (int i = 0; i < blockSize; i++) {
                        lessons.add(PlanningLesson.builder()
                            .id(null)
                            .sourceEntryId(null)
                            .subject(plannableSubject)
                            .faculty(faculty)
                            .sectionId(timetable.getSection() != null ? timetable.getSection().getId() : null)
                            .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
                            .requiredCapacity(requiredCapacity(timetable))
                            .isLab(true)
                            .practicalSessionId(sessionId)
                            .practicalSessionSize(blockSize)
                            .locked(false)
                            .build());
                    }
                    sessionSeq++;
                }
                for (int r = 0; r < remainder; r++) {
                    long sessionId = subjectIdKey * 1_000_000L + sessionSeq;
                    lessons.add(PlanningLesson.builder()
                        .id(null)
                        .sourceEntryId(null)
                        .subject(plannableSubject)
                        .faculty(faculty)
                        .sectionId(timetable.getSection() != null ? timetable.getSection().getId() : null)
                        .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
                        .requiredCapacity(requiredCapacity(timetable))
                        .isLab(true)
                        .practicalSessionId(sessionId)
                        .practicalSessionSize(1)
                        .locked(false)
                        .build());
                    sessionSeq++;
                }
            } // end practical > 0
        } // end for subjects

        // ── 7b. Cross-timetable occupancy facts (Phase 7) ───────────────────
        // Mirrors the Greedy engine: one batch load of every entry of the
        // relevant faculty across ALL timetables, then the mapper excludes the
        // timetable currently being generated. The facts make every (day, slot)
        // already claimed by another timetable's faculty/room a hard constraint.
        List<Long> allFacultyIds = new ArrayList<>(facultyById.keySet());
        List<TimetableEntry> existingEntries = allFacultyIds.isEmpty()
            ? Collections.emptyList()
            : entryRepository.findByFacultyIdIn(allFacultyIds);
        List<OccupancyFact> occupancyFacts = planningMapper.toOccupancyFacts(existingEntries, timetable.getId());

        // ── 7c. Greedy-parity practical pre-assignment ──────────────────────
        // Timefold's construction heuristic assigns lessons one at a time and the
        // lab-block hard constraint treats a partial session as a HARD violation,
        // so the heuristic would never start a multi-period block (the first
        // placed period already violates the hard rule and "unassigned" always
        // scores better). To mirror the Greedy engine's all-or-nothing practical
        // semantics, every fresh lab session is placed here as a complete
        // consecutive block — the solver then optimises the theory lessons around
        // the blocks and cannot split one without a hard violation.
        preAssignLabSessions(lessons, plannableRooms, plannableTimeSlots, availabilities,
            occupancyFacts, requiredCapacity(timetable),
            timetable.getSection() != null ? timetable.getSection().getId() : null);

        // ── 7d. Construction-heuristic ordering ──────────────────────────────
        // The default FIRST_FIT construction heuristic processes the solution's
        // lessons in list order. Practical lessons must be built first — a lab
        // block needs consecutive windows, and placing it after the theory
        // lessons have scattered across the value range leaves no consecutive
        // room (exactly the trap the Greedy engine avoids by scheduling STEP 8
        // practicals first). A stable sort keeps the original relative order
        // within the lab and theory groups. Locked lessons are pinned by
        // @PlanningPin and are unaffected by ordering.
        lessons.sort(Comparator.comparing(PlanningLesson::isLab).reversed());

        // ── 7e. Room-availability-aware time-slot range order ────────────────
        // FIRST_FIT takes the FIRST value of the time-slot range that satisfies
        // the hard constraints, so the range order decides where the seed lands.
        // With a room-poor day early in the range the heuristic deterministically
        // fills that day to its hard room limit and spills the rest onto the next
        // day, producing a day count the soft distribution penalty can never undo:
        // consolidating later needs a multi-move climb out of that basin, and the
        // local search does not find it (verified: 13x the search budget produced
        // a byte-identical solution). Ordering the range by DESCENDING free
        // eligible room cells makes the seed fill the room-rich days first, which
        // is the same "the real per-day capacity is min(freeSlots, freeRoomCells)"
        // rule the Greedy engine's day capacity now uses. Pure value ORDERING: the
        // value set is unchanged, so no placement is removed and no hard
        // constraint (room clash/capacity/type/scope, faculty, section, tenant)
        // is relaxed — the local search may still move any lesson anywhere. Days
        // that tie keep their original order, so a timetable whose rooms cover
        // every day equally is completely unaffected.
        orderTimeSlotsByRoomAvailability(plannableTimeSlots, plannableRooms, occupancyFacts,
            requiredCapacity(timetable),
            timetable.getSection() != null ? timetable.getSection().getId() : null,
            timetable.getSection() != null && timetable.getSection().getAcademicYear() != null
                ? timetable.getSection().getAcademicYear().getId() : null);

        SchedulingSolution problem = planningMapper.toSolutionFromLessons(
            timetable, availabilities, lessons, plannableRooms, plannableTimeSlots, occupancyFacts);

        log.info("  Solving {} lessons over {} rooms x {} windows ...",
            lessons.size(), plannableRooms.size(), plannableTimeSlots.size());

        long mappingMillis = Duration.ofNanos(System.nanoTime() - engineStartNanos).toMillis();
        log.info("  Prepared planning model in {} ms", mappingMillis);

        // ── 8. Solve (synchronous, adaptive termination) ─────────────────────
        // Propagate the configured practical-block size into the constraint
        // provider as the FALLBACK for subjects with a null/absent stored block
        // (the provider is instantiated per solve by Timefold via its
        // no-arg constructor; per-subject sessionBlockSize 1/2/3 overrides come
        // from the planning model itself).
        TimetableConstraintProvider.setGlobalPracticalBlockSize(practicalBlockSize);

        Duration spentLimit = adaptiveSpentLimit(lessons.size());
        Duration unimprovedLimit = adaptiveUnimprovedLimit(lessons.size());
        log.debug("  Termination: spent={}ms, unimproved={}ms", spentLimit.toMillis(), unimprovedLimit.toMillis());

        SolverConfigOverride override = new SolverConfigOverride();
        // No bestScoreFeasible early exit: with allowsUnassigned the trivial
        // all-unassigned solution already has a zero hard score, so the solver
        // would terminate before scheduling anything. Termination is spent +
        // unimproved-spent only.
        override.withTerminationConfig(new TerminationConfig()
            .withSpentLimit(spentLimit)
            .withUnimprovedSpentLimit(unimprovedLimit));

        long solveStartNanos = System.nanoTime();
        SchedulingSolution best;
        try {
            best = solverFactory.buildSolver(override).solve(problem);
        } catch (RuntimeException e) {
            log.error("Timefold solve failed for timetable {}", timetable.getId(), e);
            throw new IllegalStateException("Timefold scheduling failed", e);
        }
        long solveMillis = Duration.ofNanos(System.nanoTime() - solveStartNanos).toMillis();
        log.info("  Solved in {} ms", solveMillis);

        // ── 9. Write the assignment back ─────────────────────────────────────
        long applyStartNanos = System.nanoTime();
        resultMapper.applyToTimetable(timetable, best);
        log.info("  Applied solver result in {} ms",
            Duration.ofNanos(System.nanoTime() - applyStartNanos).toMillis());

        // ── 10. Validate, score, finalise ────────────────────────────────────
        int conflictCount = 0;
        HardSoftScore finalScore = best.getScore();
        if (finalScore != null && finalScore.hardScore() < 0) {
            if (conflictRecorderService.addConflict(timetable, "SOLVER_INFEASIBLE",
                "Timefold solver finished infeasible (" + finalScore.toShortString()
                    + "): hard constraints remain violated.",
                "HIGH")) {
                conflictCount++;
            }
        }
        // Unassigned lessons are only softly penalised by the solver, so without
        // this step an unschedulable practical would silently vanish while the UI
        // reports "0 conflicts". Record them truthfully instead.
        conflictCount += reportUnassignedLessons(timetable, best, plannableRooms, plannableTimeSlots,
            availabilities, occupancyFacts, requiredCapacity(timetable),
            timetable.getSection() != null ? timetable.getSection().getId() : null);
        conflictCount += conflictRecorderService.runPostValidation(timetable);

        int score = reportingService.computeOptimizationScore(timetable, availabilityMap, 0);
        timetable.setConflictCount(conflictCount);
        timetable.setOptimizationScore(score);
        timetable.setStatus("GENERATED");

        int unassigned = (int) best.getLessons().stream()
            .filter(lesson -> lesson.getRoom() == null || lesson.getTimeSlot() == null)
            .count();

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("  TIMEFOLD SCHEDULING COMPLETE");
        log.info("  Solver Score    : {}", finalScore != null ? finalScore.toShortString() : "N/A");
        log.info("  Entries         : {}", timetable.getEntries().size());
        log.info("  Unassigned      : {}", unassigned);
        log.info("  Conflicts       : {}", conflictCount);
        log.info("  Optimization    : {}%", score);
        log.info("  Engine total    : {} ms",
            Duration.ofNanos(System.nanoTime() - engineStartNanos).toMillis());
        log.info("═══════════════════════════════════════════════════════════════");
    }

    /**
     * Greedy-parity practical pre-assignment (Greedy STEP 8 analogue).
     *
     * <p>Timefold's construction heuristic assigns lessons one at a time and the
     * lab-block hard constraint treats a partial session as a HARD violation, so
     * the heuristic would never start a multi-period block (the first placed
     * period already violates the hard rule and "unassigned" always scores
     * better). To mirror the Greedy engine's all-or-nothing practical semantics,
     * every fresh (non-locked) lab session is placed here as a complete
     * consecutive block before the solver runs, so the construction heuristic
     * and local search only ever see fully-placed or fully-unassigned sessions.
     *
     * <p>Placement is first-fit exactly like Greedy STEP 8: working days are
     * rotated by the session index, each day's consecutive non-break windows are
     * scanned in order, and the first capacity-valid LAB room that satisfies
     * every hard rule wins. The hard rules mirror the Greedy engine's constraints
     * (see {@code engine.constraint}): room/faculty/section clashes (including
     * cross-timetable occupancy facts), BLOCKED/BUSY faculty availability, a
     * LEAVE faculty, the daily cap (5 or the faculty's own lower maxDailyHours),
     * and the weekly cap (own maxWeeklyHours or 24). Back-to-back teaching is
     * allowed, so there is no consecutive-teaching rejection. Sessions that
     * cannot be placed remain unassigned as a whole — one soft penalty per
     * lesson plus an honest PRACTICAL_UNAVAILABLE conflict.
     */
    private void preAssignLabSessions(List<PlanningLesson> lessons,
            List<PlannableRoom> rooms, List<PlannableTimeSlot> slots,
            List<FacultyAvailability> availabilities, List<OccupancyFact> occupancyFacts,
            int requiredCapacity, Long sectionId) {

        PreAssignContext ctx = buildPreAssignContext(lessons, availabilities, occupancyFacts);

        // Group fresh lab sessions by practicalSessionId.
        Map<Long, List<PlanningLesson>> sessions = new LinkedHashMap<>();
        for (PlanningLesson l : lessons) {
            if (!l.isLab() || l.isLocked() || l.getPracticalSessionId() == null) continue;
            sessions.computeIfAbsent(l.getPracticalSessionId(), k -> new ArrayList<>()).add(l);
        }

        // Consecutive non-break windows per day (break slots are already filtered
        // out of the value range by the mapper).
        Map<String, List<PlannableTimeSlot>> windowsByDay = windowsByDay(slots);

        List<PlannableRoom> eligibleLabs = rooms.stream()
            .filter(r -> "LAB".equalsIgnoreCase(r.getRoomType()))
            .filter(r -> r.getCapacity() != null && r.getCapacity() >= requiredCapacity)
            .toList();

        List<String> days = new ArrayList<>(windowsByDay.keySet());
        // LAB sessions are NEVER scheduled on Saturday (college policy).
        days.removeIf(day -> "SAT".equalsIgnoreCase(day));
        int sessionIndex = 0;
        for (List<PlanningLesson> session : sessions.values()) {
            int size = session.size();
            PlanningLesson first = session.get(0);
            PlannableFaculty faculty = first.getFaculty();

            // Rotate the starting day so practicals don't all gravitate to Monday
            // (Greedy rotates by the practical block index).
            int rotate = sessionIndex % Math.max(1, days.size());

            if (faculty != null && !"LEAVE".equalsIgnoreCase(faculty.getStatus())) {
                placed:
                for (int di = 0; di < days.size(); di++) {
                    String day = days.get((di + rotate) % days.size());
                    List<PlannableTimeSlot> dayWindows = windowsByDay.get(day);
                    for (int i = 0; i + size <= dayWindows.size(); i++) {
                        if (!isConsecutiveWindow(dayWindows, i, size)) continue;
                        List<PlannableTimeSlot> window = dayWindows.subList(i, i + size);
                        List<PlannableRoom> sessionLabs = eligibleLabs.stream()
                            .filter(r -> roomScopeCompatible(r, first))
                            .toList();
                        for (PlannableRoom room : sessionLabs) {
                            if (blockPlacementReason(faculty, room, day, window, ctx, sectionId) != null) {
                                continue;
                            }
                            for (int k = 0; k < size; k++) {
                                session.get(k).setRoom(room);
                                session.get(k).setTimeSlot(window.get(k));
                                registerInContext(session.get(k), ctx);
                            }
                            break placed;
                        }
                    }
                }
            }
            sessionIndex++;
        }
    }

    /** Per-day, slot-order-sorted teaching windows (break slots already excluded). */
    private Map<String, List<PlannableTimeSlot>> windowsByDay(List<PlannableTimeSlot> slots) {
        Map<String, List<PlannableTimeSlot>> windowsByDay = new LinkedHashMap<>();
        for (PlannableTimeSlot s : slots) {
            windowsByDay.computeIfAbsent(s.getDayOfWeek(), k -> new ArrayList<>()).add(s);
        }
        for (List<PlannableTimeSlot> dayWindows : windowsByDay.values()) {
            dayWindows.sort(Comparator.comparing(PlannableTimeSlot::getSlotOrder));
        }
        return windowsByDay;
    }

    /** Mirrors Greedy {@code buildConsecutiveWindows}: strictly consecutive slot orders. */
    private boolean isConsecutiveWindow(List<PlannableTimeSlot> dayWindows, int start, int size) {
        for (int k = 1; k < size; k++) {
            Integer prev = dayWindows.get(start + k - 1).getSlotOrder();
            Integer next = dayWindows.get(start + k).getSlotOrder();
            if (prev == null || next == null || next != prev + 1) return false;
        }
        return true;
    }

    /**
     * Shared occupancy/availability context for the practical pre-assignment and
     * for the truthful post-solve diagnostics. Mirrors the Greedy engine's
     * in-memory context maps plus its DB clash checks (occupancy facts).
     */
    private static final class PreAssignContext {
        final Map<String, Set<Long>> roomOccupancy = new HashMap<>();
        final Map<String, Set<Long>> facultyOccupancy = new HashMap<>();
        final Map<String, Boolean> sectionOccupancy = new HashMap<>();
        final Map<String, Integer> facultyDailyHours = new HashMap<>();
        final Map<Long, Integer> facultyWeeklyHours = new HashMap<>();
        final Map<String, List<Integer>> facultyDaySlots = new HashMap<>();
        // facultyId_day_slotId -> slot type ("BLOCKED"/"BUSY" are unavailable,
        // mirroring the Greedy FacultyAvailabilityConstraint).
        final Map<String, String> availabilityMap = new HashMap<>();
    }

    private PreAssignContext buildPreAssignContext(List<PlanningLesson> lessons,
            List<FacultyAvailability> availabilities, List<OccupancyFact> occupancyFacts) {
        PreAssignContext ctx = new PreAssignContext();
        for (FacultyAvailability fa : availabilities) {
            if (fa.getFaculty() == null || fa.getTimeSlot() == null) continue;
            ctx.availabilityMap.put(fa.getFaculty().getId() + "_" + fa.getDayOfWeek() + "_" + fa.getTimeSlot().getId(),
                fa.getSlotType());
        }

        // Seed the occupancy context with locked lessons (already placed, pinned
        // by @PlanningPin so they never move).
        for (PlanningLesson l : lessons) {
            if (!l.isLocked() || l.getRoom() == null || l.getTimeSlot() == null) continue;
            registerInContext(l, ctx);
        }

        // Cross-timetable occupancy facts occupy the foreign room/faculty for the
        // (day, slot) — exactly like the Greedy Room/FacultyClash constraints'
        // DB checks. A foreign entry also consumes part of the faculty's daily
        // load: Greedy's buildContext registers every foreign entry into
        // facultyDailyHours (TimetableGeneratorEngine#registerEntryInContextMaps),
        // so the college-wide 5/day cap (or the faculty's own lower maxDailyHours)
        // applies to current + foreign periods, never to the current timetable
        // alone. Weekly hours deliberately stay single-timetable here, matching
        // the FacultyWeeklyHoursLimit constraint (both still apply the weekly cap
        // to the section's own lessons only).
        for (OccupancyFact f : occupancyFacts) {
            String key = f.getDayOfWeek() + "_" + f.getTimeSlotId();
            ctx.facultyOccupancy.computeIfAbsent(key, k -> new HashSet<>()).add(f.getFacultyId());
            if (f.getRoomId() != null) {
                ctx.roomOccupancy.computeIfAbsent(key, k -> new HashSet<>()).add(f.getRoomId());
            }
            ctx.facultyDailyHours.merge(f.getFacultyId() + "_" + f.getDayOfWeek(), 1, Integer::sum);
        }
        return ctx;
    }

    /**
     * Null if placing the {@code window}-sized block on {@code day} in
     * {@code room} violates none of the Greedy engine's hard rules; otherwise the
     * exact reason the placement was rejected. Mirrors {@code canPlaceBlock}.
     */
    private String blockPlacementReason(PlannableFaculty faculty, PlannableRoom room, String day,
            List<PlannableTimeSlot> window, PreAssignContext ctx, Long sectionId) {

        int size = window.size();
        Long facultyId = faculty.getFacultyId();
        Long roomId = room.getRoomId();

        for (PlannableTimeSlot s : window) {
            String slotKey = day + "_" + s.getTimeSlotId();
            if (ctx.roomOccupancy.getOrDefault(slotKey, Collections.emptySet()).contains(roomId)) {
                return "the LAB room is occupied in that window";
            }
            if (ctx.facultyOccupancy.getOrDefault(slotKey, Collections.emptySet()).contains(facultyId)) {
                return "the faculty is already teaching in that window";
            }
            if (sectionId != null
                    && Boolean.TRUE.equals(ctx.sectionOccupancy.get(sectionId + "_" + slotKey))) {
                return "the section already has a class in that window";
            }
            String avail = ctx.availabilityMap.get(facultyId + "_" + day + "_" + s.getTimeSlotId());
            if ("BLOCKED".equalsIgnoreCase(avail) || "BUSY".equalsIgnoreCase(avail)) {
                return "the faculty is blocked/busy during that window";
            }
        }

        // Daily cap: 5, or the faculty's own lower maxDailyHours (Greedy FacultyDailyHoursConstraint).
        Integer facultyOwnDaily = faculty.getMaxDailyHours();
        int dailyCap = (facultyOwnDaily != null && facultyOwnDaily > 0)
            ? Math.min(5, facultyOwnDaily) : 5;
        int currentDaily = ctx.facultyDailyHours.getOrDefault(facultyId + "_" + day, 0);
        if (currentDaily + size > dailyCap) {
            return "the faculty is at its daily-hour limit (" + dailyCap + ") in that window";
        }

        // Weekly cap: own maxWeeklyHours or 24 (Greedy FacultyWeeklyHoursConstraint).
        Integer facultyOwnWeekly = faculty.getMaxWeeklyHours();
        int weeklyCap = (facultyOwnWeekly != null && facultyOwnWeekly > 0) ? facultyOwnWeekly : 24;
        int currentWeekly = ctx.facultyWeeklyHours.getOrDefault(facultyId, 0);
        if (currentWeekly + size > weeklyCap) {
            return "the faculty is at its weekly-hour limit (" + weeklyCap + ")";
        }

        // College policy now allows back-to-back teaching (no consecutive-teaching
        // hard rule), so a block is never rejected on that basis.
        return null;
    }

    /** Mirrors Greedy {@code registerEntryInContextMaps} for the pre-assignment. */
    private void registerInContext(PlanningLesson l, PreAssignContext ctx) {

        Long facultyId = l.getFaculty() != null ? l.getFaculty().getFacultyId() : null;
        if (facultyId == null || l.getTimeSlot() == null) return;
        String day = l.getTimeSlot().getDayOfWeek();
        String slotKey = day + "_" + l.getTimeSlot().getTimeSlotId();
        String fDayKey = facultyId + "_" + day;

        if (l.getSectionId() != null) {
            ctx.sectionOccupancy.put(l.getSectionId() + "_" + slotKey, true);
        }
        ctx.facultyOccupancy.computeIfAbsent(slotKey, k -> new HashSet<>()).add(facultyId);
        if (l.getRoom() != null) {
            ctx.roomOccupancy.computeIfAbsent(slotKey, k -> new HashSet<>()).add(l.getRoom().getRoomId());
        }
        ctx.facultyDailyHours.merge(fDayKey, 1, Integer::sum);
        ctx.facultyWeeklyHours.merge(facultyId, 1, Integer::sum);

        if (l.getTimeSlot().getSlotOrder() != null) {
            List<Integer> dayPattern = ctx.facultyDaySlots.computeIfAbsent(fDayKey, k -> new ArrayList<>());
            dayPattern.add(l.getTimeSlot().getSlotOrder());
            dayPattern.sort(Integer::compareTo);
        }
    }

    private int requiredCapacity(Timetable timetable) {
        if (timetable.getSection() != null && timetable.getSection().getStudentStrength() != null) {
            return timetable.getSection().getStudentStrength();
        }
        return DEFAULT_REQUIRED_CAPACITY;
    }

    /**
     * Records a truthful HIGH conflict for every lesson the solver left unassigned.
     *
     * <p>The planning variables are nullable and unassigned lessons only carry the
     * soft "minimize unassigned lessons" penalty, so without this step an
     * unschedulable practical (no eligible LAB room, every consecutive window
     * occupied, or faculty unavailable / at its hour limit) would be silently
     * dropped while the UI reported "0 conflicts (clean)".
     *
     * <p>Practical conflicts carry an exact per-subject accounting — requested /
     * assigned / unassigned practical periods — and a precise placement
     * diagnosis (required capacity vs largest LAB room, or the dominant hard
     * rule that rejected every candidate consecutive window). This makes the
     * {@code requested == assigned + unassigned} invariant auditable.
     *
     * @return the number of new conflict records added
     */
    private int reportUnassignedLessons(Timetable timetable, SchedulingSolution solution,
            List<PlannableRoom> rooms, List<PlannableTimeSlot> slots,
            List<FacultyAvailability> availabilities, List<OccupancyFact> occupancyFacts,
            int requiredCapacity, Long sectionId) {
        if (solution == null || solution.getLessons() == null || solution.getLessons().isEmpty()) return 0;

        // Per-subject lab accounting: [requested, assigned, unassigned].
        Map<String, int[]> labStats = new LinkedHashMap<>();
        Map<String, PlanningLesson> labSample = new LinkedHashMap<>();
        Map<String, Integer> unassignedTheory = new LinkedHashMap<>();

        for (PlanningLesson lesson : solution.getLessons()) {
            boolean unassigned = lesson.getRoom() == null || lesson.getTimeSlot() == null;
            String subjectCode = lesson.getSubject() != null ? lesson.getSubject().getSubjectCode() : "?";
            if (lesson.isLab()) {
                int[] stats = labStats.computeIfAbsent(subjectCode, k -> new int[3]);
                stats[0]++;
                if (unassigned) {
                    stats[2]++;
                    labSample.putIfAbsent(subjectCode, lesson);
                } else {
                    stats[1]++;
                }
            } else if (unassigned) {
                unassignedTheory.merge(subjectCode, 1, Integer::sum);
            }
        }
        if (labStats.isEmpty() && unassignedTheory.isEmpty()) return 0;

        int count = 0;
        for (Map.Entry<String, int[]> entry : labStats.entrySet()) {
            int[] stats = entry.getValue();
            if (stats[2] == 0) continue;
            String cause = diagnoseLabPlacementFailure(labSample.get(entry.getKey()), rooms, slots,
                availabilities, occupancyFacts, requiredCapacity, sectionId, solution.getLessons());
            String description = entry.getKey() + ": requested " + stats[0]
                + " practical period(s), assigned " + stats[1]
                + ", unassigned " + stats[2] + ". " + cause;
            if (conflictRecorderService.addConflict(timetable, "PRACTICAL_UNAVAILABLE", description, "HIGH")) {
                count++;
            }
        }
        for (Map.Entry<String, Integer> entry : unassignedTheory.entrySet()) {
            String description = entry.getValue() + " theory period(s) of " + entry.getKey()
                + " could not be scheduled. No eligible room with sufficient capacity and a free window "
                + "was available for the assigned faculty.";
            if (conflictRecorderService.addConflict(timetable, "THEORY_UNAVAILABLE", description, "HIGH")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Room scoping: a classroom owned by an academic year / section may only be
     * used by a lesson of the matching section (and its year). NULL scope
     * fields mean the room is global/shared and skip the check, mirroring the
     * Greedy engine's {@code isRoomEligibleForTimetable} and the Timefold
     * {@code ROOM_SCOPE_MATCH} hard constraint. The classroom's department is
     * organizational metadata, not a scheduling filter.
     */
    private boolean roomScopeCompatible(PlannableRoom room, PlanningLesson lesson) {
        if (room.getAcademicYearId() != null
                && !room.getAcademicYearId().equals(lesson.getAcademicYearId())) {
            return false;
        }
        if (room.getSectionId() != null
                && !room.getSectionId().equals(lesson.getSectionId())) {
            return false;
        }
        return true;
    }

    /** Scope check against explicit ids (same rule as the lesson overload). */
    private boolean roomScopeCompatible(PlannableRoom room, Long academicYearId, Long sectionId) {
        if (room.getAcademicYearId() != null
                && !room.getAcademicYearId().equals(academicYearId)) {
            return false;
        }
        if (room.getSectionId() != null
                && !room.getSectionId().equals(sectionId)) {
            return false;
        }
        return true;
    }

    /**
     * Reorders the {@code timeSlotRange} value range by DESCENDING count of the
     * (slot x classroom) cells that could still host a THEORY period that day.
     *
     * <p>A cell counts only when a non-LAB, scope-compatible classroom that is
     * not already occupied at that (day, slot) exists — exactly the eligibility
     * the {@code ROOM_TYPE} / {@code ROOM_SCOPE_MATCH} / {@code ROOM_CAPACITY} /
     * {@code ROOM_CLASH} hard constraints enforce at placement, including the
     * theory-only capacity relaxation the other room logic uses. The comparison
     * is stable, so days with equal cell counts keep the canonical
     * WORKING_DAYS / slot order and nothing else changes.
     *
     * <p>This is a construction-heuristic seed aid only. It changes no
     * constraint, removes no value from the range and cannot forbid a placement.
     */
    private void orderTimeSlotsByRoomAvailability(List<PlannableTimeSlot> slots,
            List<PlannableRoom> rooms, List<OccupancyFact> occupancyFacts,
            int requiredCapacity, Long sectionId, Long academicYearId) {

        if (slots == null || slots.isEmpty() || rooms == null || rooms.isEmpty()) {
            return;
        }

        // Rooms already claimed by another timetable's entry, per (day, slot).
        Map<String, Set<Long>> occupiedRooms = new HashMap<>();
        if (occupancyFacts != null) {
            for (OccupancyFact f : occupancyFacts) {
                if (f.getRoomId() == null || f.getDayOfWeek() == null || f.getTimeSlotId() == null) {
                    continue;
                }
                occupiedRooms
                    .computeIfAbsent(f.getDayOfWeek() + "_" + f.getTimeSlotId(), k -> new HashSet<>())
                    .add(f.getRoomId());
            }
        }

        Map<String, Integer> cellsByDay = new HashMap<>();
        for (PlannableTimeSlot slot : slots) {
            if (slot == null || slot.getDayOfWeek() == null) {
                continue;
            }
            Set<Long> occupied = occupiedRooms.getOrDefault(
                slot.getDayOfWeek() + "_" + slot.getTimeSlotId(), Collections.emptySet());
            boolean usable = rooms.stream()
                .filter(r -> !"LAB".equalsIgnoreCase(r.getRoomType()))
                .filter(r -> roomScopeCompatible(r, academicYearId, sectionId))
                .filter(r -> r.getCapacity() != null && r.getCapacity() >= requiredCapacity)
                .filter(r -> !occupied.contains(r.getRoomId()))
                .findAny()
                .isPresent();
            if (!usable) {
                // Theory-only capacity relaxation, mirroring findAvailableRoom /
                // the pre-assignment room scan.
                usable = rooms.stream()
                    .filter(r -> !"LAB".equalsIgnoreCase(r.getRoomType()))
                    .filter(r -> roomScopeCompatible(r, academicYearId, sectionId))
                    .filter(r -> !occupied.contains(r.getRoomId()))
                    .findAny()
                    .isPresent();
            }
            if (usable) {
                cellsByDay.merge(slot.getDayOfWeek(), 1, Integer::sum);
            }
        }

        List<PlannableTimeSlot> ordered = new ArrayList<>(slots);
        ordered.sort(Comparator.comparingInt(
            (PlannableTimeSlot s) -> -cellsByDay.getOrDefault(
                s == null ? null : s.getDayOfWeek(), 0)));
        slots.clear();
        slots.addAll(ordered);
    }

    /**
     * Explains why a practical session of {@code sample}'s subject could not be
     * placed. Mirrors the pre-assignment exactly: the same {@code PreAssignContext}
     * (locked lessons + occupancy facts + availability map) and the same
     * {@code blockPlacementReason} rule checks over every consecutive non-break
     * window and every capacity-valid LAB room.
     */
    private String diagnoseLabPlacementFailure(PlanningLesson sample,
            List<PlannableRoom> rooms, List<PlannableTimeSlot> slots,
            List<FacultyAvailability> availabilities, List<OccupancyFact> occupancyFacts,
            int requiredCapacity, Long sectionId, List<PlanningLesson> allLessons) {
        if (sample == null || sample.getFaculty() == null) {
            return "No practical period could be placed; no faculty is assigned to the subject.";
        }
        if ("LEAVE".equalsIgnoreCase(sample.getFaculty().getStatus())) {
            return "The assigned faculty is on leave, so the practical could not be scheduled.";
        }

        PreAssignContext ctx = buildPreAssignContext(allLessons, availabilities, occupancyFacts);
        PlannableFaculty faculty = sample.getFaculty();
        int size = sample.getPracticalSessionSize() != null ? sample.getPracticalSessionSize() : 1;

        List<PlannableRoom> eligibleLabs = rooms.stream()
            .filter(r -> "LAB".equalsIgnoreCase(r.getRoomType()))
            .filter(r -> r.getCapacity() != null && r.getCapacity() >= requiredCapacity)
            .filter(r -> roomScopeCompatible(r, sample))
            .toList();
        if (eligibleLabs.isEmpty()) {
            int maxLabCap = rooms.stream()
                .filter(r -> "LAB".equalsIgnoreCase(r.getRoomType()))
                .map(PlannableRoom::getCapacity)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
            return "No eligible LAB room: required capacity is " + requiredCapacity
                + (maxLabCap > 0 ? " but the largest LAB room holds " + maxLabCap
                    : " and no LAB room exists at all")
                + ", so no practical of this subject can be scheduled.";
        }

        Map<String, List<PlannableTimeSlot>> windowsByDay = windowsByDay(slots);
        Map<String, Integer> reasonTally = new LinkedHashMap<>();
        int candidateCount = 0;
        for (Map.Entry<String, List<PlannableTimeSlot>> dayEntry : windowsByDay.entrySet()) {
            List<PlannableTimeSlot> dayWindows = dayEntry.getValue();
            for (int i = 0; i + size <= dayWindows.size(); i++) {
                if (!isConsecutiveWindow(dayWindows, i, size)) continue;
                List<PlannableTimeSlot> window = dayWindows.subList(i, i + size);
                for (PlannableRoom room : eligibleLabs) {
                    candidateCount++;
                    String reason = blockPlacementReason(faculty, room, dayEntry.getKey(), window, ctx,
                        sectionId);
                    if (reason == null) {
                        return "A capacity-valid LAB room (" + room.getRoomNumber()
                            + ", capacity " + room.getCapacity()
                            + ") and a consecutive free window were available, but the solver left the "
                            + "session unassigned.";
                    }
                    reasonTally.merge(reason, 1, Integer::sum);
                }
            }
        }
        int totalCandidates = candidateCount;
        String topReasons = reasonTally.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(3)
            .map(e -> e.getValue() + " of " + totalCandidates + " candidate placements hit: " + e.getKey())
            .collect(Collectors.joining("; "));
        return "Every candidate placement (" + candidateCount + ") was rejected; " + topReasons + ".";
    }

    private Duration adaptiveSpentLimit(int lessonCount) {
        // The floor (3s) guarantees even tiny timetables get enough search time to
        // consolidate soft local optima (e.g. a mixed theory+practical subject whose
        // theory must collapse onto its minimum teaching days).
        long millis = Math.max(3000L, lessonCount * 200L);
        return Duration.ofMillis(Math.min(millis, (long) terminationSeconds * 1000L));
    }

    private Duration adaptiveUnimprovedLimit(int lessonCount) {
        long millis = Math.max(1500L, lessonCount * 100L);
        return Duration.ofMillis(Math.min(millis, (long) unimprovedSeconds * 1000L));
    }
}
