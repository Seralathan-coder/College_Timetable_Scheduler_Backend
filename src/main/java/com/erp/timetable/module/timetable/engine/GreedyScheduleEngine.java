package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.timetable.entity.Timetable;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link ScheduleEngine} — the deterministic, rule-based greedy engine.
 *
 * Registered when {@code timetable.scheduler.engine} is {@code greedy} (or when
 * the property is not set at all). A future Timefold implementation will be
 * registered behind the same interface under a different property value.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "timetable.scheduler.engine", havingValue = "greedy", matchIfMissing = true)
public class GreedyScheduleEngine implements ScheduleEngine {

    private final TimetableGeneratorEngine greedyEngine;

    @Override
    public void generateSchedule(Timetable timetable) {
        greedyEngine.generateSchedule(timetable);
    }

    @Override
    public void generateSchedule(Timetable timetable, boolean regenerateOnlyUnlocked) {
        greedyEngine.generateSchedule(timetable, regenerateOnlyUnlocked);
    }
}
