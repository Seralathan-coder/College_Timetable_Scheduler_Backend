package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.timetable.entity.Timetable;

/**
 * Abstraction over a timetable scheduling engine. Implementations are selected
 * via the {@code timetable.scheduler.engine} property (e.g. {@code greedy},
 * {@code timefold}).
 */
public interface ScheduleEngine {

    void generateSchedule(Timetable timetable);

    void generateSchedule(Timetable timetable, boolean regenerateOnlyUnlocked);
}
