package com.erp.timetable.module.timetable.repository;

import com.erp.timetable.module.timetable.entity.TimetableConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableConflictRepository extends JpaRepository<TimetableConflict, Long> {
    List<TimetableConflict> findByTimetableId(Long timetableId);
    void deleteByTimetableId(Long timetableId);
}
