package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phiên soạn bằng trợ lý AI được gắn vào CHÍNH bộ testcase, không chỉ nằm ở localStorage.
 *
 * <p>Bộ nào soạn bằng AI thì bấm "Sửa" ở Kho (kể cả trên máy khác, hay sau khi dọn trình duyệt)
 * phải mở lại đúng đề bài · Item Key · khung starter mà AI đã làm, để nhờ AI sửa tiếp ngay.
 */
class ExamServiceAiDraftTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsAiSessionOnTheExamSoEditReopensIt() throws Exception {
        Exam exam = new Exam();
        exam.setExamId("PE_62");

        ExamRepository examRepository = mock(ExamRepository.class);
        when(examRepository.findByExamId("PE_62")).thenReturn(Optional.of(exam));
        ExamService service = service(examRepository);

        assertNull(service.readAiAuthorDraft("PE_62"), "bộ chưa dùng AI thì không có nháp");

        String draft = "{\"updatedAt\":1,\"state\":{\"deBai\":\"Đề PE_62\"}}";
        service.saveAiAuthorDraft("PE_62", draft);
        assertEquals(draft, service.readAiAuthorDraft("PE_62"));

        // "Bắt đầu lại" gửi chuỗi rỗng — phải xoá hẳn chứ không lưu chuỗi rỗng vào cột.
        service.saveAiAuthorDraft("PE_62", "");
        assertNull(exam.getAiAuthorJson());
        assertNull(service.readAiAuthorDraft("PE_62"));
    }

    @Test
    void doesNotRegisterAnExamThatDoesNotExistYet() throws Exception {
        ExamRepository examRepository = mock(ExamRepository.class);
        when(examRepository.findByExamId("CHUA_CO")).thenReturn(Optional.empty());

        // Gõ dở mã bộ ("PE_6") cũng gửi nháp lên; tạo hàng exam ở đây là đẻ ra bộ ma trong Kho.
        service(examRepository).saveAiAuthorDraft("CHUA_CO", "{\"state\":{}}");

        verify(examRepository, never()).save(any());
    }

    @Test
    void adoptsAnExamThatExistsOnDiskOnly() throws Exception {
        Path testcase = tempDir.resolve("exams/TREN_DIA/testcase");
        Files.createDirectories(testcase);
        Files.writeString(testcase.resolve("skills_matrix.json"), "{}");

        ExamRepository examRepository = mock(ExamRepository.class);
        when(examRepository.findByExamId("TREN_DIA")).thenReturn(Optional.empty());
        when(examRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service(examRepository).saveAiAuthorDraft("TREN_DIA", "{\"state\":{}}");

        ArgumentCaptor<Exam> saved = ArgumentCaptor.forClass(Exam.class);
        verify(examRepository, atLeastOnce()).save(saved.capture());
        Exam adopted = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals("TREN_DIA", adopted.getExamId());
        assertEquals("{\"state\":{}}", adopted.getAiAuthorJson());
    }

    private ExamService service(ExamRepository examRepository) throws Exception {
        Path graderBase = tempDir.resolve("grader-base");
        Files.createDirectories(graderBase);
        Files.writeString(graderBase.resolve("Dockerfile.base"), "FROM scratch\n");

        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "examRepository", examRepository);
        ReflectionTestUtils.setField(service, "templateDir", graderBase.toString());
        ReflectionTestUtils.setField(service, "examsDir", tempDir.resolve("exams").toString());
        ReflectionTestUtils.setField(service, "submissionsDir", tempDir.resolve("submissions").toString());
        return service;
    }
}
