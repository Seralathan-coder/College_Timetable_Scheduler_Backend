package com.erp.timetable.module.timetable.entity;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "timetable_entries", uniqueConstraints = {
    @UniqueConstraint(name = "uk_timetable_slot_day", columnNames = {"timetable_id", "day_of_week", "time_slot_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "timetable_id", nullable = false)
    private Timetable timetable;

    @Column(name = "day_of_week", nullable = false, length = 15)
    private String dayOfWeek;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "time_slot_id", nullable = false)
    private TimeSlot timeSlot;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "faculty_id", nullable = false)
    private Faculty faculty;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "section_id", nullable = false)
    private com.erp.timetable.module.department.entity.Section section;

    @Column(name = "is_lab", nullable = false)
    @Builder.Default
    private Boolean isLab = false;

    @Column(name = "is_locked", nullable = false)
    @Builder.Default
    private Boolean isLocked = false;

    public Boolean getIsLab() {
        return isLab;
    }

    public void setIsLab(Boolean isLab) {
        this.isLab = isLab;
    }

    public Boolean isLab() {
        return Boolean.TRUE.equals(this.isLab);
    }

    public void setLab(Boolean isLab) {
        this.isLab = isLab;
    }

    public Boolean getIsLocked() {
        return isLocked;
    }

    public void setIsLocked(Boolean isLocked) {
        this.isLocked = isLocked;
    }

    public Boolean isLocked() {
        return Boolean.TRUE.equals(this.isLocked);
    }

    public void setLocked(Boolean isLocked) {
        this.isLocked = isLocked;
    }
}
