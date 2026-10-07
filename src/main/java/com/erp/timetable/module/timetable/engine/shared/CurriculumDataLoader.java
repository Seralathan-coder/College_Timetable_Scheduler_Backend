package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the curriculum (subjects) for a timetable using section-aware filtering.
 *
 * A section's curriculum is the union of:
 *   1. Subjects linked directly to this section_id (section-specific curriculum)
 *   2. Department-level subjects with no section link (shared curriculum that
 *      applies to every section of the department in that semester)
 *
 * This prevents subjects for Section B appearing in Section A's timetable,
 * while still scheduling department-level subjects entered without a section.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CurriculumDataLoader {

    private final SubjectRepository subjectRepository;

    public List<Subject> loadSubjectsForTimetable(Timetable timetable) {
        Long sectionId  = timetable.getSection() != null  ? timetable.getSection().getId()     : null;
        Long deptId     = timetable.getDepartment() != null ? timetable.getDepartment().getId() : null;
        Integer semester = timetable.getSemester();

        Set<Long> seen = new HashSet<>();
        List<Subject> result = new ArrayList<>();

        // Attempt 1: subjects linked directly to this section_id + semester
        if (sectionId != null) {
            for (Subject s : subjectRepository.findBySectionIdIn(List.of(sectionId))) {
                if (Boolean.TRUE.equals(s.getIsActive())
                        && Objects.equals(s.getSemester(), semester)
                        && seen.add(s.getId())) {
                    result.add(s);
                }
            }
            log.info("  Loaded {} section-linked subjects for section_id={} semester={}",
                result.size(), sectionId, semester);
        }

        // Attempt 2: department-level subjects (no section link) + semester.
        // Only unlinked subjects may be inherited, so a section with no curriculum
        // does NOT pick up another section's subjects (prevents cross-section leak).
        if (deptId != null) {
            int before = result.size();
            for (Subject s : subjectRepository.findByDepartmentId(deptId)) {
                if (s.getSection() == null
                        && Boolean.TRUE.equals(s.getIsActive())
                        && Objects.equals(s.getSemester(), semester)
                        && seen.add(s.getId())) {
                    result.add(s);
                }
            }
            if (result.size() > before) {
                log.info("  Added {} department-level subjects for dept_id={} semester={}",
                    result.size() - before, deptId, semester);
            }
        }

        return result;
    }
}
