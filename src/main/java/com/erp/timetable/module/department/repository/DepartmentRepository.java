package com.erp.timetable.module.department.repository;

import com.erp.timetable.module.department.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByName(String name);

    @Query("SELECT COUNT(d) > 0 FROM Department d WHERE d.name = :name AND " +
           "((:collegeId IS NULL AND d.college IS NULL) OR d.college.id = :collegeId)")
    boolean existsByNameForCollege(@org.springframework.data.repository.query.Param("name") String name,
                                   @org.springframework.data.repository.query.Param("collegeId") Long collegeId);

    @Query("SELECT COUNT(d) > 0 FROM Department d WHERE d.name = :name AND d.id <> :id AND " +
           "((:collegeId IS NULL AND d.college IS NULL) OR d.college.id = :collegeId)")
    boolean existsByNameAndIdNotForCollege(@org.springframework.data.repository.query.Param("name") String name,
                                           @org.springframework.data.repository.query.Param("id") Long id,
                                           @org.springframework.data.repository.query.Param("collegeId") Long collegeId);

    List<Department> findByIsArchivedFalse();

    @Query("SELECT d FROM Department d WHERE " +
           "(:search IS NULL OR :search = '' OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "(d.hodName IS NOT NULL AND LOWER(d.hodName) LIKE LOWER(CONCAT('%', :search, '%'))) OR " +
           "(d.building IS NOT NULL AND LOWER(d.building) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:isArchived IS NULL OR d.isArchived = :isArchived)")
    Page<Department> searchDepartments(@org.springframework.data.repository.query.Param("search") String search, @org.springframework.data.repository.query.Param("isArchived") Boolean isArchived, Pageable pageable);

    @Query("SELECT d FROM Department d WHERE " +
           "((:collegeId IS NULL AND d.college IS NULL) OR d.college.id = :collegeId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "(d.hodName IS NOT NULL AND LOWER(d.hodName) LIKE LOWER(CONCAT('%', :search, '%'))) OR " +
           "(d.building IS NOT NULL AND LOWER(d.building) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:isArchived IS NULL OR d.isArchived = :isArchived)")
    Page<Department> searchDepartmentsByCollege(@org.springframework.data.repository.query.Param("search") String search,
                                                @org.springframework.data.repository.query.Param("isArchived") Boolean isArchived,
                                                @org.springframework.data.repository.query.Param("collegeId") Long collegeId,
                                                Pageable pageable);

    /**
     * College-scoped department search with an additional exact-department
     * restriction, so an HOD's list resolves to their own department only.
     * A {@code null} {@code deptId} keeps the whole-college behaviour.
     */
    @Query("SELECT d FROM Department d WHERE " +
           "((:collegeId IS NULL AND d.college IS NULL) OR d.college.id = :collegeId) AND " +
           "(:deptId IS NULL OR d.id = :deptId) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(d.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "(d.hodName IS NOT NULL AND LOWER(d.hodName) LIKE LOWER(CONCAT('%', :search, '%'))) OR " +
           "(d.building IS NOT NULL AND LOWER(d.building) LIKE LOWER(CONCAT('%', :search, '%')))) AND " +
           "(:isArchived IS NULL OR d.isArchived = :isArchived)")
    Page<Department> searchDepartmentsByCollegeAndDepartment(
            @org.springframework.data.repository.query.Param("search") String search,
            @org.springframework.data.repository.query.Param("isArchived") Boolean isArchived,
            @org.springframework.data.repository.query.Param("collegeId") Long collegeId,
            @org.springframework.data.repository.query.Param("deptId") Long deptId,
            Pageable pageable);

    List<Department> findByCollege_Id(Long collegeId);
    List<Department> findByCollege_IdAndIsArchivedFalse(Long collegeId);

    long countByCollege_Id(Long collegeId);
}
