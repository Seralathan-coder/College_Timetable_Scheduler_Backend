package com.erp.timetable.module.timetable.planning.model;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.*;

/**
 * A single scheduled period as a Timefold planning entity.
 *
 * <p>Each lesson is either <em>assigned</em> — a room and a (day, time slot)
 * window, both set — or <em>unassigned</em> — both {@code null}. The two
 * planning variables allow unassigned ({@code allowsUnassigned = true}): when
 * no combination of room and window satisfies every hard constraint, the solver
 * leaves the lesson unassigned instead of forcing a placement that violates a
 * hard rule. An unassigned lesson is only penalised by the soft "Minimize
 * unassigned lessons" constraint, never by a hard rule (every hard constraint
 * filters unassigned lessons out).
 *
 * <p>The subject and its assigned faculty are immutable problem facts carried
 * by the lesson — faculty is bound to the subject, never a decision variable.
 * {@link #locked} marks entries that must survive regeneration unchanged
 * (pinned, so they are always assigned and never moved).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@PlanningEntity
public class PlanningLesson {

    private Long id;
    private Long sourceEntryId; // id of the TimetableEntry this lesson maps to (null for new lessons)
    private PlannableSubject subject;
    private PlannableFaculty faculty;
    private Long sectionId;
    private Long departmentId; // department this lesson is taught for (Department Permission target)
    private Long academicYearId; // academic year of the lesson's section (Room Scope source)
    private Integer requiredCapacity; // section student strength, default 40 (Room Capacity source)
    private boolean isLab;

    /**
     * Practical-session grouping (Greedy parity). Every practical lesson belongs
     * to exactly one session — a single LAB session of the subject's per-subject
     * practical block size ({@code sessionBlockSize} 1/2/3, remainder sessions
     * keep their own size) — identified by {@link #practicalSessionId} with an
     * expected period count {@link #practicalSessionSize}. The lab-block hard
     * constraint requires each session to be placed in FULL (all periods, one
     * day, strictly consecutive) or not at all, mirroring the Greedy engine's
     * all-or-nothing block semantics. Lessons reconstructed from persisted
     * entries derive their session from the actual consecutive runs.
     */
    private Long practicalSessionId;
    private Integer practicalSessionSize;

    @PlanningPin
    private boolean locked;

    @PlanningVariable(valueRangeProviderRefs = "roomRange", allowsUnassigned = true)
    private PlannableRoom room;

    @PlanningVariable(valueRangeProviderRefs = "timeSlotRange", allowsUnassigned = true)
    private PlannableTimeSlot timeSlot;
}
