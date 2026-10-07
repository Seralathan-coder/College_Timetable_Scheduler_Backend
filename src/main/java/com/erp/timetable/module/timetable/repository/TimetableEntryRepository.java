package com.erp.timetable.module.timetable.repository;

import com.erp.timetable.module.timetable.entity.TimetableEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableEntryRepository extends JpaRepository<TimetableEntry, Long> {

    List<TimetableEntry> findByTimetableId(Long timetableId);

    List<TimetableEntry> findByFacultyId(Long facultyId);

    List<TimetableEntry> findByFacultyIdIn(List<Long> facultyIds);

    List<TimetableEntry> findBySubjectIdIn(List<Long> subjectIds);

    @Modifying
    @Query("DELETE FROM TimetableEntry e WHERE e.subject.id = :subjectId")
    void deleteBySubjectId(Long subjectId);

    /**
     * Deletes every timetable entry that references any of the given subjects.
     * Used before deleting/unlinking a subject so the NOT NULL subject_id FK
     * cannot block the removal (setting it to null is not allowed).
     */
    @Modifying
    @Query("DELETE FROM TimetableEntry e WHERE e.subject.id IN :subjectIds")
    void deleteBySubjectIdIn(@org.springframework.data.repository.query.Param("subjectIds") List<Long> subjectIds);

    /**
     * Deletes every timetable entry that references the given faculty.
     * Needed before deleting a faculty profile, because timetable_entries.faculty_id
     * is NOT NULL and otherwise the delete fails with a foreign-key violation.
     */
    @Modifying
    @Query("DELETE FROM TimetableEntry e WHERE e.faculty.id = :facultyId")
    void deleteByFacultyId(Long facultyId);

    /**
     * Deletes every timetable entry that references the given classroom.
     * Needed before deleting a classroom, because timetable_entries.classroom_id
     * is NOT NULL and otherwise the delete fails with a foreign-key violation.
     */
    @Modifying
    @Query("DELETE FROM TimetableEntry e WHERE e.classroom.id = :classroomId")
    void deleteByClassroomId(Long classroomId);

    /**
     * Bulk-deletes every unlocked entry of a timetable. Used before partial
     * regeneration so new placements never collide with still-flushed rows
     * under the (timetable_id, day_of_week, time_slot_id) unique constraint.
     */
    @Modifying
    @Query("DELETE FROM TimetableEntry e WHERE e.timetable.id = :timetableId AND e.isLocked = false")
    int deleteUnlockedByTimetableId(@org.springframework.data.repository.query.Param("timetableId") Long timetableId);

    @Query("SELECT e FROM TimetableEntry e WHERE e.faculty.id = :facultyId AND e.dayOfWeek = :dayOfWeek AND e.timeSlot.id = :slotId")
    List<TimetableEntry> findFacultyClashes(Long facultyId, String dayOfWeek, Long slotId);

    @Query("SELECT e FROM TimetableEntry e WHERE e.classroom.id = :classroomId AND e.dayOfWeek = :dayOfWeek AND e.timeSlot.id = :slotId")
    List<TimetableEntry> findRoomClashes(Long classroomId, String dayOfWeek, Long slotId);
}
