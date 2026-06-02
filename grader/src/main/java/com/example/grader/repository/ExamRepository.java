package com.example.grader.repository;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    Optional<Exam> findByExamId(String examId);

    List<Exam> findByStatus(ExamStatus status);

    boolean existsByExamId(String examId);

    // Thống kê hồ sơ GV: số đề do GV này cấu hình
    long countByCreatedBy(String createdBy);
}
