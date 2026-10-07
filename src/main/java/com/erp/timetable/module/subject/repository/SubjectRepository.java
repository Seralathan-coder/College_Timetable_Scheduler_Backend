package com.erp.timetable.module.subject.repository;

import com.erp.timetable.module.subject.entity.Subject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    Optional<Subject> findBySubjectCode(String subjectCode);

    boolean existsBySubjectCode(String subjectCode);

    /**
     * Business rule part 1: within a department a subject code must map to exactly
     * ONE academic year. Returns true when the same code already belongs to a
     * DIFFERENT academic year of the same department. Multiple rows in the SAME
     * year (one per section) are intentionally allowed.
     */
    boolean existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdNot(
        Long departmentId, String subjectCode, Long academicYearId);

    /** Update-path variant of part 1 that ignores the record being edited. */
    boolean existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdNotAndIdNot(
        Long departmentId, String subjectCode, Long academicYearId, Long id);

    /**
     * Business rule part 2: the same code may be repeated across DIFFERENT sections
     * of one year, but never twice for the exact same department + year + section.
     */
    boolean existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdAndSection_Id(
        Long departmentId, String subjectCode, Long academicYearId, Long sectionId);

    /** Update-path variant of part 2 that ignores the record being edited. */
    boolean existsByDepartment_IdAndSubjectCodeAndAcademicYear_IdAndSection_IdAndIdNot(
        Long departmentId, String subjectCode, Long academicYearId, Long sectionId, Long id);

    List<Subject> findByDepartmentId(Long departmentId);

    List<Subject> findByAcademicYearIdIn(List<Long> academicYearIds);

    List<Subject> findBySectionIdIn(List<Long> sectionIds);

    List<Subject> findByAssignedFacultyId(Long facultyId);

    @Query("SELECT s FROM Subject s WHERE " +
           "(:search IS NULL OR LOWER(s.subjectCode) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(s.subjectName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:deptId IS NULL OR s.department.id = :deptId) AND " +
           "(:academicYearId IS NULL OR s.academicYear.id = :academicYearId) AND " +
           "(:sectionId IS NULL OR s.section.id = :sectionId) AND " +
           "(:subjectType IS NULL OR s.subjectType = :subjectType)")
    Page<Subject> searchSubjects(String search, Long deptId, Long academicYearId, Long sectionId, String subjectType, Pageable pageable);

    @Query("SELECT s FROM Subject s WHERE " +
           "((:collegeId IS NULL AND s.department.college IS NULL) OR s.department.college.id = :collegeId) AND " +
           "(:search IS NULL OR LOWER(s.subjectCode) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(s.subjectName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:deptId IS NULL OR s.department.id = :deptId) AND " +
           "(:academicYearId IS NULL OR s.academicYear.id = :academicYearId) AND " +
           "(:sectionId IS NULL OR s.section.id = :sectionId) AND " +
           "(:subjectType IS NULL OR s.subjectType = :subjectType)")
    Page<Subject> searchSubjectsByCollege(String search, Long deptId, Long academicYearId, Long sectionId, String subjectType, Long collegeId, Pageable pageable);

    long countByDepartment_CollegeId(Long collegeId);

    long countByDepartment_Id(Long departmentId);
}
