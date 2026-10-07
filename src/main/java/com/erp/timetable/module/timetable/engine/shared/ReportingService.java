package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.timetable.entity.Timetable;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Computes the optimization score that summarises the quality of a generated
 * timetable.
 */
@Service
public class ReportingService {

    /**
     * Computes the optimization score from a blend of soft goals and penalties.
     *
     * Hard/medium conflict penalties dominate; PREFERRED-slot usage is rewarded;
     * placements that had to fall back to a non-planned day are penalised. The
     * result is clamped to [0, 100].
     */
    public int computeOptimizationScore(Timetable timetable,
            Map<String, String> availabilityMap, int offTargetPlacements) {

        int hardConflicts = (int) timetable.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .count();
        int mediumConflicts = (int) timetable.getConflicts().stream()
            .filter(c -> "MEDIUM".equalsIgnoreCase(c.getSeverity()))
            .count();

        double score = 100.0;
        score -= hardConflicts * 6.0;
        score -= mediumConflicts * 3.0;
        score -= Math.min(10.0, offTargetPlacements * 2.0);

        int total = timetable.getEntries().size();
        if (total > 0) {
            long preferred = timetable.getEntries().stream()
                .filter(e -> e.getFaculty() != null && e.getTimeSlot() != null)
                .filter(e -> "PREFERRED".equalsIgnoreCase(availabilityMap.get(
                    e.getFaculty().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId())))
                .count();
            score += Math.min(10.0, (preferred * 10.0) / total);
        }

        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }
}
