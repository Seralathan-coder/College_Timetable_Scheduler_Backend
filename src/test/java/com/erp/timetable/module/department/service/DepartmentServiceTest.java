package com.erp.timetable.module.department.service;

import com.erp.timetable.common.exception.ResourceNotFoundException;
import com.erp.timetable.common.response.PageResponse;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.dto.DepartmentRequest;
import com.erp.timetable.module.department.dto.DepartmentResponse;
import com.erp.timetable.module.department.dto.YearSectionsRequest;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.config.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceTest {

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private AcademicYearRepository academicYearRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private TimetableRepository timetableRepository;

    @Mock
    private TimetableEntryRepository timetableEntryRepository;

    @Mock
    private ClassroomRepository classroomRepository;

    @Mock
    private FacultyRepository facultyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TenantContext tenantContext;

    @InjectMocks
    private DepartmentService departmentService;

    private Department sampleDepartment;
    private Faculty sampleFaculty1;
    private Faculty sampleFaculty2;
    private Classroom sampleClassroom;
    private Subject sampleSubject;
    private User sampleUser;
    private Timetable sampleTimetable;

    @BeforeEach
    void setUp() {
        sampleDepartment = Department.builder()
            .id(1L)
            .name("Computer Science")
            .hodName("Dr. Alan Turing")
            .building("Block A")
            .isArchived(false)
            .academicYears(new ArrayList<>())
            .build();

        AcademicYear year1 = AcademicYear.builder()
            .id(10L)
            .department(sampleDepartment)
            .yearLabel("1st Year")
            .sections(new ArrayList<>())
            .build();

        year1.getSections().add(Section.builder().id(100L).academicYear(year1).name("A").build());
        year1.getSections().add(Section.builder().id(101L).academicYear(year1).name("B").build());
        sampleDepartment.getAcademicYears().add(year1);

        sampleFaculty1 = Faculty.builder().id(50L).department(sampleDepartment).firstName("Alice").build();
        sampleFaculty2 = Faculty.builder().id(51L).department(sampleDepartment).firstName("Bob").build();
        sampleClassroom = Classroom.builder().id(60L).department(sampleDepartment).roomNumber("R101").build();
        sampleSubject = Subject.builder().id(70L).department(sampleDepartment).subjectCode("CS101").build();
        sampleUser = User.builder().id(90L).department(sampleDepartment).username("hod_cs").build();
        sampleTimetable = Timetable.builder().id(80L).department(sampleDepartment).build();
    }

    @Test
    void testCreateDepartment_Success() {
        DepartmentRequest request = DepartmentRequest.builder()
            .name("Information Technology")
            .hodName("Dr. Grace Hopper")
            .building("Block B")
            .years(List.of(
                YearSectionsRequest.builder().yearLabel("1st Year").sections(List.of("A", "B", "C")).build(),
                YearSectionsRequest.builder().yearLabel("2nd Year").sections(List.of("A", "B", "C")).build(),
                YearSectionsRequest.builder().yearLabel("3rd Year").sections(List.of("A", "B", "C")).build(),
                YearSectionsRequest.builder().yearLabel("4th Year").sections(List.of("A", "B", "C")).build()
            ))
            .build();

        when(departmentRepository.existsByNameForCollege(eq(request.getName()), isNull())).thenReturn(false);
        when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

        DepartmentResponse response = departmentService.createDepartment(request);

        assertNotNull(response);
        assertEquals("Information Technology", response.getName());
        assertEquals(4, response.getAcademicYears().size());
        assertEquals("1st Year", response.getAcademicYears().get(0).getYearLabel());
        assertEquals(3, response.getAcademicYears().get(0).getSections().size());
        verify(departmentRepository, times(1)).save(any(Department.class));
    }

    @Test
    void testGetDepartments_Success() {
        when(departmentRepository.searchDepartments(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(sampleDepartment)));

        PageResponse<DepartmentResponse> result = departmentService.getDepartments(0, 10, null, null, "name,asc");

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals("Computer Science", result.getContent().get(0).getName());
    }

    @Test
    void testUpdateDepartment_SyncsYearsAndSections() {
        DepartmentRequest request = DepartmentRequest.builder()
            .name("Computer Science & Engineering")
            .years(List.of(
                YearSectionsRequest.builder().yearLabel("1st Year").sections(List.of("A")).build(),
                YearSectionsRequest.builder().yearLabel("2nd Year").sections(List.of("A", "B")).build(),
                YearSectionsRequest.builder().yearLabel("3rd Year").sections(List.of("A", "B", "C")).build(),
                YearSectionsRequest.builder().yearLabel("4th Year").sections(List.of("A", "B", "C", "D")).build()
            ))
            .build();

        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sampleDepartment));
        when(departmentRepository.existsByNameAndIdNotForCollege(eq(request.getName()), eq(1L), isNull())).thenReturn(false);
        when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

        DepartmentResponse response = departmentService.updateDepartment(1L, request);

        assertNotNull(response);
        assertEquals("Computer Science & Engineering", response.getName());
        assertEquals(4, response.getAcademicYears().size());
        assertEquals("1st Year", response.getAcademicYears().get(0).getYearLabel());
        assertEquals(1, response.getAcademicYears().get(0).getSections().size());
        assertEquals("A", response.getAcademicYears().get(0).getSections().get(0).getName());
        assertEquals(4, response.getAcademicYears().get(3).getSections().size());
    }

    @Test
    void testDeleteDepartment_CascadesProperly_WithAllDependencies() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sampleDepartment));
        when(timetableRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleTimetable));
        when(subjectRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleSubject));
        when(facultyRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleFaculty1, sampleFaculty2));
        when(classroomRepository.findByDepartment_Id(1L)).thenReturn(List.of(sampleClassroom));
        when(userRepository.findByDepartment_Id(1L)).thenReturn(List.of(sampleUser));

        departmentService.deleteDepartment(1L);

        verify(timetableRepository).deleteAll(List.of(sampleTimetable));
        verify(subjectRepository).deleteAll(List.of(sampleSubject));

        assertNull(sampleFaculty1.getDepartment(), "Faculty1 department should be unlinked");
        assertNull(sampleFaculty2.getDepartment(), "Faculty2 department should be unlinked");
        verify(facultyRepository).saveAll(anyList());

        assertNull(sampleClassroom.getDepartment(), "Classroom department should be unlinked");
        verify(classroomRepository).saveAll(anyList());

        assertNull(sampleUser.getDepartment(), "User department should be unlinked");
        verify(userRepository).saveAll(anyList());

        verify(departmentRepository).delete(sampleDepartment);
    }

    @Test
    void testDeleteDepartment_WhenNotFound_Throws404() {
        when(departmentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> departmentService.deleteDepartment(999L));

        verify(departmentRepository, never()).delete(any());
    }

    @Test
    void testDeleteDepartment_WhenNoDependencies_StillDeletes() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sampleDepartment));
        when(timetableRepository.findByDepartmentId(1L)).thenReturn(List.of());
        when(subjectRepository.findByDepartmentId(1L)).thenReturn(List.of());
        when(facultyRepository.findByDepartmentId(1L)).thenReturn(List.of());
        when(classroomRepository.findByDepartment_Id(1L)).thenReturn(List.of());
        when(userRepository.findByDepartment_Id(1L)).thenReturn(List.of());

        departmentService.deleteDepartment(1L);

        verify(timetableRepository, never()).deleteAll(anyList());
        verify(subjectRepository, never()).deleteAll(anyList());
        verify(facultyRepository, never()).saveAll(anyList());
        verify(classroomRepository, never()).saveAll(anyList());
        verify(userRepository, never()).saveAll(anyList());
        verify(departmentRepository).delete(sampleDepartment);
    }

    @Test
    void testDeleteDepartment_TimetableCascadesBeforeSubjects() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(sampleDepartment));
        when(timetableRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleTimetable));
        when(subjectRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleSubject));
        when(facultyRepository.findByDepartmentId(1L)).thenReturn(List.of());
        when(classroomRepository.findByDepartment_Id(1L)).thenReturn(List.of());
        when(userRepository.findByDepartment_Id(1L)).thenReturn(List.of());

        departmentService.deleteDepartment(1L);

        verify(timetableRepository).deleteAll(List.of(sampleTimetable));
        verify(timetableRepository).flush();
        verify(subjectRepository).deleteAll(List.of(sampleSubject));
        verify(subjectRepository).flush();
    }

    @Test
    void testGetDependencyCounts_ReturnsCorrectCounts() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(timetableRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleTimetable));
        when(subjectRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleSubject, sampleSubject));
        when(facultyRepository.findByDepartmentId(1L)).thenReturn(List.of(sampleFaculty1, sampleFaculty2));
        when(classroomRepository.findByDepartment_Id(1L)).thenReturn(List.of(sampleClassroom));
        when(userRepository.findByDepartment_Id(1L)).thenReturn(List.of(sampleUser));

        Map<String, Integer> counts = departmentService.getDependencyCounts(1L);

        assertEquals(1, counts.get("timetables"));
        assertEquals(2, counts.get("subjects"));
        assertEquals(2, counts.get("faculty"));
        assertEquals(1, counts.get("classrooms"));
        assertEquals(1, counts.get("users"));
    }

    @Test
    void testGetDependencyCounts_WhenNotFound_Throws404() {
        when(departmentRepository.existsById(999L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
            () -> departmentService.getDependencyCounts(999L));
    }
}
