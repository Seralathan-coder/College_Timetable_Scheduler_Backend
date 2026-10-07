package com.erp.timetable.module.timetable.planning.model;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The Timefold planning model for a single timetable.
 *
 * The solver assigns each {@link PlanningLesson} a {@code room} (backed by the
 * {@code roomRange} value range) and a {@code timeSlot} window (backed by the
 * {@code timeSlotRange} value range). Subjects, faculty, rooms and availability
 * facts are immutable problem facts.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@PlanningSolution
public class SchedulingSolution {

    private Long timetableId;
    private String academicSession;
    private Long departmentId;
    private Long sectionId;
    private Integer semester;

    @PlanningScore
    private HardSoftScore score;

    @PlanningEntityCollectionProperty
    private List<PlanningLesson> lessons = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<PlannableSubject> subjects = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<PlannableFaculty> faculty = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<AvailabilityFact> availabilityFacts = new ArrayList<>();

    @ProblemFactCollectionProperty
    @Builder.Default
    private List<OccupancyFact> occupancyFacts = new ArrayList<>();

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "roomRange")
    private List<PlannableRoom> rooms = new ArrayList<>();

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "timeSlotRange")
    private List<PlannableTimeSlot> timeSlots = new ArrayList<>();
}
