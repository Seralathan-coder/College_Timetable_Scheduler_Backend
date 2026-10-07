package com.erp.timetable.module.availability.service;

import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.module.availability.dto.AvailabilityDto;
import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.FacultyAvailabilityRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityService {

    private final FacultyAvailabilityRepository availabilityRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final FacultyRepository facultyRepository;

    @Transactional(readOnly = true)
    public List<TimeSlot> getAllTimeSlots() {
        return timeSlotRepository.findAllByOrderBySlotOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<AvailabilityDto> getFacultyAvailability(Long facultyId) {
        return availabilityRepository.findByFacultyId(facultyId).stream()
            .map(a -> AvailabilityDto.builder()
                .id(a.getId())
                .facultyId(a.getFaculty().getId())
                .dayOfWeek(a.getDayOfWeek())
                .timeSlotId(a.getTimeSlot().getId())
                .slotOrder(a.getTimeSlot().getSlotOrder())
                .status(a.getSlotType())
                .build())
            .toList();
    }

    @Transactional
    public List<AvailabilityDto> saveFacultyAvailability(Long facultyId, List<AvailabilityDto> dtoList) {
        Faculty faculty = facultyRepository.findById(facultyId)
            .orElseThrow(() -> new ResourceNotFoundException("Faculty", "id", facultyId));

        // Bulk delete executes immediately instead of deferring the DELETE to
        // flush time. The derived deleteByFacultyId removes entities one by one
        // (queued), so Hibernate flushes the new INSERTs before the old DELETE
        // statements and the unique key uk_faculty_day_slot (faculty, day, slot)
        // is violated with a 409 on every overwrite.
        List<FacultyAvailability> existing = availabilityRepository.findByFacultyId(facultyId);
        if (!existing.isEmpty()) {
            availabilityRepository.deleteAllInBatch(existing);
        }

        List<FacultyAvailability> entities = dtoList.stream().map(dto -> {
            TimeSlot slot = timeSlotRepository.findById(dto.getTimeSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("TimeSlot", "id", dto.getTimeSlotId()));

            return FacultyAvailability.builder()
                .faculty(faculty)
                .dayOfWeek(dto.getDayOfWeek())
                .timeSlot(slot)
                .slotType(dto.getStatus())
                .build();
        }).toList();

        List<FacultyAvailability> saved = availabilityRepository.saveAll(entities);
        log.info("Saved {} availability slots for faculty: {}", saved.size(), faculty.getFullName());

        return getFacultyAvailability(facultyId);
    }
}
