package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Pre-generation weekly-capacity validation for a timetable.
 *
 * <p>The weekly teaching capacity of a class is derived from the existing
 * configured master data — never hardcoded:
 * <pre>
 *   weeklyCapacity = workingDays &times; non-break TeachingSlots
 * </pre>
 * where the working days come from {@link SubjectDemandService#WORKING_DAYS}
 * and the non-break teaching slots from the {@code time_slots} master (the same
 * source the engines use). On the standard configuration this is
 * 6 days &times; 7 teaching periods = 42 available slots, but the calculation
 * follows the configured master at runtime.
 *
 * <p>{@code totalDemand} is the sum of {@code theoryHours + practicalHours}
 * over every applicable subject for the timetable's section + semester (the
 * exact curriculum {@link CurriculumDataLoader} feeds the engines), so it is
 * generic across every department / academic year / section / semester and
 * never depends on department or section names.
 *
 * <p>Demand &le; capacity is always acceptable (the unused slots are Free
 * Periods). Demand &gt; capacity is a capacity/constraint problem that the
 * caller reports through the existing timetable conflict model
 * ({@code CAPACITY_EXCEEDED}) so an over-capacity schedule is never silently
 * treated as complete.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyCapacityValidator {

    /** Conflict type reported when totalDemand exceeds the weekly capacity. */
    public static final String CAPACITY_EXCEEDED = "CAPACITY_EXCEEDED";

    private final CurriculumDataLoader curriculumDataLoader;
    private final TimeSlotRepository timeSlotRepository;

    /**
     * Computes the weekly demand vs. capacity for the given timetable.
     */
    public CapacityReport validate(Timetable timetable) {
        int totalDemand = curriculumDataLoader.loadSubjectsForTimetable(timetable).stream()
            .mapToInt(s -> (s.getTheoryHours() != null ? s.getTheoryHours() : 0)
                + (s.getPracticalHours() != null ? s.getPracticalHours() : 0))
            .sum();

        long teachingSlotsPerDay = timeSlotRepository.findAllByOrderBySlotOrderAsc().stream()
            .filter(s -> !Boolean.TRUE.equals(s.getIsBreak()))
            .count();
        int workingDays = SubjectDemandService.WORKING_DAYS.size();
        int weeklyCapacity = Math.toIntExact(teachingSlotsPerDay) * workingDays;

        log.info("Weekly capacity check: demand={} required periods, capacity={} available slots ({} working days x {} teaching slots/day)",
            totalDemand, weeklyCapacity, workingDays, teachingSlotsPerDay);
        return new CapacityReport(totalDemand, weeklyCapacity);
    }

    /**
     * Result of the capacity validation.
     */
    public record CapacityReport(int totalDemand, int weeklyCapacity) {

        public boolean exceeded() {
            return totalDemand > weeklyCapacity;
        }

        /** Message for the {@link #CAPACITY_EXCEEDED} conflict record. */
        public String message() {
            return "Weekly workload exceeds available capacity: " + totalDemand
                + " required periods for " + weeklyCapacity + " available slots.";
        }
    }
}