package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchSubmissionFolderTest {

    private final BatchGradingService service = new BatchGradingService();

    @Test
    void readsStudentCodeFromUsernameSuffixAndKeepsUsername() {
        BatchGradingService.StudentInfo info = service.parseStudentInfo("khiempghe186137");

        assertEquals("HE186137", info.studentId());
        assertEquals("khiempghe186137", info.studentName());
    }

    @Test
    void acceptsUsernameWithoutStudentCodeAsStableIdentifier() {
        BatchGradingService.StudentInfo info = service.parseStudentInfo("khiempg");

        assertEquals("KHIEMPG", info.studentId());
        assertEquals("khiempg", info.studentName());
    }

    /**
     * Tên thư mục KHÔNG còn bị ràng buộc (giáo viên tải từ LMS về là có dấu, có khoảng trắng,
     * có ngoặc) — nhưng mã SV suy ra vẫn phải an toàn vì nó được dùng làm tên thư mục lưu bài.
     */
    @Test
    void acceptsFreeFormFolderNamesAndStillDerivesStudentCode() {
        assertEquals("HE123456", service.parseStudentInfo("Nguyễn Văn A (HE123456)").studentId());
        assertEquals("HE150123", service.parseStudentInfo("PE_ca1 - he150123").studentId());
        assertEquals("Nguyễn Văn A (HE123456)",
                service.parseStudentInfo("Nguyễn Văn A (HE123456)").studentName(),
                "tên gốc phải giữ nguyên để hiện lên bảng điểm");
    }

    @Test
    void folderWithoutStudentCodeBecomesSafeSlug() {
        BatchGradingService.StudentInfo info = service.parseStudentInfo("Trần Thị B - ca 2");

        assertEquals("TRAN_THI_B_CA_2", info.studentId(), "bỏ dấu, thay ký tự lạ bằng _");
        assertEquals("Trần Thị B - ca 2", info.studentName());
    }

    /** Mã SV thành tên thư mục trên đĩa → không được chứa dấu phân cách hay "..". */
    @Test
    void neverProducesPathTraversalId() {
        String id = service.parseStudentInfo("../khiempg").studentId();

        assertEquals("KHIEMPG", id);
        assertFalse(id.contains("/") || id.contains("\\") || id.contains(".."), id);
        assertThrows(IllegalArgumentException.class, () -> service.parseStudentInfo("   "));
    }

    /** student_id là varchar(20): tên dài phải bị cắt, không được để INSERT nổ ở tầng DB. */
    @Test
    void capsStudentIdAtColumnWidth() {
        String id = service.parseStudentInfo("Nguyen Thi Mot Cai Ten That La Dai Khong Tuong").studentId();

        assertEquals(20, id.length(), id);
    }

    @Test
    void acceptsAnyZipFileName() {
        MockMultipartFile zip = new MockMultipartFile(
                "files", "lib_HE186137.zip", "application/zip", new byte[]{1});

        assertDoesNotThrow(() -> service.validateZip(zip, zip.getOriginalFilename()));
    }

    @Test
    void rejectsNonZipFile() {
        MockMultipartFile text = new MockMultipartFile(
                "files", "lib_HE186137.rar", "application/octet-stream", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.validateZip(text, text.getOriginalFilename()));
    }
}
