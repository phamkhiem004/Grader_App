package com.example.grader.repository;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.GradingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    // Lấy toàn bộ bài trong 1 batch
    List<ExamResult> findByBatchIdOrderByStudentId(String batchId);

    // Tìm 1 bài cụ thể trong batch
    Optional<ExamResult> findByStudentIdAndBatchId(String studentId, String batchId);

    // Đếm theo trạng thái — dùng cho progress bar
    long countByBatchIdAndStatus(String batchId, GradingStatus status);

    // Kiểm tra đã nộp chính thức chưa
    boolean existsByStudentIdAndExamIdAndMode(String studentId, String examId, String mode);
}
