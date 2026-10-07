package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.subject.entity.Subject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LabConsecutiveBlockConstraint implements SchedulingConstraint {

    /** Global fallback block size (used when a subject has no explicit per-subject block). */
    private final int practicalBlockSize;

    public LabConsecutiveBlockConstraint(
            @Value("${timetable.scheduler.practical-block-size:2}") int practicalBlockSize) {
        this.practicalBlockSize = practicalBlockSize;
    }

    @Override
    public String getConstraintName() {
        return "LAB_CONSECUTIVE_BLOCK";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (!placement.isLab()) {
            return true; // Not a practical lesson, constraint trivially satisfied
        }

        int blockSize = resolvePracticalBlockSize(placement);

        List<TimeSlot> slots = placement.getSlots();
        if (slots == null || slots.isEmpty() || slots.size() > blockSize) {
            return false; // A practical session may occupy at most `blockSize` periods
        }

        for (int i = 0; i < slots.size(); i++) {
            TimeSlot current = slots.get(i);
            if (Boolean.TRUE.equals(current.getIsBreak())) {
                return false; // Cannot span over break
            }
            if (i > 0) {
                TimeSlot prev = slots.get(i - 1);
                if (current.getSlotOrder() != prev.getSlotOrder() + 1) {
                    return false; // Periods are not strictly consecutive
                }
            }
        }
        return true;
    }

    /**
     * The maximum number of consecutive periods a practical session of
     * {@code placement}'s subject may occupy.
     *
     * <p>The bound is the subject's full weekly practical demand
     * ({@code practicalHours}), because that is the one and only practical
     * session length the Greedy engine builds: {@code schedulePracticalBlock}
     * receives {@code int blockSize = practicalHours} and derives its candidate
     * windows from {@code buildConsecutiveWindows(allSlots, blockSize)}, i.e. one
     * continuous back-to-back block spanning all of the subject's practical
     * hours. The configured {@code sessionBlockSize} is deliberately NOT used
     * here — in the Greedy engine it is a THEORY-only control and the practical
     * "Consecutive Periods per Session" setting is ignored by design, so allowing
     * a longer session would loosen the bound without any producer to justify it.
     *
     * <p>The practical lesson is identified by {@link CandidatePlacement#isLab},
     * which is deliberately decoupled from {@code subjectType} (CS142 and CS795
     * are THEORY subjects that carry practical hours), so subject type is NOT
     * consulted.
     *
     * <p>This is only an upper bound on session LENGTH. It does not relax any
     * timetable-protection rule: a session may never span a break, its periods
     * must be strictly consecutive, it occupies a single day, and room type,
     * room capacity, room scope, section/faculty/room clash, the Saturday
     * prohibition, faculty daily/weekly limits, department permission and
     * tenant isolation are all evaluated by the other constraints in the
     * Greedy engine's hard-constraint pipeline and by the service-layer
     * RBAC/tenant guards. This class never selects a room, a day or a faculty
     * member; it only length-checks an already-built candidate.
     */
    private int resolvePracticalBlockSize(CandidatePlacement placement) {
        Subject subject = placement.getSubject();
        if (subject == null) {
            return Math.max(1, practicalBlockSize);
        }
        Integer practical = subject.getPracticalHours();
        return (practical != null && practical >= 1) ? practical : Math.max(1, practicalBlockSize);
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        int blockSize = resolvePracticalBlockSize(placement);
        return "Practical lesson " + placement.getSubject().getSubjectCode() +
            " requires " + blockSize + " consecutive non-break periods in a single day";
    }
}
