package com.erp.timetable.module.timetable.benchmark;

/**
 * Identifies one input dataset for the Phase 5 A/B benchmark.
 *
 * <p>A dataset is a real exported database dump (see {@code dataset/tt1-dump.sql})
 * plus the identity parameters needed to generate one timetable from it through
 * the production REST service. To benchmark a future dataset: drop its H2 dump
 * under {@code src/test/resources/dataset/} and add one {@code BenchmarkDataset}
 * to the dataset list of the engine benchmark classes. No engine or contract
 * changes are required.
 *
 * @param name              short label used in the benchmark output
 * @param dumpResource      classpath location of the dataset dump
 * @param departmentId      department the timetable is generated for
 * @param sectionId         section the timetable is generated for
 * @param semester          semester of the timetable
 * @param academicSession   academic session sent in the request
 */
public record BenchmarkDataset(
        String name,
        String dumpResource,
        long departmentId,
        long sectionId,
        int semester,
        String academicSession) {

    /** The single production dataset available today (CSD section A, semester 1). */
    public static BenchmarkDataset TT1() {
        return new BenchmarkDataset("TT1", "dataset/tt1-dump.sql", 4L, 25L, 1, "2025-2026 EVEN");
    }
}
