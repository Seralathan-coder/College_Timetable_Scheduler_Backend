package com.erp.timetable.module.classroom.repository;

import com.erp.timetable.module.classroom.entity.Classroom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

    List<Classroom> findByStatus(String status);

    Optional<Classroom> findByRoomNumber(String roomNumber);

    List<Classroom> findByDepartment_Id(Long departmentId);

    List<Classroom> findByDepartment_CollegeId(Long collegeId);

    @Query("SELECT c FROM Classroom c WHERE " +
           "(:search IS NULL OR LOWER(c.roomNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(c.roomName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(c.building) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:roomType IS NULL OR c.roomType = :roomType) AND " +
           "(:status IS NULL OR c.status = :status)")
    Page<Classroom> searchClassrooms(String search, String roomType, String status, Pageable pageable);

    @Query("SELECT c FROM Classroom c WHERE " +
           "((:collegeId IS NULL AND c.department.college IS NULL) OR c.department.college.id = :collegeId) AND " +
           "(:deptId IS NULL OR c.department.id = :deptId) AND " +
           "(:search IS NULL OR LOWER(c.roomNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(c.roomName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(c.building) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) AND " +
           "(:roomType IS NULL OR c.roomType = :roomType) AND " +
           "(:status IS NULL OR c.status = :status)")
    Page<Classroom> searchClassroomsByCollege(String search, Long deptId, Long collegeId, String roomType, String status, Pageable pageable);

    long countByDepartment_CollegeId(Long collegeId);

    long countByDepartment_Id(Long departmentId);
}
