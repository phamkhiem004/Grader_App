package com.example.grader.dto;

import java.time.Instant;

/** Thông tin giáo viên trả về client (KHÔNG kèm mật khẩu). */
public record TeacherProfile(
        Long id,
        String fullName,
        String email,
        String role,
        Instant createdAt,
        long examsCreated,        // số đề đã cấu hình
        long batchesCreated,      // số lần upload chấm hàng loạt
        long submissionsGraded    // tổng số bài đã chấm xong
) {}
