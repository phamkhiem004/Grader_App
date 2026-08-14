package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void rejectsUnsafeFolderName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.parseStudentInfo("../khiempghe186137"));
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
