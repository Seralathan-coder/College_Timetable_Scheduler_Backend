package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableConflict;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Records conflicts on a timetable and runs post-generation conflict validation.
 */
@Service
@Slf4j
public class ConflictRecorderService {

    /**
     * Adds a conflict to the timetable, skipping exact duplicates. This prevents
     * duplicate conflict rows from accumulating across partial regenerations.
     *
     * @return true if a new conflict was added, false if an identical one existed
     */
    public boolean addConflict(Timetable timetable, String type, String description, String severity) {
        boolean exists = timetable.getConflicts().stream()
            .anyMatch(c -> Objects.equals(c.getConflictType(), type)
                && Objects.equals(c.getDescription(), description));
        if (exists) return false;
        timetable.addConflict(TimetableConflict.builder()
            .conflictType(type)
            .description(description)
            .severity(severity)
            .build());
        return true;
    }

    /**
     * After the main scheduling loop, scan all generated entries to catch any
     * residual conflicts that slipped through (e.g., data integrity issues).
     *
     * @return number of new conflict records added
     */
    public int runPostValidation(Timetable timetable) {
        int count = 0;
        List<TimetableEntry> entries = timetable.getEntries();
        if (entries == null || entries.isEmpty()) return 0;

        // Group entries by (day, slotNumber) to detect faculty and room double-bookings
        Map<String, List<TimetableEntry>> byDaySlot = new HashMap<>();
        for (TimetableEntry entry : entries) {
            if (entry.getTimeSlot() == null) continue;
            String key = entry.getDayOfWeek() + "_" + entry.getTimeSlot().getId();
            byDaySlot.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<String, List<TimetableEntry>> bucket : byDaySlot.entrySet()) {
            List<TimetableEntry> group = bucket.getValue();
            if (group.size() <= 1) continue;

            // Check faculty clash
            Map<Long, Long> facultyCount = new HashMap<>();
            for (TimetableEntry e : group) {
                if (e.getFaculty() != null) {
                    long prev = facultyCount.getOrDefault(e.getFaculty().getId(), 0L);
                    if (prev > 0) {
                        if (addConflict(timetable, "FACULTY_CLASH_DETECTED",
                            "Faculty " + e.getFaculty().getFullName()
                                + " is double-booked on " + e.getDayOfWeek()
                                + " slot " + e.getTimeSlot().getSlotLabel(),
                            "HIGH")) {
                            count++;
                        }
                    }
                    facultyCount.put(e.getFaculty().getId(), prev + 1);
                }
            }

            // Check room clash
            Map<Long, Long> roomCount = new HashMap<>();
            for (TimetableEntry e : group) {
                if (e.getClassroom() != null) {
                    long prev = roomCount.getOrDefault(e.getClassroom().getId(), 0L);
                    if (prev > 0) {
                        if (addConflict(timetable, "ROOM_CLASH_DETECTED",
                            "Room " + e.getClassroom().getRoomNumber()
                                + " is double-booked on " + e.getDayOfWeek()
                                + " slot " + e.getTimeSlot().getSlotLabel(),
                            "HIGH")) {
                            count++;
                        }
                    }
                    roomCount.put(e.getClassroom().getId(), prev + 1);
                }
            }
        }

        if (count == 0) {
            log.info("  ✓ Post-validation passed — no conflicts detected");
        } else {
            log.warn("  ⚠ Post-validation found {} conflict(s)", count);
        }
        return count;
    }
}
