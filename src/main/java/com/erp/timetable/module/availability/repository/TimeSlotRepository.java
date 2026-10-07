package com.erp.timetable.module.availability.repository;

import com.erp.timetable.module.availability.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {
    List<TimeSlot> findAllByOrderBySlotOrderAsc();
}
