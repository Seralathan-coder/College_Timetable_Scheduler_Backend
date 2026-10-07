package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Builder
public class ConstraintContext {
    private final Map<String, Set<Long>> facultyOccupancy;  // slotKey -> Set<FacultyId>
    private final Map<String, Set<Long>> roomOccupancy;     // slotKey -> Set<RoomId>
    private final Map<String, Boolean> sectionOccupancy;    // slotKey -> true if current section has class
    private final Map<String, Integer> facultyDailyHours;   // facultyId_day -> count
    private final Map<Long, Integer> facultyWeeklyHours;   // facultyId -> count
    private final Map<String, String> availabilityMap;     // facultyId_day_slotId -> status
    private final Map<Long, Integer> roomUsageCount;        // roomId -> number of subjects already assigned to it (for round-robin room balancing)
    private final TimetableEntryRepository entryRepository;

    /**
     * Tracks the exact slot numbers (period numbers) a faculty member is already
     * assigned on each day.  Key = "facultyId_day", Value = sorted mutable list
     * of slotNumbers already occupied.
     *
     * Used exclusively by ConsecutiveTeachingConstraint to enforce:
     *   - max 2 consecutive teaching periods per block
     *   - mandatory 2 free periods after every 2-consecutive block
     */
    private final Map<String, List<Integer>> facultyDaySlots; // "facultyId_day" -> sorted slot numbers
}
