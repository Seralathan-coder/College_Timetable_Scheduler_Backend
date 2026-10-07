package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.subject.entity.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubjectDemandService} — verifies the weekly theory
 * distribution plan spreads single-period subjects across distinct days.
 */
class SubjectDemandServiceTest {

    private SubjectDemandService service;

    @BeforeEach
    void setUp() {
        service = new SubjectDemandService();
    }

    private Subject theorySubject(long id, int theoryHours, int blockSize) {
        Subject s = Subject.builder()
            .id(id)
            .subjectCode("SUBJ" + id)
            .subjectName("Subject " + id)
            .subjectType("THEORY")
            .theoryHours(theoryHours)
            .practicalHours(0)
            .build();
        s.setSessionBlockSize(blockSize);
        return s;
    }

    private Map<String, Integer> fullDayCapacity() {
        Map<String, Integer> cap = new HashMap<>();
        for (String day : SubjectDemandService.WORKING_DAYS) {
            cap.put(day, 7);
        }
        return cap;
    }

    private Map<Long, Map<String, Integer>> emptyFacultyDayCapacity(List<Subject> subjects) {
        Map<Long, Map<String, Integer>> map = new HashMap<>();
        for (Subject s : subjects) {
            Map<String, Integer> dayCap = new HashMap<>();
            for (String day : SubjectDemandService.WORKING_DAYS) {
                dayCap.put(day, 5);
            }
            map.put(s.getId(), dayCap);
        }
        return map;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2 hours/week + explicit 2×CONSECUTIVE: ONE 2-period block on ONE day
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void blockSize2_H2_plansASingleConsecutivePairOnOneDay() {
        Subject pt = theorySubject(1, 2, 2);
        List<Subject> subjects = List.of(pt);
        Map<Long, Integer> hours = Map.of(1L, 2);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(1L);
        assertNotNull(entries);
        assertEquals(1, entries.size(),
            "2 hours + 2xCONSECUTIVE is ONE session, not two: " + entries);
        assertEquals(2, entries.get(0).blockSize(),
            "that single session must be a 2-period consecutive block: " + entries);
        assertEquals(2, entries.stream().mapToInt(SubjectDemandService.DistributionEntry::blockSize).sum(),
            "the plan must still account for exactly 2 weekly periods: " + entries);
        assertEquals(1, entries.stream().map(SubjectDemandService.DistributionEntry::day).distinct().count(),
            "both periods must land on the SAME day: " + entries);
    }

    @Test
    void blockSize1_H2_isNOTPromotedToAPair() {
        Subject single = theorySubject(2, 2, 1);
        List<Subject> subjects = List.of(single);
        Map<Long, Integer> hours = Map.of(2L, 2);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(2L);
        assertNotNull(entries);
        assertEquals(2, entries.size(),
            "2 hours + SINGLE must stay two single sessions: " + entries);
        assertTrue(entries.stream().allMatch(e -> e.blockSize() == 1),
            "no entry may become a pair without an explicit 2xCONSECUTIVE request: " + entries);
        assertEquals(2, entries.stream().map(SubjectDemandService.DistributionEntry::day).distinct().count(),
            "the two single periods must sit on 2 DISTINCT days: " + entries);
    }

    @Test
    void blockSize2_H5_keepsTheSinglePeriodPattern() {
        // The explicit block only governs a subject whose WHOLE weekly demand is
        // that one block; 5 hours still spreads as 1+1+1+1+1.
        Subject five = theorySubject(3, 5, 2);
        List<Subject> subjects = List.of(five);
        Map<Long, Integer> hours = Map.of(3L, 5);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(3L);
        assertNotNull(entries);
        assertEquals(5, entries.size(), "5 hours must keep the 1+1+1+1+1 pattern: " + entries);
        assertTrue(entries.stream().allMatch(e -> e.blockSize() == 1), entries.toString());
    }

    @Test
    void blockSize2_H4_keepsTheSinglePeriodPattern() {
        // 4 hours is not "one explicitly requested 2-period block" — the narrow
        // 2-hour rule must not leak into the 4-hour single-period pattern.
        Subject four = theorySubject(4, 4, 2);
        List<Subject> subjects = List.of(four);
        Map<Long, Integer> hours = Map.of(4L, 4);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(4L);
        assertNotNull(entries);
        assertEquals(4, entries.size(), "4 hours must keep the single-period pattern: " + entries);
        assertEquals(4, entries.stream().map(SubjectDemandService.DistributionEntry::day).distinct().count(),
            entries.toString());
    }

    @Test
    void getSessionBlockSize_reportsTheExplicitBlockForAPairSubject() {
        assertEquals(2, service.getSessionBlockSize(theorySubject(9, 2, 2)),
            "a subject stored with 2xCONSECUTIVE must report 2, not the default 1");
    }

    @Test
    void blockSize1_H5_spreadsAcross5DistinctDays() {
        Subject english = theorySubject(1, 5, 1);
        List<Subject> subjects = List.of(english);
        Map<Long, Integer> hours = Map.of(1L, 5);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(1L);
        assertNotNull(entries);
        assertEquals(5, entries.size(), "must produce 5 sessions: " + entries);

        Set<String> days = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : entries) {
            assertEquals(1, e.blockSize(), "each session must be size 1: " + e);
            days.add(e.day());
        }
        assertEquals(5, days.size(),
            "5 periods with blockSize=1 must land on 5 distinct days: " + entries);
    }

    @Test
    void blockSize1_H3_spreadsAcross3DistinctDays() {
        Subject physics = theorySubject(2, 3, 1);
        List<Subject> subjects = List.of(physics);
        Map<Long, Integer> hours = Map.of(2L, 3);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(2L);
        assertNotNull(entries);
        assertEquals(3, entries.size());

        Set<String> days = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : entries) {
            assertEquals(1, e.blockSize());
            days.add(e.day());
        }
        assertEquals(3, days.size(),
            "3 periods with blockSize=1 must land on 3 distinct days: " + entries);
    }

    // ──────────────────────────────────────────────────────────────────────
    // The 2-period daily ceiling: a NORMAL subject never holds more than 2
    // periods a day, and its 6th+ weekly periods form consecutive pairs.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void blockSize1_H6_usesOnePairAndFourSinglesAcross5Days() {
        Subject maths = theorySubject(3, 6, 1);
        List<Subject> subjects = List.of(maths);
        Map<Long, Integer> hours = Map.of(3L, 6);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(3L);
        assertNotNull(entries);
        assertEquals(5, entries.size(), "6 periods must plan as 5 sessions: " + entries);
        assertEquals(6, entries.stream().mapToInt(SubjectDemandService.DistributionEntry::blockSize).sum(),
            "planned periods must equal weekly hours: " + entries);

        Set<String> days = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : entries) {
            assertTrue(e.blockSize() == 1 || e.blockSize() == 2,
                "a normal subject plans only singles and pairs: " + e);
            days.add(e.day());
        }
        assertEquals(5, days.size(), "6 periods must span 5 distinct days: " + entries);
        assertEquals(1, entries.stream().filter(e -> e.blockSize() == 2).count(),
            "6 periods must contain exactly one back-to-back pair: " + entries);
    }

    @Test
    void blockSize1_H7_usesTwoPairsAndThreeSingles() {
        assertPairSinglePattern(7L, 1, 2, 3, 7);
    }

    @Test
    void blockSize1_H8_usesThreePairsAndTwoSingles() {
        assertPairSinglePattern(8L, 1, 3, 2, 8);
    }

    @Test
    void blockSize2_H8_usesThreePairsAndTwoSingles() {
        assertPairSinglePattern(8L, 2, 3, 2, 8);
    }

    @Test
    void weeklyHours_neverPlanMoreThanTwoPeriodsOnADay_orRepeatASession() {
        // H=1..12 with the default blockSize=1 (the database default): the sum is
        // always the weekly hours, every session is a single or a pair, no day ever
        // carries more than one session, and no session exceeds 2 periods.
        for (int h = 1; h <= 12; h++) {
            Subject subject = theorySubject(1L, h, 1);
            List<Subject> subjects = List.of(subject);
            Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
                service.buildTheoryDistributionPlan(
                    subjects, Map.of(1L, h), Map.of(), fullDayCapacity(),
                    emptyFacultyDayCapacity(subjects));

            List<SubjectDemandService.DistributionEntry> entries = plan.get(1L);
            assertNotNull(entries, "H=" + h + " must produce a plan");
            assertEquals(h, entries.stream().mapToInt(SubjectDemandService.DistributionEntry::blockSize).sum(),
                "H=" + h + " must plan exactly " + h + " periods: " + entries);
            assertEquals(entries.size(), entries.stream()
                    .map(SubjectDemandService.DistributionEntry::day).distinct().count(),
                "H=" + h + " must plan at most one session per day: " + entries);
            for (SubjectDemandService.DistributionEntry e : entries) {
                assertTrue(e.blockSize() >= 1 && e.blockSize() <= 2,
                    "H=" + h + " session must be a single or a pair: " + e);
            }
            long pairDays = entries.stream().filter(e -> e.blockSize() == 2).count();
            int expectedPairs = Math.max(0, h - 5);
            if (h - expectedPairs * 2 < 0) {
                // More periods than max(0, H-5) pairs can carry: pair as much as
                // possible and keep the odd period as a single (H=11 -> 5 pairs + 1).
                expectedPairs = h / 2;
            }
            assertEquals(expectedPairs, pairDays,
                "H=" + h + " must contain " + expectedPairs + " consecutive pairs: " + entries);
        }
    }

    @Test
    void severalSubjects_eachGetTheirOwnPairsButShareTheWeek() {
        // Three 8-hour subjects: every subject gets 3 pairs + 2 singles, and no day
        // is planned with two pairs of the SAME subject.
        Subject a = theorySubject(1, 8, 1);
        Subject b = theorySubject(2, 8, 1);
        Subject c = theorySubject(3, 8, 1);
        List<Subject> subjects = List.of(a, b, c);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, Map.of(1L, 8, 2L, 8, 3L, 8), Map.of(), fullDayCapacity(),
                emptyFacultyDayCapacity(subjects));

        for (Subject s : subjects) {
            List<SubjectDemandService.DistributionEntry> entries = plan.get(s.getId());
            assertEquals(5, entries.size(), s.getSubjectCode() + ": 8 periods -> 5 sessions");
            assertEquals(3, entries.stream().filter(e -> e.blockSize() == 2).count(),
                s.getSubjectCode() + ": 8 periods -> 3 pairs");
            assertEquals(5, entries.stream().map(SubjectDemandService.DistributionEntry::day).distinct().count(),
                s.getSubjectCode() + ": one session per day");
        }

        // The section's own capacity is still respected: 3 subjects x 8 periods = 24
        // periods spread over the working days, never over-planning a day.
        Map<String, Integer> load = new HashMap<>();
        for (Subject s : subjects) {
            for (SubjectDemandService.DistributionEntry e : plan.get(s.getId())) {
                load.merge(e.day(), e.blockSize(), Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> day : load.entrySet()) {
            assertTrue(day.getValue() <= 7,
                "day " + day.getKey() + " is over-planned: " + day.getValue());
        }
    }

    private void assertPairSinglePattern(Long subjectId, int blockSize, int expectedPairs,
            int expectedSingles, int hours) {
        Subject subject = theorySubject(subjectId, hours, blockSize);
        List<Subject> subjects = List.of(subject);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, Map.of(subjectId, hours), Map.of(), fullDayCapacity(),
                emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(subjectId);
        assertNotNull(entries);
        assertEquals(hours, entries.stream().mapToInt(SubjectDemandService.DistributionEntry::blockSize).sum(),
            "total periods must equal weekly hours: " + entries);
        assertEquals(expectedPairs, entries.stream().filter(e -> e.blockSize() == 2).count(),
            "expected " + expectedPairs + " consecutive pairs: " + entries);
        assertEquals(expectedSingles, entries.stream().filter(e -> e.blockSize() == 1).count(),
            "expected " + expectedSingles + " single sessions: " + entries);
        assertEquals(expectedPairs + expectedSingles, entries.size(), "one session per planned day: " + entries);
    }

    @Test
    void blockSize1_H4_spreadsAcross4DistinctDays() {
        Subject tamil = theorySubject(4, 4, 1);
        List<Subject> subjects = List.of(tamil);
        Map<Long, Integer> hours = Map.of(4L, 4);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(4L);
        assertNotNull(entries);
        assertEquals(4, entries.size());

        Set<String> days = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : entries) {
            assertEquals(1, e.blockSize());
            days.add(e.day());
        }
        assertEquals(4, days.size(),
            "4 periods with blockSize=1 must land on 4 distinct days: " + entries);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multiple blockSize=1 subjects: each gets distinct days, plans don't
    // pile up on the same days (least-loaded assignment).
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void multipleBlocksize1Subjects_eachSpreadsAcrossDistinctDays() {
        Subject english = theorySubject(1, 5, 1);
        Subject tamil   = theorySubject(2, 5, 1);
        Subject c       = theorySubject(3, 5, 1);
        List<Subject> subjects = List.of(english, tamil, c);
        Map<Long, Integer> hours = Map.of(1L, 5, 2L, 5, 3L, 5);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        for (Subject s : subjects) {
            List<SubjectDemandService.DistributionEntry> entries = plan.get(s.getId());
            assertNotNull(entries);
            assertEquals(5, entries.size(), s.getSubjectCode() + " must have 5 sessions");

            Set<String> days = new HashSet<>();
            for (SubjectDemandService.DistributionEntry e : entries) {
                assertEquals(1, e.blockSize(), s.getSubjectCode() + " each session must be size 1");
                days.add(e.day());
            }
            assertEquals(5, days.size(),
                s.getSubjectCode() + " must spread across 5 distinct days: " + entries);
        }

        // Verify the three subjects don't ALL plan for the same days.
        // Each subject uses least-loaded assignment, so their day sets differ.
        Set<String> days1 = new HashSet<>();
        Set<String> days2 = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : plan.get(1L)) days1.add(e.day());
        for (SubjectDemandService.DistributionEntry e : plan.get(2L)) days2.add(e.day());
        // At least some days should differ between subjects
        Set<String> intersection = new HashSet<>(days1);
        intersection.retainAll(days2);
        assertTrue(intersection.size() < 5,
            "two subjects should not share all 5 planned days");
    }

    // ──────────────────────────────────────────────────────────────────────
    // blockSize=2: H=8 → mix of double and single sessions across 5 days
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void blockSize2_H8_distributesAcross5Days() {
        Subject maths = theorySubject(1, 8, 2);
        List<Subject> subjects = List.of(maths);
        Map<Long, Integer> hours = Map.of(1L, 8);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(1L);
        assertNotNull(entries);

        int totalPeriods = entries.stream().mapToInt(SubjectDemandService.DistributionEntry::blockSize).sum();
        assertEquals(8, totalPeriods, "total periods must equal weekly hours: " + entries);

        Set<String> days = new HashSet<>();
        for (SubjectDemandService.DistributionEntry e : entries) {
            assertTrue(e.blockSize() == 1 || e.blockSize() == 2,
                "blockSize=2 sessions must be size 1 or 2: " + e);
            days.add(e.day());
        }
        assertEquals(5, days.size(),
            "8 periods with blockSize=2 should spread across 5 days: " + entries);

        long doubleSessions = entries.stream().filter(e -> e.blockSize() == 2).count();
        long singleSessions = entries.stream().filter(e -> e.blockSize() == 1).count();
        assertEquals(3, doubleSessions, "must have 3 double-period sessions");
        assertEquals(2, singleSessions, "must have 2 single-period sessions");
    }

    // ──────────────────────────────────────────────────────────────────────
    // getSessionBlockSize returns 1 for blockSize=1 subjects
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void getSessionBlockSize_returns1_forSinglePeriodSubject() {
        Subject subject = theorySubject(1, 5, 1);
        assertEquals(1, service.getSessionBlockSize(subject));
    }

    @Test
    void getSessionBlockSize_returns2_forDoublePeriodSubject() {
        Subject subject = theorySubject(1, 8, 2);
        assertEquals(2, service.getSessionBlockSize(subject));
    }

    @Test
    void getSessionBlockSize_clamps3to2() {
        Subject subject = theorySubject(1, 8, 3);
        assertEquals(2, service.getSessionBlockSize(subject));
    }

    @Test
    void getSessionBlockSize_defaultsTo1_whenNull() {
        Subject subject = theorySubject(1, 5, 1);
        subject.setSessionBlockSize(null);
        assertEquals(1, service.getSessionBlockSize(subject));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Edge cases
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void zeroWeeklyHours_producesEmptyPlan() {
        Subject subject = theorySubject(1, 0, 1);
        List<Subject> subjects = List.of(subject);
        Map<Long, Integer> hours = Map.of(1L, 0);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        assertTrue(plan.get(1L).isEmpty());
    }

    @Test
    void singlePeriod_producesOneSession() {
        Subject subject = theorySubject(1, 1, 1);
        List<Subject> subjects = List.of(subject);
        Map<Long, Integer> hours = Map.of(1L, 1);

        Map<Long, List<SubjectDemandService.DistributionEntry>> plan =
            service.buildTheoryDistributionPlan(
                subjects, hours, Map.of(), fullDayCapacity(), emptyFacultyDayCapacity(subjects));

        List<SubjectDemandService.DistributionEntry> entries = plan.get(1L);
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).blockSize());
    }
}
