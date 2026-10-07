package com.erp.timetable.module.availability.repository;

import com.erp.timetable.module.availability.entity.FacultyAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FacultyAvailabilityRepository extends JpaRepository<FacultyAvailability, Long> {
    List<FacultyAvailability> findByFacultyId(Long facultyId);
    void deleteByFacultyId(Long facultyId);
}
