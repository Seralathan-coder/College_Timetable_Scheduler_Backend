package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles locked-entry preservation for partial regeneration.
 *
 * On a full regeneration all entries and conflicts are discarded. On a partial
 * regeneration (regenerateOnlyUnlocked=true) unlocked entries are removed, locked
 * entries are kept and re-registered into the occupancy context by the caller,
 * and stale conflicts are cleared because they are recomputed from scratch by
 * post-validation.
 */
@Service
@Slf4j
public class LockPreservationService {

    /**
     * Prepares the timetable for (re)generation.
     *
     * @return the locked entries that survive the operation (empty for a full
     *         regeneration); the caller must re-register them in the occupancy
     *         context so subsequent constraint checks reflect their slots.
     */
    public List<TimetableEntry> prepareForGeneration(Timetable timetable, boolean regenerateOnlyUnlocked) {
        if (regenerateOnlyUnlocked) {
            timetable.getEntries().removeIf(e -> !Boolean.TRUE.equals(e.getIsLocked()));
            // Conflicts are recomputed from scratch by post-validation below,
            // so stale conflicts from a previous run must not linger.
            timetable.getConflicts().clear();
            return new ArrayList<>(timetable.getEntries());
        }
        timetable.getEntries().clear();
        timetable.getConflicts().clear();
        return List.of();
    }

    /**
     * Remembers which days are already covered by locked entries per subject, so
     * the theory distribution plan can avoid scheduling new periods on those days.
     */
    public Map<Long, Set<String>> lockedDaysBySubject(Timetable timetable) {
        Map<Long, Set<String>> lockedDays = new HashMap<>();
        for (TimetableEntry e : timetable.getEntries()) {
            if (Boolean.TRUE.equals(e.getIsLocked()) && e.getSubject() != null) {
                lockedDays.computeIfAbsent(e.getSubject().getId(), k -> new HashSet<>())
                    .add(e.getDayOfWeek());
            }
        }
        return lockedDays;
    }

    /**
     * For partial regeneration, locked entries already contribute toward each
     * subject's weekly coverage. Reduces the required count so the engine never
     * double-schedules locked periods.
     */
    public void reduceWeeklyHoursForLockedSubjects(Timetable timetable, List<Subject> subjects,
            Map<Long, Integer> weeklyHoursMap) {
        for (Subject s : subjects) {
            long lockedCount = timetable.getEntries().stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsLocked()) && e.getSubject() != null
                    && e.getSubject().getId().equals(s.getId()))
                .count();
            int remaining = Math.max(0, weeklyHoursMap.getOrDefault(s.getId(), 1) - (int) lockedCount);
            weeklyHoursMap.put(s.getId(), remaining);
        }
    }
}
