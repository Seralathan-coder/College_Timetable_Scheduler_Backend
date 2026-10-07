package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.subject.entity.Subject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Calculates subject demand — weekly hours per subject and session block sizes —
 * and builds the weekly distribution plan for theory subjects.
 *
 * All logic is deterministic and depends only on master data (no randomness).
 */
@Service
@Slf4j
public class SubjectDemandService {

    public static final List<String> WORKING_DAYS =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");

    /**
     * Global fallback practical block size
     * ({@code timetable.scheduler.practical-block-size}, default 2). Used ONLY
     * when a subject has no stored block configuration (null/absent value); it
     * never overrides an explicit per-subject sessionBlockSize of 1, 2 or 3.
     */
    @Value("${timetable.scheduler.practical-block-size:2}")
    private int globalPracticalBlockSize;

    /**
     * Calculates how many periods per week each subject requires: the sum of its
     * theory and practical hours, independent of the subject type. A subject with
     * zero theory and zero practical hours contributes nothing.
     */
    public Map<Long, Integer> calculateWeeklyHours(List<Subject> subjects) {
        Map<Long, Integer> map = new HashMap<>();
        for (Subject s : subjects) {
            int theory = s.getTheoryHours() != null ? s.getTheoryHours() : 0;
            int practical = s.getPracticalHours() != null ? s.getPracticalHours() : 0;
            map.put(s.getId(), theory + practical);
        }
        return map;
    }

    public void logSubjectWeeklyHours(List<Subject> subjects, Map<Long, Integer> weeklyMap) {
        for (Subject s : subjects) {
            log.info("  {} [{}] — {} period(s)/week", s.getSubjectCode(), s.getSubjectType(),
                weeklyMap.getOrDefault(s.getId(), 0));
        }
    }

    /**
     * Logs the per-subject demand breakdown used to verify 42/42 scheduling:
     * {@code Subject | Theory | Practical | Expected (= theory + practical) |
     * Created lessons}. When {@code createdLessonsBySubject} is null, the
     * "Created" column reports the expected count (the lesson plan every engine
     * must produce before solving).
     */
    public void logDemandBreakdown(List<Subject> subjects, Map<Long, Integer> createdLessonsBySubject) {
        log.info("  ── SUBJECT DEMAND BREAKDOWN ──────────────────────────────────────");
        log.info("  {:<12} {:<8} {:<10} {:<10} {:<12}", "Subject", "Theory", "Practical", "Expected", "Created");
        int expectedTotal = 0;
        int createdTotal = 0;
        for (Subject s : subjects) {
            int theory = s.getTheoryHours() != null ? s.getTheoryHours() : 0;
            int practical = s.getPracticalHours() != null ? s.getPracticalHours() : 0;
            int expected = theory + practical;
            int created = createdLessonsBySubject != null
                ? createdLessonsBySubject.getOrDefault(s.getId(), 0) : expected;
            expectedTotal += expected;
            createdTotal += created;
            log.info("  {:<12} {:<8} {:<10} {:<10} {:<12}",
                s.getSubjectCode(), theory, practical, expected, created);
        }
        log.info("  ──────────────────────────────────────────────────────────────────");
        log.info("  {:<12} {:<8} {:<10} {:<10} {:<12}", "TOTAL", "-", "-", expectedTotal, createdTotal);
        log.info("  DEMAND CHECK: expected={}, created={}", expectedTotal, createdTotal);
    }

    /**
     * Returns the consecutive-period block size for a theory subject, clamped to
     * {@link #MAX_PERIODS_PER_SUBJECT_PER_DAY} (1–2). A NORMAL subject holds at
     * most 2 periods a day, so a stored 3 is not a reachable placement for one and
     * is clamped rather than reported. LAB subjects always fall back to 1 here —
     * their practical sessions are blocked by the lab scheduler via
     * {@link #getPracticalBlockSize(Subject)}, which still honours a 3-period lab
     * block.
     */
    public int getSessionBlockSize(Subject subject) {
        if ("LAB".equalsIgnoreCase(subject.getSubjectType())) return 1;
        Integer blockSize = subject.getSessionBlockSize();
        if (blockSize == null || blockSize < 1) return 1;
        return Math.min(blockSize, MAX_PERIODS_PER_SUBJECT_PER_DAY);
    }

    /**
     * Returns the consecutive-period block size for a subject's PRACTICAL
     * component (the size of a single practical session in a LAB room).
     *
     * <p>The Subject Creator's configured {@code sessionBlockSize} is the source
     * of truth and is honored verbatim:
     * <ul>
     *   <li>1 → each practical session occupies ONE consecutive period,</li>
     *   <li>2 → TWO consecutive periods,</li>
     *   <li>3 → THREE consecutive periods.</li>
     * </ul>
     * The global {@code timetable.scheduler.practical-block-size} (default 2) is
     * used ONLY when the stored value is null/absent (detached planning models
     * without a mapped value); it never overrides an explicit 1.
     */
    public int getPracticalBlockSize(Subject subject) {
        Integer blockSize = subject.getSessionBlockSize();
        if (blockSize == null) {
            return globalPracticalBlockSize;
        }
        return Math.max(blockSize, 1);
    }

    /**
     * Maximum periods a NORMAL subject (THEORY / GAME / OTHER) may occupy on a
     * single day. A second period on the same day is only ever planned as a
     * consecutive pair, so a normal subject can never hold 3 or more periods in
     * one day. LAB subjects are unaffected — their practical hours are placed by
     * the lab scheduler as one strict consecutive block.
     */
    public static final int MAX_PERIODS_PER_SUBJECT_PER_DAY = 2;

    /**
     * Weekly period count up to which a NORMAL subject is spread as single
     * periods on separate days. Above this count the surplus periods are
     * planned as consecutive pairs.
     */
    public static final int SINGLE_PERIOD_WEEK_LIMIT = 5;

    /**
     * One theory session: a run of {@code blockSize} consecutive periods (or a
     * single period when {@code blockSize == 1}) to be placed on {@code day}.
     */
    public record DistributionEntry(String day, int blockSize) {
    }

    /**
     * Builds the weekly theory distribution plan for NORMAL subjects
     * (THEORY / GAME / OTHER). A normal subject NEVER occupies more than
     * {@link #MAX_PERIODS_PER_SUBJECT_PER_DAY} periods on a day, and when it
     * takes two they are planned as one consecutive pair. The pattern is derived
     * purely from the subject's own weekly hours {@code H}:
     *
     * <pre>
     *   doubleDays = max(0, H - 5)
     *   singleDays = H - (doubleDays * 2)
     * </pre>
     *
     * <p>which yields {@code doubleDays} days carrying a back-to-back pair and
     * {@code singleDays} days carrying one period each:
     * <ul>
     *   <li>5/wk → 1+1+1+1+1 (5 single days, no pair)</li>
     *   <li>6/wk → 2+1+1+1+1 (exactly one pair)</li>
     *   <li>7/wk → 2+2+1+1+1 (exactly two pairs)</li>
     *   <li>8/wk → 2+2+2+1+1 (exactly three pairs)</li>
     * </ul>
     * The same formula serves every subject, with no per-subject special case.
     * A subject gets AT MOST ONE session per day, so a day can never carry more
     * than the 2-period ceiling.
     *
     * <p>The target days are chosen to keep the WHOLE-CLASS week balanced and
     * never plan more periods onto a day than the section can physically hold:
     * each preferred day-size is assigned to the LEAST-LOADED day that still has
     * enough free capacity, and the planned load is tracked across subjects so
     * two subjects never over-plan the same day. A pair-day is additionally
     * de-prioritised on a day that already carries another subject's pair, so at
     * most one normal subject takes a back-to-back block per day. When no day can
     * hold a preferred block, the subject's remaining periods spill onto the days
     * with the MOST remaining capacity (spreading is a soft goal; hard constraints
     * rule).
     *
     * <p>Each {@link DistributionEntry} is ONE session: {@code blockSize == 2}
     * marks a consecutive pair, {@code blockSize == 1} a single period, so the
     * sum of the entries is always the subject's weekly hours. Locked days
     * (partial regeneration) are excluded.
     *
     * @param occupiedDays days already covered by locked entries (partial regeneration)
     * @param dayCapacity  free teaching periods the section can still hold per day
     *                     (per-day teaching-slot count minus the practical and
     *                     locked periods already placed on that day)
     * @param facultyDayCapacity  per subject, the remaining daily teaching periods
     *                     of the subject's assigned faculty per day (its own daily
     *                     cap minus the lab/locked periods already placed on that
     *                     day). A subject is never planned onto a day where its
     *                     faculty is already at the daily limit — otherwise the
     *                     section's least-loaded day could still reject the plan
     *                     at placement time and the theory would spill onto extra
     *                     days (the own-lab faculty-cap trap). Missing keys mean
     *                     no faculty constraint (no assigned faculty).
     */
    public Map<Long, List<DistributionEntry>> buildTheoryDistributionPlan(List<Subject> theorySubjects,
            Map<Long, Integer> weeklyHoursMap, Map<Long, Set<String>> occupiedDays,
            Map<String, Integer> dayCapacity, Map<Long, Map<String, Integer>> facultyDayCapacity) {
        return buildTheoryDistributionPlan(theorySubjects, weeklyHoursMap, occupiedDays, dayCapacity, facultyDayCapacity, null);
    }

    /**
     * Overload accepting a seeded {@link Random} for controlled day shuffling.
     * When non-null, the list of candidate days passed to
     * {@link #leastLoadedFittingDay} and {@link #mostFreeDay} is shuffled
     * before evaluation so different subjects claim different days while still
     * respecting capacity. When null, behaviour is fully deterministic.
     */
    public Map<Long, List<DistributionEntry>> buildTheoryDistributionPlan(List<Subject> theorySubjects,
            Map<Long, Integer> weeklyHoursMap, Map<Long, Set<String>> occupiedDays,
            Map<String, Integer> dayCapacity, Map<Long, Map<String, Integer>> facultyDayCapacity,
            Random rng) {
        Map<Long, List<DistributionEntry>> plan = new HashMap<>();
        Map<String, Integer> plannedLoad = new HashMap<>();
        // Days that already carry a back-to-back pair of a NORMAL subject, so a
        // second subject is not planned onto the same day for its own pair.
        Map<String, Long> pairDayOwner = new HashMap<>();

        for (int subjectIndex = 0; subjectIndex < theorySubjects.size(); subjectIndex++) {
            Subject subject = theorySubjects.get(subjectIndex);
            int weeklyHours = weeklyHoursMap.getOrDefault(subject.getId(), 0);

            if (weeklyHours <= 0) {
                plan.put(subject.getId(), List.of());
                continue;
            }

            int blockSize = Math.min(getSessionBlockSize(subject), Math.max(1, weeklyHours));
            // A normal subject holds at most one session a day, and that session
            // is a single period or one consecutive pair — never 3 or more.
            int perDayCap = Math.min(MAX_PERIODS_PER_SUBJECT_PER_DAY, Math.max(1, weeklyHours));

            List<String> availableDays = new ArrayList<>(WORKING_DAYS.stream()
                .filter(d -> !occupiedDays.getOrDefault(subject.getId(), Set.of()).contains(d))
                .toList());
            if (rng != null) {
                Collections.shuffle(availableDays, rng);
            }
            if (availableDays.isEmpty()) {
                plan.put(subject.getId(), List.of());
                continue;
            }

            // Preferred pattern: each preferred day-size goes to the least-loaded
            // day that still has enough free capacity (whole-class balance) and
            // room for the subject's faculty (own-lab faculty-cap trap).
            Map<String, Integer> facultyCap = facultyDayCapacity.getOrDefault(subject.getId(), Map.of());
            Map<String, Integer> assigned = new LinkedHashMap<>();
            List<Integer> daySizes = idealDaySizes(weeklyHours, blockSize);
            for (int size : daySizes) {
                // One session per day: the subject never takes a second session on
                // a day it already has, which is what caps it at 2 periods a day.
                List<String> mainLoopCandidates = availableDays.stream()
                    .filter(d -> !assigned.containsKey(d))
                    .toList();
                // A pair-day is de-prioritised when another subject already holds a
                // pair that day (at most one back-to-back subject per day).
                if (size >= MAX_PERIODS_PER_SUBJECT_PER_DAY) {
                    mainLoopCandidates = mainLoopCandidates.stream()
                        .sorted(Comparator.comparingInt(d -> pairDayOwner.containsKey(d) ? 1 : 0))
                        .toList();
                }
                String bestDay = leastLoadedFittingDay(mainLoopCandidates, plannedLoad, dayCapacity, facultyCap, size);
                if (bestDay == null) {
                    // The pair/single pattern could not be honoured (day capacity or
                    // faculty cap). Never silently cluster: keep spreading the rest
                    // onto the remaining free days, one period each.
                    break;
                }
                plannedLoad.merge(bestDay, size, Integer::sum);
                assigned.merge(bestDay, size, Integer::sum);
            }

            // Spill any period the preferred pattern could not place onto the days
            // with the most remaining capacity, never exceeding the 2-period
            // per-day ceiling of a normal subject (a day already holding this
            // subject's pair is not eligible for a further single).
            int remaining = weeklyHours
                - assigned.values().stream().mapToInt(Integer::intValue).sum();
            while (remaining > 0) {
                List<String> spillCandidates = availableDays.stream()
                    .filter(d -> assigned.getOrDefault(d, 0) < perDayCap)
                    .toList();
                String bestDay = mostFreeDay(spillCandidates, plannedLoad, dayCapacity, facultyCap);
                if (bestDay == null) {
                    break;
                }
                int free = Math.min(
                    dayCapacity.getOrDefault(bestDay, Integer.MAX_VALUE)
                        - plannedLoad.getOrDefault(bestDay, 0),
                    facultyCap.getOrDefault(bestDay, Integer.MAX_VALUE));
                int chunk = Math.min(remaining, Math.max(0, free));
                chunk = Math.min(chunk, perDayCap - assigned.getOrDefault(bestDay, 0));
                if (chunk <= 0) {
                    break;
                }
                plannedLoad.merge(bestDay, chunk, Integer::sum);
                assigned.merge(bestDay, chunk, Integer::sum);
                remaining -= chunk;
            }

            // Last resort: the section/faculty capacity is already fully planned on
            // every eligible day. Place the remainder one period at a time on the
            // least-loaded day that the subject does not yet occupy — never a
            // second period on a day, so the 2-period ceiling still holds.
            if (remaining > 0) {
                List<String> unassignedDays = availableDays.stream()
                    .filter(d -> !assigned.containsKey(d))
                    .sorted(Comparator.comparingInt(d -> plannedLoad.getOrDefault(d, 0)))
                    .toList();
                for (String day : unassignedDays) {
                    if (remaining <= 0) break;
                    plannedLoad.merge(day, 1, Integer::sum);
                    assigned.merge(day, 1, Integer::sum);
                    remaining--;
                }
            }

            // Decompose each target day's size into sessions: a 2-period day is one
            // consecutive pair, a 1-period day a single. Never emit a second
            // session on the same day — a normal subject holds at most 2 periods a
            // day and, when it holds 2, they are back-to-back. The total always
            // equals the subject's weekly hours.
            List<DistributionEntry> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> dayEntry : assigned.entrySet()) {
                String day = dayEntry.getKey();
                int daySize = dayEntry.getValue();
                int pairs = daySize / MAX_PERIODS_PER_SUBJECT_PER_DAY;
                int singles = daySize % MAX_PERIODS_PER_SUBJECT_PER_DAY;
                for (int k = 0; k < pairs; k++) {
                    entries.add(new DistributionEntry(day, MAX_PERIODS_PER_SUBJECT_PER_DAY));
                    pairDayOwner.putIfAbsent(day, subject.getId());
                }
                for (int k = 0; k < singles; k++) {
                    entries.add(new DistributionEntry(day, 1));
                }
            }

            plan.put(subject.getId(), entries);
        }
        return plan;
    }

    /**
     * The least-loaded day whose planned load plus {@code size} still fits within
     * its section capacity AND whose assigned-faculty daily capacity can hold
     * {@code size}, or {@code null} when no day qualifies. Days tied on load are
     * resolved by the most remaining free capacity (the binding one — section or
     * faculty, whichever is tighter), so a day already holding a practical/locked
     * block (or a faculty already near its daily cap) is skipped in favour of an
     * emptier day — keeping the subject's sessions on its minimum teaching days
     * instead of spilling over a boundary the section or faculty cap creates.
     */
    private String leastLoadedFittingDay(List<String> days, Map<String, Integer> load,
            Map<String, Integer> capacity, Map<String, Integer> facultyCapacity, int size) {
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        int bestFree = -1;
        for (String day : days) {
            int dayLoad = load.getOrDefault(day, 0);
            int dayCapacity = capacity.getOrDefault(day, Integer.MAX_VALUE);
            int facultyFree = facultyCapacity.getOrDefault(day, Integer.MAX_VALUE);
            if (dayLoad + size > dayCapacity || size > facultyFree) {
                continue;
            }
            int free = Math.min(dayCapacity - dayLoad, facultyFree);
            if (dayLoad < bestLoad || (dayLoad == bestLoad && free > bestFree)) {
                bestLoad = dayLoad;
                bestFree = free;
                best = day;
            }
        }
        return best;
    }

    /**
     * The day with the most unused capacity — the binding one (section or
     * faculty) — tie-broken by the working-day order so results stay
     * deterministic, or {@code null} when every day is full.
     */
    private String mostFreeDay(List<String> days, Map<String, Integer> load,
            Map<String, Integer> capacity, Map<String, Integer> facultyCapacity) {
        String best = null;
        int bestFree = 0;
        for (String day : days) {
            int free = Math.min(
                capacity.getOrDefault(day, Integer.MAX_VALUE) - load.getOrDefault(day, 0),
                facultyCapacity.getOrDefault(day, Integer.MAX_VALUE));
            if (free > bestFree) {
                bestFree = free;
                best = day;
            }
        }
        return bestFree > 0 ? best : null;
    }

    /**
     * Ideal periods-per-day pattern for {@code H} weekly periods of a NORMAL
     * subject, derived from the single formula:
     * <pre>
     *   doubleDays = max(0, H - 5)
     *   singleDays = H - (doubleDays * 2)
     * </pre>
     * This yields {@code doubleDays} days carrying a back-to-back pair and
     * {@code singleDays} days carrying one period each, so no day ever exceeds
     * {@link #MAX_PERIODS_PER_SUBJECT_PER_DAY} periods and the total is exactly
     * {@code H}:
     * <ul>
     *   <li>1/wk → 1</li>
     *   <li>3/wk → 1+1+1</li>
     *   <li>5/wk → 1+1+1+1+1</li>
     *   <li>6/wk → 2+1+1+1+1</li>
     *   <li>7/wk → 2+2+1+1+1</li>
     *   <li>8/wk → 2+2+2+1+1</li>
     *   <li>12/wk → 2+2+2+2+2+2</li>
     * </ul>
     * The number of days used is {@code doubleDays + singleDays}; if that exceeds
     * the available working days the caller reports a scheduling conflict.
     *
     * <p>The subject's stored {@code blockSize} no longer changes the pattern —
     * the 2-period daily ceiling decides it — with one exception: a subject whose
     * whole weekly demand is one explicitly requested consecutive block (2
     * hours/week with {@code sessionBlockSize} 2) keeps that block on one day.
     * {@code blockSize >= 3} is no longer settable and is clamped elsewhere, so a
     * legacy value cannot push a normal subject above 2 periods a day.
     */
    private List<Integer> idealDaySizes(int weeklyHours, int blockSize) {
        if (weeklyHours <= 0) return List.of();
        List<Integer> sizes = new ArrayList<>();

        // A subject whose ENTIRE weekly demand is one explicitly requested
        // 2-period consecutive block (2 hours/week with sessionBlockSize 2) keeps
        // that block: one day, two consecutive periods. Without this the formula
        // below yields doubleDays = max(0, 2 - 5) = 0 and singleDays = 2, i.e.
        // 1+1 on two days, which silently discards the explicit 2xCONSECUTIVE
        // request. Only reached when the subject actually stored 2, because
        // getSessionBlockSize reports 1 for the default/unset configuration and
        // clamps anything above 2.
        if (blockSize == MAX_PERIODS_PER_SUBJECT_PER_DAY
                && weeklyHours == MAX_PERIODS_PER_SUBJECT_PER_DAY) {
            return List.of(MAX_PERIODS_PER_SUBJECT_PER_DAY);
        }

        int doubleDays = Math.max(0, weeklyHours - SINGLE_PERIOD_WEEK_LIMIT);
        int singleDays = weeklyHours - (doubleDays * 2);
        if (singleDays < 0) {
            // More than the limit above the first threshold (e.g. H=11, 12):
            // pair as much as possible, keep the odd period as a single.
            doubleDays = weeklyHours / MAX_PERIODS_PER_SUBJECT_PER_DAY;
            singleDays = weeklyHours - (doubleDays * MAX_PERIODS_PER_SUBJECT_PER_DAY);
        }
        for (int i = 0; i < doubleDays; i++) {
            sizes.add(MAX_PERIODS_PER_SUBJECT_PER_DAY);
        }
        for (int i = 0; i < singleDays; i++) {
            sizes.add(1);
        }
        return sizes;
    }
}
