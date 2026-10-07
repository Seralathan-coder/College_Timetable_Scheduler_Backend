package com.erp.timetable.module.timetable.benchmark;

import com.erp.timetable.module.timetable.api.AbstractTimetableApiE2E;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 — final production A/B validation harness.
 *
 * <p>Drives the real REST scheduling path ({@code POST /timetable/generate} and
 * {@code POST /timetable/{id}/regenerate-unlocked}) exactly as the frontend
 * does, for every Phase 8 scenario:
 * <ul>
 *   <li><b>A</b> — single-section generation (TT1, with a LAB room in the dump).</li>
 *   <li><b>B</b> — multi-section generation (seeded CSE, section A then B).</li>
 *   <li><b>C</b> — locked-entry regeneration on the two-section environment.</li>
 * </ul>
 *
 * <p>Every measured scenario runs one unmeasured warm-up pass followed by
 * {@value #MEASURED_RUNS} measured passes, resetting only the timetable tables
 * between passes so the master data is byte-identical each run (input parity).
 * Each pass emits a {@code P8_RUN} line and the base prints a {@code P8_SUMMARY}
 * with min / max / average / median. Clash counts are re-counted from the
 * returned entries (engine-independent), never taken from the engines' own
 * conflict records. Nothing here modifies production code, engines, or the
 * default engine selection.
 */
public abstract class AbstractPhase8Benchmark extends AbstractTimetableApiE2E {

    protected static final int MEASURED_RUNS = 5;
    protected static final int WARMUP_RUNS = 1;
    protected static final int N_A = -1;

    private static final Pattern SOLVER_SCORE_PATTERN = Pattern.compile(
        "Solver Score\\s*:\\s*(?:(\\d+)hard(?:/([+-]?\\d+)soft)?|([+-]?\\d+)soft|([+-]?\\d+))");

    @PersistenceContext
    protected EntityManager entityManager;

    /** Short label used in the benchmark output, e.g. {@code A}, {@code B}, {@code C}, {@code D}. */
    protected abstract String scenarioName();

    /** Runs one full scenario pass and returns the metrics of its measured step. */
    protected abstract RunResult runScenarioOnce(CapturedOutput output) throws Exception;

    /** Per-scenario correctness invariants asserted across all measured runs. */
    protected void assertScenarioInvariants(List<RunResult> measured) {
    }

    @Test
    void phase8Benchmark(CapturedOutput output) throws Exception {
        resetTimetableTables();
        runScenarioOnce(output);

        List<RunResult> measured = new ArrayList<>();
        for (int r = 0; r < MEASURED_RUNS; r++) {
            resetTimetableTables();
            RunResult result = runScenarioOnce(output);
            measured.add(result);
            printRun(result);
        }
        assertScenarioInvariants(measured);
        printSummary(measured);
    }

    protected void resetTimetableTables() {
        entityManager.clear();
        jdbcTemplate.execute("DELETE FROM TIMETABLE_CONFLICTS");
        jdbcTemplate.execute("DELETE FROM TIMETABLE_ENTRIES");
        jdbcTemplate.execute("DELETE FROM TIMETABLES");
    }

    protected String logsSince(CapturedOutput output, String before) {
        String all = output.getAll();
        return all.length() >= before.length() ? all.substring(before.length()) : "";
    }

    protected long[] parseEngineTimings(String logs) {
        long engineMs = parseMillis(logs, "Engine total");
        long solverMs = parseMillis(logs, "Solved in");
        long mappingMs = parseMillis(logs, "Prepared planning model in");
        long applyMs = parseMillis(logs, "Applied solver result in");
        return new long[]{engineMs, solverMs, mappingMs, applyMs};
    }

    protected int[] parseHardSoft(String logs) {
        Matcher m = SOLVER_SCORE_PATTERN.matcher(logs);
        if (m.find()) {
            if (m.group(1) != null) {
                return new int[]{Integer.parseInt(m.group(1)),
                    m.group(2) != null ? Integer.parseInt(m.group(2)) : 0};
            }
            if (m.group(3) != null) {
                return new int[]{0, Integer.parseInt(m.group(3))};
            }
            return new int[]{Integer.parseInt(m.group(4)), 0};
        }
        return null;
    }

    protected int countSolverInfeasible(JsonNode data) {
        int n = 0;
        for (JsonNode c : data.get("conflicts")) {
            if ("SOLVER_INFEASIBLE".equals(c.get("conflictType").asText())) {
                n++;
            }
        }
        return n;
    }

    protected ClashCounts countClashes(JsonNode entries) {
        Set<String> windows = new HashSet<>();
        Set<String> faculty = new HashSet<>();
        Set<String> rooms = new HashSet<>();
        int facultyClashes = 0;
        int roomClashes = 0;
        int sectionClashes = 0;
        for (JsonNode e : entries) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            if (!windows.add(window)) {
                sectionClashes++;
            }
            if (!faculty.add(e.get("facultyId").asText() + "|" + window)) {
                facultyClashes++;
            }
            if (!rooms.add(e.get("classroomId").asText() + "|" + window)) {
                roomClashes++;
            }
        }
        return new ClashCounts(facultyClashes, roomClashes, sectionClashes);
    }

    /** Counts candidate entries that reuse a (faculty, day, slot) or (room, day, slot) window of the other timetable. */
    protected int countCrossTimetableClashes(JsonNode candidate, JsonNode occupied) {
        Set<String> facultyWindows = new HashSet<>();
        Set<String> roomWindows = new HashSet<>();
        for (JsonNode e : occupied) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            facultyWindows.add(e.get("facultyId").asText() + "|" + window);
            roomWindows.add(e.get("classroomId").asText() + "|" + window);
        }
        int clashes = 0;
        for (JsonNode e : candidate) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            if (!facultyWindows.add(e.get("facultyId").asText() + "|" + window)) {
                clashes++;
            }
            if (!roomWindows.add(e.get("classroomId").asText() + "|" + window)) {
                clashes++;
            }
        }
        return clashes;
    }

    /** Entries of other timetables the engine would load as cross-timetable occupancy input. */
    protected int countOtherTimetableEntries(long timetableId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES WHERE TIMETABLE_ID <> ?", Integer.class, timetableId);
        return n == null ? 0 : n;
    }

    protected JsonNode regenerateUnlocked(long timetableId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/timetable/" + timetableId + "/regenerate-unlocked")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        var root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "regenerate-unlocked must succeed: " + root);
        return root.get("data");
    }

    protected JsonNode lockEntry(long entryId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/timetable/entries/" + entryId + "/lock")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        var root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "lock toggle must succeed: " + root);
        return root.get("data");
    }

    protected JsonNode getTimetable(long timetableId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/timetable/" + timetableId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void printRun(RunResult r) {
        System.out.println("P8_RUN|scenario=" + scenarioName()
            + "|engine=" + engineName()
            + "|requested=" + r.requested()
            + "|assigned=" + r.assigned()
            + "|unassigned=" + r.unassigned()
            + "|hard=" + (r.hard() == N_A ? "-" : r.hard())
            + "|soft=" + (r.soft() == N_A ? "-" : r.soft())
            + "|conflicts=" + r.conflicts()
            + "|solverInfeasible=" + r.solverInfeasible()
            + "|facultyClashes=" + r.facultyClashes()
            + "|roomClashes=" + r.roomClashes()
            + "|sectionClashes=" + r.sectionClashes()
            + "|crossTimetable=" + r.crossTimetableClashes()
            + "|apiMs=" + r.apiMs()
            + "|setupApiMs=" + (r.setupApiMs() > 0 ? r.setupApiMs() : "-")
            + "|solverMs=" + (r.solverMs() > 0 ? r.solverMs() : "-")
            + "|mappingMs=" + (r.mappingMs() > 0 ? r.mappingMs() : "-")
            + "|applyMs=" + (r.applyMs() > 0 ? r.applyMs() : "-")
            + "|persistMs=" + (r.persistMs() >= 0 ? r.persistMs() : "-")
            + "|occupancyFacts=" + (r.occupancyFacts() >= 0 ? r.occupancyFacts() : "-")
            + "|optimization=" + r.optimization());
    }

    private void printSummary(List<RunResult> runs) {
        long[] api = runs.stream().mapToLong(RunResult::apiMs).toArray();
        long[] solver = runs.stream().mapToLong(RunResult::solverMs).filter(v -> v > 0).toArray();
        long[] mapping = runs.stream().mapToLong(RunResult::mappingMs).filter(v -> v > 0).toArray();
        long[] apply = runs.stream().mapToLong(RunResult::applyMs).filter(v -> v > 0).toArray();
        long[] persist = runs.stream().mapToLong(RunResult::persistMs).filter(v -> v >= 0).toArray();
        int[] assigned = runs.stream().mapToInt(RunResult::assigned).toArray();
        int[] conflicts = runs.stream().mapToInt(RunResult::conflicts).toArray();
        int[] cross = runs.stream().mapToInt(RunResult::crossTimetableClashes).toArray();

        System.out.println("P8_SUMMARY|scenario=" + scenarioName()
            + "|engine=" + engineName()
            + "|runs=" + runs.size()
            + "|apiMs(min=" + min(api) + ",max=" + max(api) + ",avg=" + avg(api) + ",median=" + median(api) + ")"
            + "|solverMs(" + (solver.length == 0 ? "-" : "min=" + min(solver) + ",max=" + max(solver)
                + ",avg=" + avg(solver) + ",median=" + median(solver)) + ")"
            + "|mappingMs(" + (mapping.length == 0 ? "-" : "min=" + min(mapping) + ",max=" + max(mapping)
                + ",avg=" + avg(mapping) + ",median=" + median(mapping)) + ")"
            + "|applyMs(" + (apply.length == 0 ? "-" : "min=" + min(apply) + ",max=" + max(apply)
                + ",avg=" + avg(apply) + ",median=" + median(apply)) + ")"
            + "|persistMs(" + (persist.length == 0 ? "-" : "min=" + min(persist) + ",max=" + max(persist)
                + ",avg=" + avg(persist) + ",median=" + median(persist)) + ")"
            + "|assigned(min=" + min(assigned) + ",max=" + max(assigned) + ",avg=" + avg(assigned)
                + ",median=" + median(assigned) + ")"
            + "|conflicts(min=" + min(conflicts) + ",max=" + max(conflicts) + ",avg=" + avg(conflicts)
                + ",median=" + median(conflicts) + ")"
            + "|crossTimetable(min=" + min(cross) + ",max=" + max(cross) + ",avg=" + avg(cross)
                + ",median=" + median(cross) + ")");
    }

    private static long min(long[] a) {
        return Arrays.stream(a).min().orElseThrow();
    }

    private static long max(long[] a) {
        return Arrays.stream(a).max().orElseThrow();
    }

    private static double avg(long[] a) {
        return Arrays.stream(a).average().orElseThrow();
    }

    private static double median(long[] a) {
        long[] sorted = Arrays.stream(a).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
            ? (sorted[mid - 1] + sorted[mid]) / 2.0
            : sorted[mid];
    }

    private static int min(int[] a) {
        return Arrays.stream(a).min().orElseThrow();
    }

    private static int max(int[] a) {
        return Arrays.stream(a).max().orElseThrow();
    }

    private static double avg(int[] a) {
        return Arrays.stream(a).average().orElseThrow();
    }

    private static double median(int[] a) {
        int[] sorted = Arrays.stream(a).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
            ? (sorted[mid - 1] + sorted[mid]) / 2.0
            : sorted[mid];
    }

    protected record RunResult(int requested, int assigned, int unassigned, int hard, int soft,
            int conflicts, int solverInfeasible, int facultyClashes, int roomClashes, int sectionClashes,
            int crossTimetableClashes, long apiMs, long setupApiMs, long solverMs, long mappingMs,
            long applyMs, long persistMs, int occupancyFacts, int optimization) {
    }

    protected record ClashCounts(int faculty, int room, int section) {
    }
}
