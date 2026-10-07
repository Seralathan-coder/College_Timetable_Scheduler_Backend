package com.erp.timetable.module.department.repository;

import com.erp.timetable.module.department.entity.AcademicYear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AcademicYearRepository extends JpaRepository<AcademicYear, Long> {
    List<AcademicYear> findByDepartmentId(Long departmentId);
    Optional<AcademicYear> findByDepartmentIdAndYearLabel(Long departmentId, String yearLabel);
}
