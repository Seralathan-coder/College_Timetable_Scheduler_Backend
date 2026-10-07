package com.erp.timetable.module.faculty.repository;

import com.erp.timetable.module.faculty.entity.Faculty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FacultyRepository extends JpaRepository<Faculty, Long> {

    Optional<Faculty> findByEmployeeId(String employeeId);

    Optional<Faculty> findByUserId(Long userId);

    boolean existsByEmployeeId(String employeeId);

    boolean existsByEmail(String email);

    List<Faculty> findByDepartmentId(Long departmentId);

    List<Faculty> findAllByOrderByFirstNameAsc();

    List<Faculty> findByCollege_IdOrderByFirstNameAsc(Long collegeId);

    List<Faculty> findByCollege_IdAndDepartment_IdOrderByFirstNameAsc(Long collegeId, Long departmentId);

    @Query("SELECT f FROM Faculty f WHERE " +
           "(:search IS NULL OR LOWER(f.firstName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.lastName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.employeeId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:deptId IS NULL OR f.department.id = :deptId) AND " +
           "(:status IS NULL OR f.status = :status)")
    Page<Faculty> searchFaculty(String search, Long deptId, String status, Pageable pageable);

    @Query("SELECT f FROM Faculty f WHERE " +
           "((:collegeId IS NULL AND f.college IS NULL) OR f.college.id = :collegeId) AND " +
           "(:search IS NULL OR LOWER(f.firstName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.lastName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.employeeId) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(f.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:deptId IS NULL OR f.department.id = :deptId) AND " +
           "(:status IS NULL OR f.status = :status)")
    Page<Faculty> searchFacultyByCollege(String search, Long deptId, Long collegeId, String status, Pageable pageable);

    long countByCollege_Id(Long collegeId);

    long countByDepartment_CollegeId(Long collegeId);

    long countByDepartment_Id(Long departmentId);
}
