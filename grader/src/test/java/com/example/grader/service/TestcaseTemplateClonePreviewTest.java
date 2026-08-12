package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.TestcaseTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestcaseTemplateClonePreviewTest {

    @TempDir
    Path tempDir;

    @Test
    void clonesBuilderConfigIntoIndependentGeneratedFramework() throws Exception {
        Exam source = sourceExam("SOURCE_01", """
                {"schema_version":1,"items":[],"contract":{"require_keys":true,"keys":[]}}
                """);
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("SOURCE_01")).thenReturn(Optional.of(source));
        when(repository.findByExamId("TARGET_01")).thenReturn(Optional.empty());
        when(repository.existsByExamId("TARGET_01")).thenReturn(false);
        when(repository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExamService examService = mock(ExamService.class);
        Path target = tempDir.resolve("exams/TARGET_01/testcase");
        when(examService.testcaseDirectoryForConfiguration("TARGET_01")).thenReturn(target);

        TestcaseTemplateService service = service(repository, examService);
        Map<String, Object> result = service.cloneExam("SOURCE_01", Map.of(
                "exam_id", "TARGET_01",
                "exam_name", "Bộ bản sao",
                "teacher_note", "Mô tả mới"), "local-user");

        assertEquals(true, result.get("cloned"));
        assertEquals("SOURCE_01", result.get("source_exam_id"));
        assertEquals("TARGET_01", result.get("exam_id"));
        assertTrue(Files.exists(target.resolve("exam_test.dart")));
        assertTrue(Files.exists(target.resolve("grader.dart")));
        assertTrue(Files.exists(target.resolve("skills_matrix.json")));
        assertTrue(Files.exists(target.resolve("testcase-config.json")));

        ArgumentCaptor<Exam> saved = ArgumentCaptor.forClass(Exam.class);
        verify(repository).save(saved.capture());
        assertEquals("TARGET_01", saved.getValue().getExamId());
        assertEquals("Bộ bản sao", saved.getValue().getExamName());
        assertEquals("DRAFT", saved.getValue().getTestcaseStatus());
        assertTrue(saved.getValue().getTestcaseConfigJson().contains("require_keys"));
        verify(examService).cloneHandout("SOURCE_01", "TARGET_01");
    }

    @Test
    void rejectsCloneForManualZipExam() {
        Exam manual = sourceExam("MANUAL_ZIP", null);
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("MANUAL_ZIP")).thenReturn(Optional.of(manual));
        when(repository.existsByExamId("TARGET_02")).thenReturn(false);

        ExamService examService = mock(ExamService.class);
        when(examService.testcaseDirectoryForConfiguration("TARGET_02"))
                .thenReturn(tempDir.resolve("exams/TARGET_02/testcase"));
        TestcaseTemplateService service = service(repository, examService);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.cloneExam("MANUAL_ZIP", Map.of(
                        "exam_id", "TARGET_02", "exam_name", "Không hợp lệ"), "local-user"));
        assertTrue(error.getMessage().contains("ZIP"));
        verify(repository, never()).save(any(Exam.class));
    }

    @Test
    void previewsAllThreeGeneratedFilesWithoutPersisting() {
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("PREVIEW_01")).thenReturn(Optional.empty());
        TestcaseTemplateService service = service(repository, mock(ExamService.class));

        Map<String, Object> result = service.preview("PREVIEW_01", Map.of(
                "items", List.of(),
                "contract", Map.of()), "local-user");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> files = (List<Map<String, String>>) result.get("files");
        assertEquals(List.of("exam_test.dart", "skills_matrix.json", "grader.dart"),
                files.stream().map(file -> file.get("name")).toList());
        assertTrue(files.get(0).get("content").contains("void main()"));
        assertEquals("{}", files.get(1).get("content").replaceAll("\\s", ""));
        assertFalse(files.get(2).get("content").isBlank());
        verify(repository, never()).save(any(Exam.class));
    }

    @Test
    void previewsStoredFilesWhenLegacyTemplateNoLongerExists() {
        String instanceId = "LEGACY_01_item_01";
        Map<String, Object> legacyItem = Map.ofEntries(
                Map.entry("instance_id", instanceId),
                Map.entry("template_id", "REMOVED_LEGACY_TEMPLATE"),
                Map.entry("runner", "TEMPLATE_MODEL_FIELDS"),
                Map.entry("skill_code", "DART_CLASSES_OOP"),
                Map.entry("layer", "LOGIC"),
                Map.entry("testcase_group", "LOGIC"),
                Map.entry("name", "Legacy model fields"),
                Map.entry("description", "Saved snapshot"),
                Map.entry("difficulty", "basic"),
                Map.entry("enabled", true),
                Map.entry("order", 1),
                Map.entry("weight", 2.0),
                Map.entry("parameters", Map.of("modelClass", "LedgerEntry")),
                Map.entry("expected", "Model has required fields"));
        Exam legacy = sourceExam("LEGACY_01", """
                {"schema_version":1,"engine_type":"COMMON_V1","items":[{
                  "instance_id":"LEGACY_01_item_01",
                  "template_id":"REMOVED_LEGACY_TEMPLATE"
                }]}
                """);
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("LEGACY_01")).thenReturn(Optional.of(legacy));
        ExamService examService = mock(ExamService.class);
        List<Map<String, String>> storedFiles = List.of(
                Map.of("name", "exam_test.dart", "content", "void main() {}"),
                Map.of("name", "skills_matrix.json", "content", "{}"),
                Map.of("name", "grader.dart", "content", "void grade() {}"));
        when(examService.readExamTestcaseFiles("LEGACY_01")).thenReturn(storedFiles);
        TestcaseTemplateService service = service(repository, examService);

        Map<String, Object> result = service.preview("LEGACY_01",
                Map.of("items", List.of(legacyItem)), "local-user");

        assertEquals(false, result.get("live_preview"));
        assertEquals(storedFiles, result.get("files"));
        assertTrue(result.get("warning").toString().contains("template cũ"));
        verify(repository, never()).save(any(Exam.class));
    }

    @Test
    void copiesStarterStatementAndSolutionWithTheClone() throws Exception {
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("SOURCE_DOCS")).thenReturn(Optional.empty());
        when(repository.findByExamId("TARGET_DOCS")).thenReturn(Optional.empty());
        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "examRepository", repository);
        Path graderBase = tempDir.resolve("grader-base");
        Files.createDirectories(graderBase);
        Files.writeString(graderBase.resolve("Dockerfile.base"), "FROM scratch");
        ReflectionTestUtils.setField(service, "templateDir", graderBase.toString());
        ReflectionTestUtils.setField(service, "examsDir", tempDir.resolve("exams").toString());

        Path source = tempDir.resolve("exams/SOURCE_DOCS/handout");
        Files.createDirectories(source.resolve("starter/lib"));
        Files.createDirectories(source.resolve("solution/lib"));
        Files.writeString(source.resolve("de_bai.md"), "Nội dung đề");
        Files.writeString(source.resolve("starter/lib/main.dart"), "void main() {}");
        Files.writeString(source.resolve("solution/lib/main.dart"), "void main() => runApp();");

        service.cloneHandout("SOURCE_DOCS", "TARGET_DOCS");

        Path target = tempDir.resolve("exams/TARGET_DOCS/handout");
        assertEquals("Nội dung đề", Files.readString(target.resolve("de_bai.md")));
        assertEquals("void main() {}", Files.readString(target.resolve("starter/lib/main.dart")));
        assertEquals("void main() => runApp();", Files.readString(target.resolve("solution/lib/main.dart")));
    }

    private TestcaseTemplateService service(ExamRepository repository, ExamService examService) {
        TestcaseTemplateService service = new TestcaseTemplateService();
        TestcaseTemplateRepository templates = mock(TestcaseTemplateRepository.class);
        when(templates.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "examRepository", repository);
        ReflectionTestUtils.setField(service, "examService", examService);
        ReflectionTestUtils.setField(service, "syllabusService", mock(SyllabusService.class));
        ReflectionTestUtils.setField(service, "templateRepository", templates);
        return service;
    }

    private Exam sourceExam(String examId, String config) {
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setExamId(examId);
        exam.setExamName(examId);
        exam.setTestcaseConfigJson(config);
        return exam;
    }
}
