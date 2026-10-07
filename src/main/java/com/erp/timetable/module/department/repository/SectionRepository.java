package com.erp.timetable.module.department.repository;

import com.erp.timetable.module.department.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SectionRepository extends JpaRepository<Section, Long> {
    List<Section> findByAcademicYearId(Long academicYearId);
}
