package com.example.grader.service;

import com.example.grader.entity.BatchStatus;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingBatch;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.repository.GradingBatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExamServiceRenameTest {

    @TempDir
    Path tempDir;

    @Test
    void renamesIdAndDisplayNameAcrossFilesAndHistory() throws Exception {
        Path oldTestcase = tempDir.resolve("exams/OLD_SET/testcase");
        Files.createDirectories(oldTestcase);
        Files.writeString(oldTestcase.resolve("testcase-config.json"),
                "{\"exam_id\":\"OLD_SET\",\"exam_name\":\"Tên cũ\"}");
        Path oldSubmission = tempDir.resolve("submissions/OLD_SET");
        Files.createDirectories(oldSubmission);
        Files.writeString(oldSubmission.resolve("HE123.zip"), "submission");

        Exam exam = new Exam();
        exam.setExamId("OLD_SET");
        exam.setExamName("Tên cũ");
        exam.setTestcasePath(oldTestcase.toString());
        exam.setTestcaseConfigJson("{\"exam_id\":\"OLD_SET\",\"exam_name\":\"Tên cũ\"}");

        ExamResult result = new ExamResult();
        result.setExamId("OLD_SET");
        result.setResultJson("{\"exam\":{\"code\":\"OLD_SET\"}}");
        GradingBatch batch = new GradingBatch();
        batch.setExamId("OLD_SET");
        batch.setStatus(BatchStatus.COMPLETED);

        ExamRepository examRepository = mock(ExamRepository.class);
        ExamResultRepository resultRepository = mock(ExamResultRepository.class);
        GradingBatchRepository batchRepository = mock(GradingBatchRepository.class);
        when(examRepository.findByExamId("OLD_SET")).thenReturn(Optional.of(exam));
        when(examRepository.existsByExamId("NEW_SET")).thenReturn(false);
        when(resultRepository.findByExamId("OLD_SET")).thenReturn(List.of(result));
        when(batchRepository.findByExamIdOrderByCreatedAtDesc("OLD_SET")).thenReturn(List.of(batch));

        Map<String, Object> response = service(examRepository, resultRepository, batchRepository)
                .renameExam("OLD_SET", "NEW_SET", "Tên mới");

        assertEquals("NEW_SET", response.get("exam_id"));
        assertEquals("Tên mới", response.get("exam_name"));
        assertEquals("NEW_SET", exam.getExamId());
        assertEquals("Tên mới", exam.getExamName());
        assertEquals("NEW_SET", result.getExamId());
        assertTrue(result.getResultJson().contains("\"code\":\"NEW_SET\""));
        assertEquals("NEW_SET", batch.getExamId());
        assertFalse(Files.exists(tempDir.resolve("exams/OLD_SET")));
        assertFalse(Files.exists(tempDir.resolve("submissions/OLD_SET")));
        assertTrue(Files.exists(tempDir.resolve("exams/NEW_SET/testcase")));
        assertTrue(Files.exists(tempDir.resolve("submissions/NEW_SET/HE123.zip")));
        String diskConfig = Files.readString(tempDir.resolve("exams/NEW_SET/testcase/testcase-config.json"));
        assertTrue(diskConfig.contains("\"exam_id\" : \"NEW_SET\""));
        assertTrue(diskConfig.contains("\"exam_name\" : \"Tên mới\""));
        assertTrue(exam.getTestcaseConfigJson().contains("\"exam_name\" : \"Tên mới\""));
        verify(examRepository).save(exam);
        verify(resultRepository).save(result);
        verify(batchRepository).save(batch);
    }

    @Test
    void renamesDisplayNameOfUploadedZipWithoutMovingItsFolder() throws Exception {
        Path testcase = tempDir.resolve("exams/ZIP_SET/testcase");
        Files.createDirectories(testcase);

        Exam exam = new Exam();
        exam.setExamId("ZIP_SET");
        exam.setExamName("Tên file cũ");
        exam.setTestcasePath(testcase.toString());
        exam.setTestcaseConfigJson(null);

        ExamRepository examRepository = mock(ExamRepository.class);
        ExamResultRepository resultRepository = mock(ExamResultRepository.class);
        GradingBatchRepository batchRepository = mock(GradingBatchRepository.class);
        when(examRepository.findByExamId("ZIP_SET")).thenReturn(Optional.of(exam));

        Map<String, Object> response = service(examRepository, resultRepository, batchRepository)
                .renameExam("ZIP_SET", "ZIP_SET", "Bộ ZIP đã đổi tên");

        assertEquals("ZIP_SET", response.get("exam_id"));
        assertEquals("Bộ ZIP đã đổi tên", response.get("exam_name"));
        assertEquals("Bộ ZIP đã đổi tên", exam.getExamName());
        assertEquals(testcase.toString(), exam.getTestcasePath());
        assertTrue(Files.isDirectory(testcase));
        verify(examRepository).save(exam);
        verifyNoInteractions(resultRepository, batchRepository);
    }

    private ExamService service(ExamRepository examRepository,
                                ExamResultRepository resultRepository,
                                GradingBatchRepository batchRepository) throws Exception {
        Path graderBase = tempDir.resolve("grader-base");
        Files.createDirectories(graderBase);
        Files.writeString(graderBase.resolve("Dockerfile.base"), "FROM scratch\n");

        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "examRepository", examRepository);
        ReflectionTestUtils.setField(service, "resultRepository", resultRepository);
        ReflectionTestUtils.setField(service, "batchRepository", batchRepository);
        ReflectionTestUtils.setField(service, "templateDir", graderBase.toString());
        ReflectionTestUtils.setField(service, "examsDir", tempDir.resolve("exams").toString());
        ReflectionTestUtils.setField(service, "submissionsDir", tempDir.resolve("submissions").toString());
        return service;
    }
}
