package com.example.grader.repository;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    Optional<Exam> findByExamId(String examId);

    List<Exam> findByStatus(ExamStatus status);

    boolean existsByExamId(String examId);

    // Thống kê hồ sơ GV: số đề do GV này cấu hình
    long countByCreatedBy(String createdBy);

    // ── Counter/Flag Pattern: chỉ lấy đề đã có bài chấm xong (cho dropdown Thống kê) ──
    // Đọc O(log N) nhờ index idx_exam_has_results, không cần JOIN/đếm exam_results.
    List<Exam> findByHasResultsTrueOrderByExamNameAsc();

    /** Bật cờ khi bài đầu tiên của đề được chấm xong. WHERE hasResults=false → các lần sau no-op (0 dòng). */
    @Transactional
    @Modifying
    @Query("UPDATE Exam e SET e.hasResults = true WHERE e.examId = :examId AND e.hasResults = false")
    int markHasResults(@Param("examId") String examId);

    /** Backfill 1 lần lúc khởi động: bật cờ cho các đề đã có sẵn bài DONE (dữ liệu cũ trước khi thêm cờ). */
    @Transactional
    @Modifying
    @Query("UPDATE Exam e SET e.hasResults = true WHERE e.hasResults = false AND EXISTS " +
           "(SELECT r.id FROM ExamResult r WHERE r.examId = e.examId " +
           "AND r.status = com.example.grader.entity.GradingStatus.DONE)")
    int backfillHasResults();
}
