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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Xóa bộ testcase phải cuốn theo TOÀN BỘ dấu vết của nó.
 *
 * <p>Lỗi thật đã gặp: chỉ bản ghi `exam` bị xóa, còn exam_result/grading_batch ở lại. Không trang
 * nào quản lý được đống mồ côi đó nữa, nhưng màn hình Chấm tự động vẫn giữ batchId trong
 * localStorage nên cứ F5 là dựng lại nguyên phiên chấm của bộ đã xóa.
 */
class ExamServiceDeleteTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesGradingHistoryAndFilesAlongWithTheExam() throws Exception {
        Path testcase = tempDir.resolve("exams/DELETE_TEST_SET/testcase");
        Files.createDirectories(testcase);
        Files.writeString(testcase.resolve("testcase-config.json"), "{\"exam_id\":\"DELETE_TEST_SET\"}");
        Path submissions = tempDir.resolve("submissions/DELETE_TEST_SET");
        Files.createDirectories(submissions);
        Files.writeString(submissions.resolve("HE123.zip"), "submission");

        Exam exam = new Exam();
        exam.setExamId("DELETE_TEST_SET");
        List<ExamResult> results = List.of(result("HE123"), result("HE456"));
        GradingBatch batch = new GradingBatch();
        batch.setExamId("DELETE_TEST_SET");
        batch.setStatus(BatchStatus.COMPLETED);

        ExamRepository examRepository = mock(ExamRepository.class);
        ExamResultRepository resultRepository = mock(ExamResultRepository.class);
        GradingBatchRepository batchRepository = mock(GradingBatchRepository.class);
        when(examRepository.findByExamId("DELETE_TEST_SET")).thenReturn(Optional.of(exam));
        when(resultRepository.findByExamId("DELETE_TEST_SET")).thenReturn(results);
        when(batchRepository.findByExamIdOrderByCreatedAtDesc("DELETE_TEST_SET")).thenReturn(List.of(batch));

        Map<String, Object> response = service(examRepository, resultRepository, batchRepository)
                .deleteExam("DELETE_TEST_SET");

        assertEquals(2, response.get("resultsRemoved"));
        assertEquals(1, response.get("batchesRemoved"));
        assertEquals(true, response.get("dbRecordRemoved"));
        verify(resultRepository).deleteAll(results);
        verify(batchRepository).deleteAll(List.of(batch));
        verify(examRepository).delete(exam);
        assertFalse(Files.exists(tempDir.resolve("exams/DELETE_TEST_SET")));
        assertFalse(Files.exists(tempDir.resolve("submissions/DELETE_TEST_SET")));
    }

    @Test
    void refusesWhileAGradingSessionIsStillRunning() throws Exception {
        // Xóa giữa chừng thì worker vẫn ghi vào bản ghi vừa bị xóa và đọc zip vừa bị gỡ.
        Path testcase = tempDir.resolve("exams/BUSY_SET/testcase");
        Files.createDirectories(testcase);

        GradingBatch running = new GradingBatch();
        running.setExamId("BUSY_SET");
        running.setStatus(BatchStatus.IN_PROGRESS);

        ExamRepository examRepository = mock(ExamRepository.class);
        ExamResultRepository resultRepository = mock(ExamResultRepository.class);
        GradingBatchRepository batchRepository = mock(GradingBatchRepository.class);
        when(batchRepository.findByExamIdOrderByCreatedAtDesc("BUSY_SET")).thenReturn(List.of(running));

        ExamService service = service(examRepository, resultRepository, batchRepository);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.deleteExam("BUSY_SET"));

        assertTrue(error.getMessage().contains("đang có phiên chấm"), error.getMessage());
        assertTrue(Files.isDirectory(testcase), "chặn rồi thì không được đụng vào file");
        verify(batchRepository, never()).deleteAll(any());
        verifyNoInteractions(examRepository, resultRepository);
    }

    @Test
    void purgeOnlyTouchesExamsThatNoLongerExist() throws Exception {
        // Dữ liệu của các lần xóa đề CŨ (khi deleteExam chưa xóa kèm kết quả) nằm lại trong DB.
        ExamRepository examRepository = mock(ExamRepository.class);
        ExamResultRepository resultRepository = mock(ExamResultRepository.class);
        GradingBatchRepository batchRepository = mock(GradingBatchRepository.class);
        when(resultRepository.findDistinctExamIds()).thenReturn(List.of("ALIVE_SET", "DEAD_SET"));
        when(batchRepository.findDistinctExamIds()).thenReturn(List.of("DEAD_SET", "DEAD_TOO"));
        when(examRepository.existsByExamId("ALIVE_SET")).thenReturn(true);
        when(resultRepository.findByExamId("DEAD_SET")).thenReturn(List.of(result("HE123"), result("HE456")));
        GradingBatch deadBatch = new GradingBatch();
        deadBatch.setExamId("DEAD_TOO");
        deadBatch.setStatus(BatchStatus.COMPLETED);
        when(batchRepository.findByExamIdOrderByCreatedAtDesc("DEAD_TOO")).thenReturn(List.of(deadBatch));

        Map<String, Object> response = service(examRepository, resultRepository, batchRepository)
                .purgeOrphanGradingData();

        assertEquals(List.of("DEAD_SET", "DEAD_TOO"), response.get("examIds"));
        assertEquals(2, response.get("resultsRemoved"));
        assertEquals(1, response.get("batchesRemoved"));
        assertEquals(Map.of(), response.get("failed"));
        verify(resultRepository, never()).findByExamId("ALIVE_SET");   // bộ còn sống: không đụng tới
        verify(examRepository, never()).findByExamId("ALIVE_SET");
    }

    private static ExamResult result(String studentId) {
        ExamResult r = new ExamResult();
        r.setExamId("DELETE_TEST_SET");
        r.setStudentId(studentId);
        return r;
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
        ReflectionTestUtils.setField(service, "imagePrefix", "grader-unit-test");
        return service;
    }
}
