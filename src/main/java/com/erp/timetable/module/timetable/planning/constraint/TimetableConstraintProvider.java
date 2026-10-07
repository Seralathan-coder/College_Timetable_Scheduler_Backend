package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.quad.QuadConstraintStream;
import com.erp.timetable.module.timetable.engine.constraint.ConsecutiveTeachingConstraint;
import com.erp.timetable.module.timetable.engine.constraint.DepartmentPermissionConstraint;
import com.erp.timetable.module.timetable.engine.constraint.FacultyAssignedSubjectConstraint;
import com.erp.timetable.module.timetable.engine.constraint.FacultyAvailabilityConstraint;
import com.erp.timetable.module.timetable.engine.constraint.FacultyDailyHoursConstraint;
import com.erp.timetable.module.timetable.engine.constraint.FacultyWeeklyHoursConstraint;
import com.erp.timetable.module.timetable.engine.constraint.LabConsecutiveBlockConstraint;
import com.erp.timetable.module.timetable.engine.constraint.RoomTypeConstraint;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.planning.model.AvailabilityFact;
import com.erp.timetable.module.timetable.planning.model.OccupancyFact;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Hard constraints for the Timefold timetable solver (Phase 3A + 3B).
 *
 * <p>Phase 3A contributed the three clash constraints:
 * <ul>
 *   <li>{@link #SECTION_CONFLICT} — a section may only attend one lesson per
 *       (day, time slot) window.</li>
 *   <li>{@link #FACULTY_CONFLICT} — a faculty member may only teach one lesson
 *       per window.</li>
 *   <li>{@link #ROOM_CONFLICT} — a room may only host one lesson per window.</li>
 * </ul>
 *
 * <p>Phase 3B mirrors the remaining nine hard rules of the Greedy engine
 * ({@code TimetableGeneratorEngine#evaluateAllConstraints}). Each rule is a
 * direct translation of the matching {@code *Constraint#isSatisfied} method in
 * {@code com.erp.timetable.module.timetable.engine.constraint}:
 * <ul>
 *   <li>{@link #FACULTY_AVAILABILITY} — {@link FacultyAvailabilityConstraint}</li>
 *   <li>{@link #FACULTY_DAILY_HOURS} — {@link FacultyDailyHoursConstraint}</li>
 *   <li>{@link #FACULTY_WEEKLY_HOURS} — {@link FacultyWeeklyHoursConstraint}</li>
 *   <li>{@link #DEPARTMENT_PERMISSION} — {@link DepartmentPermissionConstraint}</li>
 *   <li>{@link #FACULTY_ASSIGNED_SUBJECT} — {@link FacultyAssignedSubjectConstraint}</li>
 *   <li>{@link #ROOM_TYPE_MATCH} — {@link RoomTypeConstraint}</li>
 *   <li>{@link #ROOM_CAPACITY} — Greedy {@code TimetableGeneratorEngine#findAvailableRoom} /
 *       {@code getRequiredCapacity} (room capacity &gt;= section strength)</li>
 *   <li>{@link #LAB_CONSECUTIVE_BLOCK} — {@link LabConsecutiveBlockConstraint}</li>
 *   <li>{@link #CONSECUTIVE_TEACHING_RULE} — {@link ConsecutiveTeachingConstraint}</li>
 *   <li>{@link #SUBJECT_DAILY_PERIOD_LIMIT} — a NORMAL subject holds at most 2
 *       periods on a day, back-to-back when it holds 2 (the hard form of the
 *       Greedy planner's pair/single distribution rule; LAB components
 *       excluded).</li>
 * </ul>
 *
 * <p><b>Greedy vs Timefold semantic notes.</b> The Greedy engine decides
 * placements one at a time against an accumulated context and returns a boolean
 * reject; Timefold scores the final assignment. Consequences:
 * <ul>
 *   <li>{@link #FACULTY_AVAILABILITY} merges the {@code LEAVE} faculty-status
 *       check and the {@code BLOCKED}/{@code BUSY} availability-fact check into
 *       one constraint via {@code concat}, since Timefold forbids duplicate
 *       constraint names.</li>
 *   <li>{@link #FACULTY_DAILY_HOURS}/{@link #FACULTY_WEEKLY_HOURS} penalise
 *       {@code count - effectiveCap} (one hard point per excess period) instead
 *       of a binary reject, so severer overloads score worse.
 *       {@link #FACULTY_DAILY_HOURS} is additionally cross-timetable aware: the
 *       daily cap is enforced against the faculty's load across every timetable
 *       (current lessons + {@link OccupancyFact} foreign load), exactly like the
 *       Greedy engine's whole-college context.</li>
 *   <li>Faculty groupings key on the shared {@link PlannableFaculty} instances
 *       built by {@code TimetablePlanningMapper} (one instance per faculty);
 *       consumers must share instances across lessons of the same faculty.</li>
 *   <li>Unassigned lessons (null room/time slot) are ignored by every hard
 *       constraint: {@code forEach} excludes entities whose planning variables
 *       are unset/null, and each stream also guards its keys with explicit
 *       null filters, matching the Phase 3A decision not to create phantom
 *       conflicts for unplaced work. Unassigned lessons are instead penalised
 *       by the soft {@link #UNASSIGNED_LESSONS} constraint (which uses
 *       {@code forEachIncludingUnassigned}), so a lesson is only left unassigned
 *       when placing it would violate a hard rule.</li>
 * </ul>
 *
 * <p><b>Soft constraints.</b> Three soft constraints shape the optimisation
 * objective, ordered hard rules &gt; {@link #UNASSIGNED_LESSONS} &gt;
 * {@link #IDLE_GAP} &gt; {@link #SUBJECT_DISTRIBUTION}:
 * <ul>
 *   <li>{@link #unassignedLessons(ConstraintFactory)} minimises the number of
 *       lessons left without a room and window
 *       ({@value #UNASSIGNED_LESSON_WEIGHT} soft points each).</li>
 *   <li>{@link #idleGap(ConstraintFactory)} minimises faculty idle gaps — free
 *       non-break windows strictly between a faculty member's first and last
 *       teaching period of a day (one soft point per idle window).</li>
 *   <li>{@link #subjectDistribution(ConstraintFactory)} steers repeated sessions
 *       of the same subject onto the pair/single teaching-day pattern
 *       ({@code idealTeachingDays}: up to 5 weekly periods → one per day, the
 *       surplus → consecutive pairs, so 6/7/8/9/10 hours all use 5 days and 11
 *       uses 6) — {@value #EXTRA_THEORY_DAY_WEIGHT} soft points per day of
 *       deviation, plus one soft point per non-consecutive gap between two
 *       same-day theory sessions.</li>
 * </ul>
 * Feasibility (hard score zero) therefore does not require every lesson to be
 * scheduled; at equal unassigned counts a compacted schedule outscores a gapped
 * one, and at equal unassigned and idle counts a schedule matching each subject's
 * pair/single distribution outscores a scattered one.
 */
public class TimetableConstraintProvider implements ConstraintProvider {

    public static final String SECTION_CONFLICT = "Section conflict";
    public static final String FACULTY_CONFLICT = "Faculty conflict";
    public static final String ROOM_CONFLICT = "Room conflict";
    public static final String FACULTY_AVAILABILITY = "Faculty availability";
    public static final String FACULTY_DAILY_HOURS = "Faculty daily hours limit";
    public static final String FACULTY_WEEKLY_HOURS = "Faculty weekly hours limit";
    public static final String DEPARTMENT_PERMISSION = "Department permission";
    public static final String FACULTY_ASSIGNED_SUBJECT = "Faculty assigned subject";
    public static final String ROOM_TYPE_MATCH = "Room type match";
    public static final String ROOM_CAPACITY = "Room capacity";
    public static final String LAB_CONSECUTIVE_BLOCK = "Lab consecutive block";
    public static final String LAB_SATURDAY_FORBIDDEN = "Lab not allowed on Saturday";
    public static final String CONSECUTIVE_TEACHING_RULE = "Consecutive teaching rule";
    public static final String CROSS_TIMETABLE_OCCUPANCY = "Cross-timetable occupancy conflict";
    public static final String ROOM_SCOPE_MATCH = "Room scope match";
    public static final String UNASSIGNED_LESSONS = "Minimize unassigned lessons";
    public static final String IDLE_GAP = "Minimize faculty idle gaps";
    public static final String SUBJECT_DISTRIBUTION = "Cluster subject sessions into few teaching days";
    public static final String SUBJECT_DAILY_PERIOD_LIMIT = "Subject daily period limit";
    public static final String SECTION_DAILY_PAIR_LIMIT = "Section daily back-to-back subject limit";

    // Soft weight of an unassigned lesson. It must dominate the per-unit weights
    // of IDLE_GAP and SUBJECT_DISTRIBUTION (both ONE_SOFT) by a wide margin so
    // that leaving a lesson unassigned is NEVER a net win over scheduling it:
    // assigning a lesson can force idle gaps and same-day repeats worth up to a
    // handful of soft points, and if the unassigned penalty were only barely
    // heavier the solver would stop at a compact 40/42 "best" instead of the
    // complete 42/42 schedule (the TT1 Timefold regression). A weight of 1000
    // keeps the mandated hierarchy hard > unassigned > idle-gap > distribution:
    // the solver first maximises the number of lessons assigned, then optimises
    // gaps and distribution among complete schedules.
    // Public so the constraint tests (and the unassigned-semantics solver
    // tests) can reference the exact weight they assert on.
    public static final int UNASSIGNED_LESSON_WEIGHT = 1000;

    // Mirrors FacultyDailyHoursConstraint.COLLEGE_WIDE_MAX_DAILY_HOURS (5)
    private static final int COLLEGE_WIDE_MAX_DAILY_HOURS = 5;

    // Mirrors SubjectDemandService: a NORMAL subject (THEORY / GAME / OTHER)
    // holds at most 2 periods a day, and when it holds 2 they are consecutive.
    private static final int MAX_PERIODS_PER_SUBJECT_PER_DAY =
        SubjectDemandService.MAX_PERIODS_PER_SUBJECT_PER_DAY;

    // Mirrors SubjectDemandService.SINGLE_PERIOD_WEEK_LIMIT: up to 5 weekly
    // periods a normal subject is spread one period per day; beyond that the
    // surplus is planned as consecutive pairs.
    private static final int SINGLE_PERIOD_WEEK_LIMIT =
        SubjectDemandService.SINGLE_PERIOD_WEEK_LIMIT;

    // Soft weight of one THEORY teaching day beyond the ideal. It equals the
    // college-wide daily cap so that "fewer teaching days" ALWAYS dominates
    // gap compactness (the user's priority order): the lunch-break rule forces
    // an unavoidable same-day gap on any 5-lesson day, and with day penalty ==
    // gap penalty (both 1) the solver would rationally pick 3 days over 2. A
    // day beyond ideal costs COLLEGE_WIDE_MAX_DAILY_HOURS (5) points, strictly
    // more than the maximum same-day gaps (4) a day can ever accumulate, so the
    // solver first collapses each subject to its minimum teaching days and only
    // then competes on same-day consecutive runs. Still negligible next to
    // UNASSIGNED_LESSON_WEIGHT (1000), keeping the mandated hierarchy hard >
    // unassigned > idle-gap > distribution intact.
    // The weight is applied to the ABSOLUTE deviation from the ideal day count
    // (see theoryDistributionPenalty), so it also prices a day SHORT of the
    // ideal. That is what makes the intended double/single day count the optimum
    // instead of merely an upper bound, for every block size — the hard daily cap
    // only forbids a 6th period, so a subject packed 5 periods onto one day has to
    // be priced away by the soft distribution terms. Public so the constraint
    // tests can reference the exact weight they assert.
    public static final int EXTRA_THEORY_DAY_WEIGHT = COLLEGE_WIDE_MAX_DAILY_HOURS;

    // Mirrors FacultyWeeklyHoursConstraint.DEFAULT_MAX_WEEKLY_HOURS
    private static final int DEFAULT_MAX_WEEKLY_HOURS = 24;
    // Mirrors TimetableGeneratorEngine.getRequiredCapacity() default
    private static final int DEFAULT_MIN_CAPACITY = 40;

    /**
     * Default practical-block size. Mirrors
     * {@code timetable.scheduler.practical-block-size} (default 2). The provider
     * is instantiated by Timefold via its no-arg constructor, so the engine's
     * configured value is propagated through {@link #setGlobalPracticalBlockSize}
     * before solving.
     */
    public static final int DEFAULT_PRACTICAL_BLOCK_SIZE = 2;

    private static int globalPracticalBlockSize = DEFAULT_PRACTICAL_BLOCK_SIZE;

    private final int practicalBlockSize;

    public TimetableConstraintProvider() {
        this.practicalBlockSize = globalPracticalBlockSize;
    }

    public TimetableConstraintProvider(int practicalBlockSize) {
        this.practicalBlockSize = Math.max(1, practicalBlockSize);
    }

    /** Configures the block size for providers created after this call. */
    public static void setGlobalPracticalBlockSize(int practicalBlockSize) {
        globalPracticalBlockSize = Math.max(1, practicalBlockSize);
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            sectionConflict(factory),
            facultyConflict(factory),
            roomConflict(factory),
            facultyAvailability(factory),
            facultyDailyHoursLimit(factory),
            facultyWeeklyHoursLimit(factory),
            departmentPermission(factory),
            facultyAssignedSubject(factory),
            roomTypeMatch(factory),
            roomCapacity(factory),
            roomScopeMatch(factory),
            labConsecutiveBlock(factory),
            labSaturdayForbidden(factory),
            consecutiveTeachingRule(factory),
            subjectDailyPeriodLimit(factory),
            sectionDailyPairLimit(factory),
            crossTimetableOccupancy(factory),
            unassignedLessons(factory),
            idleGap(factory),
            subjectDistribution(factory)
        };
    }

    // ============================== Phase 3A ==============================

    public Constraint sectionConflict(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null)
            .groupBy(PlanningLesson::getSectionId, PlanningLesson::getTimeSlot,
                ConstraintCollectors.count())
            .filter((sectionId, timeSlot, count) -> count > 1)
            .penalize(HardSoftScore.ONE_HARD, (sectionId, timeSlot, count) -> count - 1)
            .asConstraint(SECTION_CONFLICT);
    }

    public Constraint facultyConflict(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
            .groupBy(lesson -> lesson.getFaculty().getFacultyId(), PlanningLesson::getTimeSlot,
                ConstraintCollectors.count())
            .filter((facultyId, timeSlot, count) -> count > 1)
            .penalize(HardSoftScore.ONE_HARD, (facultyId, timeSlot, count) -> count - 1)
            .asConstraint(FACULTY_CONFLICT);
    }

    public Constraint roomConflict(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getRoom() != null)
            .groupBy(lesson -> lesson.getRoom().getRoomId(), PlanningLesson::getTimeSlot,
                ConstraintCollectors.count())
            .filter((roomId, timeSlot, count) -> count > 1)
            .penalize(HardSoftScore.ONE_HARD, (roomId, timeSlot, count) -> count - 1)
            .asConstraint(ROOM_CONFLICT);
    }

    // ====================== Phase 3B — hard rules ========================

    /**
     * Mirrors {@link FacultyAvailabilityConstraint}: a lesson is rejected when
     * its faculty is on LEAVE or when its (day, slot) carries a {@code BLOCKED}
     * or {@code BUSY} availability fact. The two conditions are merged into one
     * constraint via {@code concat} (Timefold forbids duplicate constraint
     * names); leave faculty are excluded from the fact branch so a leave faculty
     * is never double-counted, mirroring the Greedy short-circuit.
     */
    public Constraint facultyAvailability(ConstraintFactory factory) {
        ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream<PlanningLesson> onLeave =
            factory.forEach(PlanningLesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
                .filter(lesson -> "LEAVE".equalsIgnoreCase(lesson.getFaculty().getStatus()));

        ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream<PlanningLesson> blockedOrBusy =
            factory.forEach(PlanningLesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
                .filter(lesson -> !"LEAVE".equalsIgnoreCase(lesson.getFaculty().getStatus()))
                .join(AvailabilityFact.class,
                    Joiners.equal(lesson -> lesson.getFaculty().getFacultyId(),
                        AvailabilityFact::getFacultyId),
                    Joiners.equal(lesson -> lesson.getTimeSlot().getDayOfWeek(),
                        AvailabilityFact::getDayOfWeek),
                    Joiners.equal(lesson -> lesson.getTimeSlot().getTimeSlotId(),
                        AvailabilityFact::getTimeSlotId))
                .filter((lesson, fact) -> "BLOCKED".equalsIgnoreCase(fact.getSlotType())
                    || "BUSY".equalsIgnoreCase(fact.getSlotType()))
                .map((lesson, fact) -> lesson);

        return onLeave.concat(blockedOrBusy)
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(FACULTY_AVAILABILITY);
    }

    /**
     * Mirrors {@link FacultyDailyHoursConstraint}: per (faculty, day) the number
     * of periods must not exceed {@code min(5, maxDailyHours)} (or 5 when
     * maxDailyHours is unset/non-positive). Penalises each excess period.
     *
     * <p>Like the Greedy engine, the cap is <em>cross-timetable aware</em>: it
     * applies to the faculty's total daily load across <b>every</b> timetable,
     * not just the section being solved. Each {@link OccupancyFact} of the same
     * faculty and day (one per entry of the other timetables, built by
     * {@code TimetablePlanningMapper#toOccupancyFacts}) counts as one
     * additional period of that day's load. The single-timetable behaviour is
     * unchanged — with no foreign entries the two concatenated branches below
     * reduce exactly to the original count-and-penalise rule.
     *
     * <p>The two branches are disjoint on the current-lesson count so a day is
     * never double-penalised: the current-only branch fires when the section's
     * own lessons already exceed the cap (penalty = own excess) and the
     * cross-timetable branch fires only while the own count is still at or
     * below the cap and the foreign load pushes the total over it (penalty =
     * total excess). {@code concat} merges them into one constraint because
     * Timefold forbids duplicate constraint names; both branches emit the same
     * (faculty, day, current, foreign) quad so the merged penalise value is a
     * single pure function of that quad.
     */
    public Constraint facultyDailyHoursLimit(ConstraintFactory factory) {
        QuadConstraintStream<PlannableFaculty, String, Long, Long> currentOnly =
            factory.forEach(PlanningLesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
                .groupBy(PlanningLesson::getFaculty,
                    lesson -> lesson.getTimeSlot().getDayOfWeek(),
                    ConstraintCollectors.countDistinct(lesson -> lesson),
                    ConstraintCollectors.countDistinct(lesson -> lesson))
                .filter((faculty, day, current, ignored) -> current > effectiveDailyCap(faculty));

        QuadConstraintStream<PlannableFaculty, String, Long, Long> withForeignLoad =
            factory.forEach(PlanningLesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
                .join(OccupancyFact.class,
                    Joiners.equal(lesson -> lesson.getFaculty().getFacultyId(), OccupancyFact::getFacultyId),
                    Joiners.equal(lesson -> lesson.getTimeSlot().getDayOfWeek(), OccupancyFact::getDayOfWeek))
                .groupBy((lesson, fact) -> lesson.getFaculty(),
                    (lesson, fact) -> lesson.getTimeSlot().getDayOfWeek(),
                    ConstraintCollectors.countDistinct((lesson, fact) -> lesson),
                    ConstraintCollectors.countDistinct((lesson, fact) -> fact))
                .filter((faculty, day, current, foreign) -> current <= effectiveDailyCap(faculty)
                    && current + foreign > effectiveDailyCap(faculty));

        return currentOnly.concat(withForeignLoad)
            .penalize(HardSoftScore.ONE_HARD,
                (faculty, day, current, foreign) -> current > effectiveDailyCap(faculty)
                    ? current - effectiveDailyCap(faculty)
                    : current + foreign - effectiveDailyCap(faculty))
            .asConstraint(FACULTY_DAILY_HOURS);
    }

    /**
     * Mirrors {@link FacultyWeeklyHoursConstraint}: per faculty the total weekly
     * periods must not exceed {@code maxWeeklyHours} (default 24). Penalises each
     * excess period.
     */
    public Constraint facultyWeeklyHoursLimit(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
            .groupBy(PlanningLesson::getFaculty, ConstraintCollectors.count())
            .filter((faculty, count) -> count > effectiveWeeklyCap(faculty))
            .penalize(HardSoftScore.ONE_HARD, (faculty, count) -> count - effectiveWeeklyCap(faculty))
            .asConstraint(FACULTY_WEEKLY_HOURS);
    }

    /**
     * Mirrors {@link DepartmentPermissionConstraint}: the faculty must belong to
     * the target department, or its primary department name / a teaching-department
     * token must match the target department id or name.
     */
    public Constraint departmentPermission(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null
                && lesson.getDepartmentId() != null)
            .filter(lesson -> !hasDepartmentPermission(lesson.getFaculty(), lesson.getDepartmentId(), lesson.getSubject()))
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(DEPARTMENT_PERMISSION);
    }

    /**
     * Mirrors {@link FacultyAssignedSubjectConstraint}: the faculty must be the
     * subject's assigned faculty, appear in the subject-code list, or match by
     * specialization.
     */
    public Constraint facultyAssignedSubject(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null
                && lesson.getSubject() != null)
            .filter(lesson -> !hasAssignedSubject(lesson.getFaculty(), lesson.getSubject()))
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(FACULTY_ASSIGNED_SUBJECT);
    }

    /**
     * Mirrors {@link RoomTypeConstraint}: practical lessons (component flag)
     * require LAB rooms and theory lessons require non-LAB rooms (boolean
     * equality on the LAB flag).
     */
    public Constraint roomTypeMatch(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getRoom() != null && lesson.getSubject() != null)
            .filter(lesson -> lesson.isLab() != isLab(lesson.getRoom()))
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(ROOM_TYPE_MATCH);
    }

    /**
     * Mirrors the Greedy room-capacity filter
     * ({@code TimetableGeneratorEngine#findAvailableRoom} + {@code getRequiredCapacity}):
     * the assigned room's capacity must be &gt;= the lesson's required capacity
     * (section student strength, default 40).
     */
    public Constraint roomCapacity(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getRoom() != null && lesson.getRoom().getCapacity() != null)
            .filter(lesson -> requiredCapacityOf(lesson) > lesson.getRoom().getCapacity())
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(ROOM_CAPACITY);
    }

    /**
     * Room scoping: a classroom owned by a department / academic year / section
     * may only be assigned to a lesson of the matching section (and its year).
     * NULL ownership fields mean the room is global/shared and skip the check,
     * mirroring the Greedy engine's {@code isRoomEligibleForTimetable} filter.
     * The classroom's department is organizational metadata, not a scheduling
     * filter.
     */
    public Constraint roomScopeMatch(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getRoom() != null)
            .filter(lesson -> !roomScopeCompatible(lesson))
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(ROOM_SCOPE_MATCH);
    }

    private static boolean roomScopeCompatible(PlanningLesson lesson) {
        PlannableRoom room = lesson.getRoom();
        if (room.getAcademicYearId() != null
                && !Objects.equals(room.getAcademicYearId(), lesson.getAcademicYearId())) {
            return false;
        }
        if (room.getSectionId() != null
                && !Objects.equals(room.getSectionId(), lesson.getSectionId())) {
            return false;
        }
        return true;
    }

    /**
     * Mirrors {@link LabConsecutiveBlockConstraint} with Greedy-exact
     * <em>all-or-nothing</em> session semantics. Practical lessons are grouped
     * into explicit sessions by {@link PlanningLesson#getPracticalSessionId}
     * (built by the {@code timefold} engine from the subject's per-subject
     * practical block size: {@code sessionBlockSize} 1/2/3, global fallback only
     * when null), each with an expected size
     * {@link PlanningLesson#getPracticalSessionSize}. A session is valid only
     * when it is placed <b>in full</b> — every period assigned, on one day, in
     * strictly consecutive non-break slots, exactly matching the configured
     * block size. A partial session (some periods placed, some not) is a hard
     * violation, so the solver completes the block or leaves the whole session
     * unassigned — identical to the Greedy engine, which never places a partial
     * practical block. A session with every period unassigned is valid (it is
     * the "cannot be placed" state, penalised only softly).
     */
    public Constraint labConsecutiveBlock(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.isLab() && lesson.getPracticalSessionId() != null)
            .groupBy(PlanningLesson::getPracticalSessionId, ConstraintCollectors.toList())
            .filter((key, lessons) -> !isValidLabSession(lessons))
            .penalize(HardSoftScore.ONE_HARD, (key, lessons) -> 1)
            .asConstraint(LAB_CONSECUTIVE_BLOCK);
    }

    /**
     * College policy: LAB sessions are NEVER scheduled on Saturday. Mirrors the
     * Greedy engine, which offers only the five non-Saturday LAB days to every
     * practical placement ({@code LAB_DAYS}). A placed lab lesson whose window
     * falls on Saturday is a hard violation; unassigned lessons (no window) are
     * excluded, matching the Phase 3A "no phantom conflicts" rule.
     */
    public Constraint labSaturdayForbidden(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.isLab()
                && lesson.getTimeSlot() != null
                && "SAT".equalsIgnoreCase(lesson.getTimeSlot().getDayOfWeek()))
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(LAB_SATURDAY_FORBIDDEN);
    }

    /**
     * Mirrors {@link ConsecutiveTeachingConstraint}: college policy now allows
     * faculty to teach back-to-back periods without any maximum-consecutive or
     * minimum-free-gap restriction. The constraint is kept registered (so the
     * solver-verification report still lists it) but never penalises — the
     * daily/weekly hour caps and per-faculty maxDailyHours remain binding.
     */
    public Constraint consecutiveTeachingRule(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> false)
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1)
            .asConstraint(CONSECUTIVE_TEACHING_RULE);
    }

    /**
     * Hard form of the Greedy planner's 2-period daily rule: a NORMAL subject
     * (THEORY / GAME / OTHER) never holds more than 2 periods on a day, and when
     * it holds 2 they are CONSECUTIVE periods. This mirrors the Greedy engine,
     * where the rule is enforced by the distribution plan (at most one session
     * per day) plus {@code TimetableGeneratorEngine#tryPlaceConsecutiveBlock}
     * (a pair is always a consecutive window) and
     * {@code TimetableGeneratorEngine#isAnotherTheoryPairPlacedOnDay}.
     *
     * <p>It has to be HARD rather than soft: with only a soft day-count
     * preference the solver happily leaves a subject with 3 or 4 periods on one
     * day (the daily teaching cap of 5 does not forbid it), which the greedy
     * engine would never produce.
     *
     * <p>LAB components are excluded — practical sessions keep their own block
     * rule ({@link #LAB_CONSECUTIVE_BLOCK}) and may share a day with a theory
     * pair. Sessions are grouped per section, so two sections meeting the same
     * subject on the same day are never merged, and unassigned lessons are
     * excluded to match every other hard constraint.
     *
     * <p>Scoring: one hard point per surplus period on an over-full day, plus one
     * hard point for a day holding exactly 2 periods that are not consecutive.
     */
    public Constraint subjectDailyPeriodLimit(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getSubject() != null)
            .groupBy(TimetableConstraintProvider::subjectSectionKey, ConstraintCollectors.toList())
            .filter((key, lessons) -> subjectDailyPeriodViolations(lessons) > 0L)
            .penalize(HardSoftScore.ONE_HARD,
                (key, lessons) -> subjectDailyPeriodViolations(lessons))
            .asConstraint(SUBJECT_DAILY_PERIOD_LIMIT);
    }

    /**
     * Number of 2-period daily-rule violations in one (subject, section) group:
     * every period above the daily ceiling on an over-full day, plus one for a
     * two-period day whose periods are not adjacent.
     */
    private static long subjectDailyPeriodViolations(List<PlanningLesson> lessons) {
        long violations = 0L;
        for (List<Integer> orders : theoryOrdersByDay(lessons).values()) {
            int size = orders.size();
            if (size > MAX_PERIODS_PER_SUBJECT_PER_DAY) {
                violations += size - MAX_PERIODS_PER_SUBJECT_PER_DAY;
                continue;
            }
            if (size == MAX_PERIODS_PER_SUBJECT_PER_DAY) {
                List<Integer> sorted = orders.stream().sorted().toList();
                if (sorted.get(1) - sorted.get(0) != 1) {
                    violations++;
                }
            }
        }
        return violations;
    }

    /**
     * One section + one day = at most ONE back-to-back NORMAL subject, mirroring
     * the Greedy engine's {@code TimetableGeneratorEngine#sectionAlreadyHostsLabOnDay}
     * analogue {@code isAnotherTheoryPairPlacedOnDay}: a second paired subject on
     * the same day is rejected even though its periods sit in different slots, so
     * a busy day can never degenerate into three back-to-back sessions.
     *
     * <p>LAB components are excluded — a practical block may share a day with a
     * theory pair. Because {@link #SUBJECT_DAILY_PERIOD_LIMIT} already caps a
     * subject at 2 periods a day, "holds a consecutive pair" is exactly "holds
     * 2 periods on that day", which keeps this constraint a plain count.
     */
    public Constraint sectionDailyPairLimit(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getSubject() != null)
            .groupBy(lesson -> lesson.getSectionId() + "_" + lesson.getTimeSlot().getDayOfWeek(),
                ConstraintCollectors.toList())
            .filter((dayKey, lessons) -> backToBackSubjectCount(lessons) > 1L)
            .penalize(HardSoftScore.ONE_HARD,
                (dayKey, lessons) -> backToBackSubjectCount(lessons) - 1L)
            .asConstraint(SECTION_DAILY_PAIR_LIMIT);
    }

    /**
     * Number of distinct subjects holding a back-to-back pair on one section-day.
     */
    private static long backToBackSubjectCount(List<PlanningLesson> lessons) {
        long pairOwners = 0L;
        for (List<PlanningLesson> bySubject : lessonsBySubject(lessons).values()) {
            for (List<Integer> orders : theoryOrdersByDay(bySubject).values()) {
                if (orders.size() == MAX_PERIODS_PER_SUBJECT_PER_DAY) {
                    List<Integer> sorted = orders.stream().sorted().toList();
                    if (sorted.get(1) - sorted.get(0) == 1) {
                        pairOwners++;
                        break;
                    }
                }
            }
        }
        return pairOwners;
    }

    /**
     * NORMAL (non-LAB) lessons of one section-day grouped by subject id.
     */
    private static Map<Long, List<PlanningLesson>> lessonsBySubject(List<PlanningLesson> lessons) {
        Map<Long, List<PlanningLesson>> bySubject = new HashMap<>();
        for (PlanningLesson lesson : lessons) {
            if (lesson.isLab()) continue;
            bySubject.computeIfAbsent(
                lesson.getSubject() != null ? lesson.getSubject().getSubjectId() : Long.MIN_VALUE,
                k -> new ArrayList<>())
                .add(lesson);
        }
        return bySubject;
    }

    /**
     * Mirrors the Greedy engine's cross-timetable occupancy context
     * ({@code TimetableGeneratorEngine#buildContext}): a lesson may not use a
     * faculty/day/time window or room/day/time window already occupied by
     * another timetable (Phase 7).
     *
     * <p>Each {@link OccupancyFact} represents one foreign timetable entry and
     * carries both that entry's faculty and room ids. A lesson is penalised once
     * per clashing <em>window</em> — the {@code groupBy} collapses every
     * matching (lesson, fact) pair into a single one-point penalty, so a lesson
     * that clashes on faculty only, room only, or both counts as exactly one
     * violation, exactly like Greedy's boolean reject of a placement.
     *
     * <p>Unassigned lessons (null room or window) are excluded by the initial
     * {@code filter}, matching the Phase 3A decision never to create phantom
     * conflicts for unplaced work.
     */
    public Constraint crossTimetableOccupancy(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null)
            .join(OccupancyFact.class,
                Joiners.equal(lesson -> lesson.getTimeSlot().getDayOfWeek(), OccupancyFact::getDayOfWeek),
                Joiners.equal(lesson -> lesson.getTimeSlot().getTimeSlotId(), OccupancyFact::getTimeSlotId))
            .filter((lesson, fact) -> conflictsWithOccupancy(lesson, fact))
            .groupBy((lesson, fact) -> lesson)
            .penalize(HardSoftScore.ONE_HARD, lesson -> 1L)
            .asConstraint(CROSS_TIMETABLE_OCCUPANCY);
    }

    /** A lesson clashes with a foreign occupancy fact when its faculty or its
     * room is the one the foreign entry claims for the same (day, slot). */
    private static boolean conflictsWithOccupancy(PlanningLesson lesson, OccupancyFact fact) {
        if (lesson.getFaculty() != null && fact.getFacultyId() != null
                && Objects.equals(lesson.getFaculty().getFacultyId(), fact.getFacultyId())) {
            return true;
        }
        return lesson.getRoom() != null && fact.getRoomId() != null
            && Objects.equals(lesson.getRoom().getRoomId(), fact.getRoomId());
    }

    /**
     * Soft constraint — minimize the number of lessons left unassigned. A lesson
     * is unassigned when it has no room or no (day, time slot) window.
     *
     * <p>This constraint iterates with {@code forEachIncludingUnassigned}: the
     * plain {@code forEach} excludes entities whose planning variables are
     * unset/null, so without it an unassigned lesson would carry no cost and the
     * construction heuristic would have no incentive to schedule anything. With
     * the penalty in place the solver prefers to schedule a lesson whenever a
     * placement satisfies all hard rules, and leaves it unassigned only when no
     * such placement exists. Feasibility (hard score zero) is therefore
     * achievable even when some lessons stay unassigned.
     *
     * <p>The penalty uses {@value #UNASSIGNED_LESSON_WEIGHT} soft points per
     * lesson (heavily dominating the one-point units of IDLE_GAP and
     * SUBJECT_DISTRIBUTION) so the solver never drops a lesson just to improve
     * those soft preferences: unassigning a lesson would need to relieve at
     * least {@value #UNASSIGNED_LESSON_WEIGHT} soft points elsewhere to break
     * even, and it never can, so assigning the lesson always scores strictly
     * better. This is what lets the solver prefer a complete 42/42 schedule
     * over a compact one that leaves lessons unassigned.
     */
    public Constraint unassignedLessons(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(PlanningLesson.class)
            .filter(lesson -> lesson.getRoom() == null || lesson.getTimeSlot() == null)
            .penalize(HardSoftScore.ofSoft(UNASSIGNED_LESSON_WEIGHT), lesson -> 1)
            .asConstraint(UNASSIGNED_LESSONS);
    }

    /**
     * Soft constraint — minimize faculty idle gaps (Phase 6B). For each faculty
     * member and day, only the non-break windows strictly between the faculty's
     * first and last assigned teaching period on that day are examined; every
     * such window that holds no assigned lesson of that faculty costs ONE_SOFT.
     *
     * <p>Free periods are therefore <em>not</em> treated uniformly:
     * <ul>
     *   <li>windows before the first lesson or after the last lesson of a day
     *       are never penalised (a legitimate free window);</li>
     *   <li>days with zero or one teaching period never fire;</li>
     *   <li>break windows are never idle — {@code TimetablePlanningMapper}
     *       excludes {@code isBreak} slots from the {@link PlannableTimeSlot}
     *       value range, so they do not exist as windows to be free;</li>
     *   <li>windows outside the working week never fire — only the
     *       working-day labels are materialised into the value range;</li>
     *   <li>unassigned lessons hold no window and therefore add no teaching
     *       period and no idle window of their own; the missing period they
     *       would have filled is counted only once, as a free window between
     *       the faculty's actual assigned teaching periods.</li>
     * </ul>
     *
     * <p>Only faculty gaps are penalised (section gaps are out of scope for
     * Phase 6B), and the weight is one soft point per idle window, so a
     * compacted schedule always outscores a gapped one at equal unassigned
     * counts. The score therefore ranks: fewer hard violations first, then
     * fewer unassigned lessons, then fewer faculty idle gaps.
     */
    public Constraint idleGap(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getFaculty() != null)
            .groupBy(PlanningLesson::getFaculty,
                lesson -> lesson.getTimeSlot().getDayOfWeek(),
                ConstraintCollectors.toList())
            .join(PlannableTimeSlot.class,
                Joiners.equal((faculty, day, lessons) -> day, PlannableTimeSlot::getDayOfWeek))
            .filter((faculty, day, lessons, slot) -> isIdleSlot(lessons, slot))
            .penalize(HardSoftScore.ONE_SOFT, (faculty, day, lessons, slot) -> 1L)
            .asConstraint(IDLE_GAP);
    }

    /**
     * Soft constraint — cluster a subject's teaching sessions onto as few days
     * as possible (whole-class balanced distribution, Phase 6C). For each
     * (subject, section), the ideal number of teaching days mirrors the Greedy
     * planner's {@code idealDaySizes} exactly: up to 5 weekly hours → one day,
     * otherwise days of 5 each with the remainder folded into the last (6 → two
     * days, 7 → two, 8 → two, 9 → two, 10 → two, 11 → three). Every teaching
     * day beyond that ideal costs ONE_SOFT, so a 5-hour subject taught on
     * Monday alone scores 0 while the same subject spread over five days scores
     * 4.
     *
     * <p>Back-to-back teaching is rewarded on top: for THEORY lessons, each
     * non-consecutive gap between two same-day sessions (their slot orders are
     * not adjacent) costs ONE_SOFT, so a single day of back-to-back sessions
     * outscores the same sessions split across that day's two extremes.
     *
     * <p>LAB lessons keep the original spread semantics: one point per same-day
     * session beyond the first (a session is a consecutive run), so a
     * two-session lab day costs one point while one block on each of two days
     * is free. Clustering labs onto a single day is deliberately not rewarded
     * because the lab-block hard rule fixes each session at its configured
     * block size — the sessions of a {@code sessionBlockSize} 1 lab must remain
     * distinct single-period runs, never merged into one oversized block.
     *
     * <p>The group is split by lesson component ({@code isLab}): a mixed
     * subject — one THEORY-type record that also carries practical hours, e.g.
     * CS691 = 5 theory + 2 practical — produces a theory group and a lab group
     * inside the same (subject, section) key. The theory lessons are then
     * clustered to the ideal days computed from the THEORY lesson count (a
     * subject's theory component is never spread over more days than the
     * college-wide daily cap allows), and the lab lessons keep the block-spread
     * rule. This mirrors the Greedy planner, which builds a distribution plan
     * for theory subjects only and schedules LAB components separately.
     *
     * <p>This is strictly a soft preference; it never blocks a placement the
     * hard rules allow. Sessions are grouped per section, so two sections
     * meeting the same subject on the same day are never merged. Unassigned
     * lessons (no window) and break windows are not part of the value range.
     */
    public Constraint subjectDistribution(ConstraintFactory factory) {
        return factory.forEach(PlanningLesson.class)
            .filter(lesson -> lesson.getTimeSlot() != null && lesson.getSubject() != null)
            .groupBy(TimetableConstraintProvider::subjectSectionKey, ConstraintCollectors.toList())
            .penalize(HardSoftScore.ONE_SOFT,
                (key, lessons) -> distributionPenalty(lessons))
            .asConstraint(SUBJECT_DISTRIBUTION);
    }

    // ============================ helpers ============================

    private static boolean isLab(PlannableSubject subject) {
        return subject != null && "LAB".equalsIgnoreCase(subject.getSubjectType());
    }

    private static boolean isLab(PlannableRoom room) {
        return room != null && "LAB".equalsIgnoreCase(room.getRoomType());
    }

    private static int effectiveDailyCap(PlannableFaculty faculty) {
        Integer ownCap = faculty != null ? faculty.getMaxDailyHours() : null;
        return (ownCap != null && ownCap > 0)
            ? Math.min(COLLEGE_WIDE_MAX_DAILY_HOURS, ownCap)
            : COLLEGE_WIDE_MAX_DAILY_HOURS;
    }

    private static int effectiveWeeklyCap(PlannableFaculty faculty) {
        Integer ownCap = faculty != null ? faculty.getMaxWeeklyHours() : null;
        return (ownCap != null && ownCap > 0) ? ownCap : DEFAULT_MAX_WEEKLY_HOURS;
    }

    private static int requiredCapacityOf(PlanningLesson lesson) {
        return lesson.getRequiredCapacity() != null ? lesson.getRequiredCapacity() : DEFAULT_MIN_CAPACITY;
    }

    /**
     * A window is a faculty idle gap when the faculty teaches some assigned
     * lesson before it and some assigned lesson after it on the same day, and
     * the window itself is not taught by that faculty. The lesson list is
     * already restricted to one faculty and day by the grouping, and the window
     * is a non-break {@link PlannableTimeSlot} by construction.
     */
    private static boolean isIdleSlot(List<PlanningLesson> lessons, PlannableTimeSlot slot) {
        int order = slot.getSlotOrder();
        boolean hasEarlier = false;
        boolean hasLater = false;
        for (PlanningLesson lesson : lessons) {
            int taughtOrder = lesson.getTimeSlot().getSlotOrder();
            if (taughtOrder == order) {
                return false;
            }
            if (taughtOrder < order) {
                hasEarlier = true;
            } else {
                hasLater = true;
            }
        }
        return hasEarlier && hasLater;
    }

    /**
     * Grouping key for the subject-distribution objective: subject + section.
     * The subject is keyed by id rather than instance — the Timefold engine
     * builds one {@link PlannableSubject} per lesson, so instance grouping
     * would split one subject's sessions into single-lesson groups.
     */
    private static String subjectSectionKey(PlanningLesson lesson) {
        return lesson.getSubject().getSubjectId() + "|" + lesson.getSectionId();
    }

    /**
     * Soft penalty for one (subject, section) group.
     *
     * <p>THEORY lessons cluster: {@link #EXTRA_THEORY_DAY_WEIGHT} points per
     * teaching day of ABSOLUTE deviation from the ideal (computed from the THEORY
     * lesson count and the subject's block size, so a mixed subject's theory
     * component is scored on its own double/single pattern and never spread over
     * more days than the daily cap allows, and never packed tighter than the
     * pattern requires), plus one point per non-consecutive gap between two
     * same-day sessions. LAB lessons keep the original spread rule: one point
     * per same-day session beyond the first, where a session is a consecutive
     * run of periods. Practical blocks are strictly consecutive by the hard
     * lab-block rule, so a 2-period block is one session; two blocks on the
     * same day are a two-session day (one point), while one block on each of
     * two days is a clean spread (zero points). Clustering labs onto one day is
     * deliberately not rewarded — the session must render at its configured
     * block size as a separate run, so the three single-period sessions of a
     * {@code sessionBlockSize} 1 lab must not be merged into one oversized
     * block.
     *
     * <p>The group is split by {@code isLab} first so a mixed subject (THEORY
     * record with practical hours) gets both a theory cluster objective and a
     * lab spread rule instead of whichever semantics its first lesson happens
     * to carry.
     */
    private static long distributionPenalty(List<PlanningLesson> lessons) {
        if (lessons.isEmpty()) {
            return 0L;
        }
        List<PlanningLesson> theory = new ArrayList<>();
        List<PlanningLesson> lab = new ArrayList<>();
        for (PlanningLesson lesson : lessons) {
            (lesson.isLab() ? lab : theory).add(lesson);
        }
        long penalty = 0L;
        if (!theory.isEmpty()) {
            penalty += theoryDistributionPenalty(theory);
        }
        for (List<Integer> orders : ordersByDay(lab).values()) {
            penalty += Math.max(0L, (long) consecutiveRuns(orders) - 1L);
        }
        return penalty;
    }

    /**
     * Theory-clustering penalty:
     * {@code EXTRA_THEORY_DAY_WEIGHT} soft points per teaching day beyond the
     * ideal days the THEORY lesson count needs under the daily teaching cap,
     * plus one soft point per non-consecutive gap between two same-day theory
     * sessions. The day weight dominates the gap weight so fewer teaching days
     * is always preferred over same-day compactness (the lunch-break rule makes
     * a 5-lesson day carry one forced gap, and day == gap weight would make
     * 3 days look as good as 2).
     */
    private static long theoryDistributionPenalty(List<PlanningLesson> lessons) {
        Integer blockOpt = (lessons.get(0).getSubject() != null)
            ? lessons.get(0).getSubject().getSessionBlockSize() : null;
        int effectiveBlock = (blockOpt != null && blockOpt >= 1) ? blockOpt : 1;
        long ideal = idealTeachingDays(lessons.size(), effectiveBlock);
        Map<String, List<Integer>> byDay = ordersByDay(lessons);
        // TWO-SIDED deviation from the intended teaching-day count.
        //
        // The previous max(0, usedDays - ideal) form only ever punished
        // SPREADING, never compaction. For block size 2 the intended day count is
        // the double/single pattern (5 days for H=5..10), so a solution that
        // packed the same lessons into 3 days scored exactly the same as the
        // intended one (0 day penalty) and typically also scored better on the
        // gap term, because a 5-lesson day always carries the forced lunch gap
        // while one-lesson-per-day carries none. The solver therefore had no
        // reason at all to prefer the intended distribution and picked the
        // packed one. Penalising under-ideal compaction by the same weight makes
        // the intended distribution the optimum for block size 2 and for a
        // mixed theory/practical subject (whose theory component is scored here
        // and whose lab component is scored by the lab spread rule).
        //
        // For block size 1 the ideal is ceil(n / 5), which the hard college-wide
        // daily teaching cap already forces, so the under-ideal term can never
        // fire on a feasible solution and the penalty stays a pure "do not
        // spread" term.
        // (Superseded: a NORMAL subject now holds at most 2 periods a day and its
        // 6th+ periods are consecutive pairs, so the ideal is always the
        // double/single day count from idealTeachingDays and the two-sided term
        // is what steers the solver to it.)
        long penalty = Math.abs((long) byDay.size() - ideal) * EXTRA_THEORY_DAY_WEIGHT;
        for (List<Integer> orders : byDay.values()) {
            List<Integer> sorted = new ArrayList<>(orders);
            sorted.sort(Integer::compareTo);
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i) != sorted.get(i - 1) + 1) {
                    penalty++;
                }
            }
        }
        return penalty;
    }

    /**
     * Slot orders of a lesson list grouped by day.
     */
    private static Map<String, List<Integer>> ordersByDay(List<PlanningLesson> lessons) {
        Map<String, List<Integer>> ordersByDay = new HashMap<>();
        for (PlanningLesson lesson : lessons) {
            ordersByDay.computeIfAbsent(lesson.getTimeSlot().getDayOfWeek(), k -> new ArrayList<>())
                .add(lesson.getTimeSlot().getSlotOrder());
        }
        return ordersByDay;
    }

    /**
     * Slot orders of a lesson list grouped by day, NORMAL (non-LAB) lessons only —
     * the same theory/practical split the soft distribution penalty uses.
     */
    private static Map<String, List<Integer>> theoryOrdersByDay(List<PlanningLesson> lessons) {
        Map<String, List<Integer>> ordersByDay = new HashMap<>();
        for (PlanningLesson lesson : lessons) {
            if (lesson.isLab()) continue;
            ordersByDay.computeIfAbsent(lesson.getTimeSlot().getDayOfWeek(), k -> new ArrayList<>())
                .add(lesson.getTimeSlot().getSlotOrder());
        }
        return ordersByDay;
    }

    /**
     * Number of contiguous runs in a day's slot orders: adjacent orders belong
     * to the same run, a gap starts a new one.
     */
    private static int consecutiveRuns(List<Integer> orders) {
        List<Integer> sorted = new ArrayList<>(orders);
        sorted.sort(Integer::compareTo);
        int runs = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i == 0 || sorted.get(i) != sorted.get(i - 1) + 1) {
                runs++;
            }
        }
        return runs;
    }

    /**
     * Ideal number of teaching days for {@code lessonCount} theory lessons of a
     * NORMAL subject, mirroring the Greedy planner's
     * {@code SubjectDemandService#idealDaySizes} business rule: a normal subject
     * holds at most {@link #MAX_PERIODS_PER_SUBJECT_PER_DAY} periods a day and,
     * when it holds 2, they are back-to-back, so the ideal day count is
     * {@code doubleDays + singleDays} for
     * <pre>
     *   doubleDays = max(0, lessonCount - 5)
     *   singleDays = lessonCount - (doubleDays * 2)
     * </pre>
     * i.e. 5/wk → 5 days, 6/wk → 5, 7/wk → 5, 8/wk → 5, 9/wk → 5, 10/wk → 5,
     * 11/wk → 6, 12/wk → 6. The count is the THEORY lesson count of the group —
     * a mixed subject's practical component is scored separately by the lab
     * spread rule.
     *
     * <p>The subject's stored {@code blockSize} no longer changes the pattern: the
     * 2-period daily ceiling decides it, so a subject stored with the default
     * {@code sessionBlockSize = 1} still gets its 6th+ periods as consecutive
     * pairs instead of being spread one period per day. The one exception is a
     * subject whose whole weekly demand is one explicitly requested consecutive
     * block (2 hours/week with {@code sessionBlockSize} 2), which keeps that
     * block on a single day.
     */
    private static long idealTeachingDays(int lessonCount, int blockSize) {
        if (lessonCount <= 0) {
            return 1L;
        }
        // A subject whose ENTIRE weekly demand is one explicitly requested
        // 2-period consecutive block (2 hours/week with sessionBlockSize 2) keeps
        // that block: one day, two consecutive periods. Without this the formula
        // below yields doubleDays = max(0, 2 - 5) = 0 and singleDays = 2, i.e.
        // one day per period, which silently discards the explicit 2xCONSECUTIVE
        // request. Only reached when the subject actually stored 2, since the
        // default/unset configuration reports 1.
        if (blockSize == MAX_PERIODS_PER_SUBJECT_PER_DAY
                && lessonCount == MAX_PERIODS_PER_SUBJECT_PER_DAY) {
            return 1L;
        }
        int doubleDays = Math.max(0, lessonCount - SINGLE_PERIOD_WEEK_LIMIT);
        int singleDays = lessonCount - doubleDays * MAX_PERIODS_PER_SUBJECT_PER_DAY;
        if (singleDays < 0) {
            // More than the limit above the first threshold (e.g. H=11, 12):
            // pair as much as possible and keep the odd period as a single.
            doubleDays = lessonCount / MAX_PERIODS_PER_SUBJECT_PER_DAY;
            singleDays = lessonCount - doubleDays * MAX_PERIODS_PER_SUBJECT_PER_DAY;
        }
        return doubleDays + singleDays;
    }

    private static boolean hasDepartmentPermission(PlannableFaculty faculty, Long targetDepartmentId,
            PlannableSubject subject) {
        if (faculty == null || targetDepartmentId == null) {
            return false;
        }
        if (subject != null && subject.getAssignedFacultyId() != null
                && subject.getAssignedFacultyId().equals(faculty.getFacultyId())) {
            return true;
        }
        if (faculty.getDepartmentId() != null && Objects.equals(faculty.getDepartmentId(), targetDepartmentId)) {
            return true;
        }
        if (faculty.getTeachingDepartments() != null && !faculty.getTeachingDepartments().isBlank()) {
            return Arrays.stream(faculty.getTeachingDepartments().split(","))
                .map(String::trim)
                .anyMatch(token -> token.equals(String.valueOf(targetDepartmentId))
                    || (faculty.getDepartmentName() != null
                        && faculty.getDepartmentName().equalsIgnoreCase(token)));
        }
        return false;
    }

    private static boolean hasAssignedSubject(PlannableFaculty faculty, PlannableSubject subject) {
        if (faculty == null || subject == null) {
            return false;
        }
        if (subject.getAssignedFacultyId() != null && subject.getAssignedFacultyId().equals(faculty.getFacultyId())) {
            return true;
        }
        if (faculty.getAssignedSubjectCodes() != null && !faculty.getAssignedSubjectCodes().isBlank()) {
            boolean codeMatched = Arrays.stream(faculty.getAssignedSubjectCodes().split(","))
                .map(String::trim)
                .anyMatch(code -> code.equalsIgnoreCase(subject.getSubjectCode()));
            if (codeMatched) {
                return true;
            }
        }
        if (faculty.getSpecialization() != null && !faculty.getSpecialization().isBlank()
                && subject.getSubjectName() != null && !subject.getSubjectName().isBlank()) {
            String spec = faculty.getSpecialization().toLowerCase();
            String name = subject.getSubjectName().toLowerCase();
            if (name.contains(spec) || spec.contains(name.substring(0, Math.min(4, name.length())))) {
                return true;
            }
        }
        return subject.getAssignedFacultyId() == null;
    }

    /**
     * A practical session is valid only when it is placed in full: every period
     * assigned, all on one day, in strictly consecutive non-break slots, with
     * the session size matching the configured block size. A session whose
     * periods are ALL unassigned is valid — it is the "cannot be placed" state,
     * penalised only by the soft unassigned-lessons objective. A PARTIAL session
     * (some periods placed, some not) is invalid: the solver must complete the
     * block or drop the whole session, mirroring the Greedy engine's
     * all-or-nothing practical-block semantics.
     */
    private static boolean isValidLabSession(List<PlanningLesson> lessons) {
        if (lessons.isEmpty()) {
            return true;
        }
        boolean allUnassigned = lessons.stream()
            .allMatch(lesson -> lesson.getRoom() == null || lesson.getTimeSlot() == null);
        if (allUnassigned) {
            return true;
        }
        int expectedSize = lessons.get(0).getPracticalSessionSize() != null
            ? lessons.get(0).getPracticalSessionSize()
            : practicalBlockSizeFor(lessons.get(0));
        if (lessons.size() != expectedSize) {
            return false;
        }
        String day = lessons.get(0).getTimeSlot().getDayOfWeek();
        List<Integer> orders = new ArrayList<>(lessons.size());
        for (PlanningLesson lesson : lessons) {
            if (lesson.getRoom() == null || lesson.getTimeSlot() == null) {
                return false;
            }
            if (!day.equals(lesson.getTimeSlot().getDayOfWeek())) {
                return false;
            }
            orders.add(lesson.getTimeSlot().getSlotOrder());
        }
        orders.sort(Comparator.naturalOrder());
        for (int i = 1; i < orders.size(); i++) {
            if (orders.get(i) != orders.get(i - 1) + 1) {
                return false;
            }
        }
        return true;
    }

/**
     * Per-subject practical block size for a lesson: the subject's own
     * {@code sessionBlockSize} is the source of truth and is honored verbatim
     * (1 → one consecutive period, 2 → two, 3 → three). The global fallback
     * applies only when the subject is null.
     */
    private static int practicalBlockSizeFor(PlanningLesson lesson) {
        if (lesson.getSubject() == null) {
            return globalPracticalBlockSize;
        }
        return lesson.getSubject().getSessionBlockSize() != null
            ? lesson.getSubject().getSessionBlockSize()
            : 1;
    }
}
