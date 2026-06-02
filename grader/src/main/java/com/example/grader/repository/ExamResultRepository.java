package com.example.grader.repository;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.GradingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    // Lấy toàn bộ bài trong 1 batch
    List<ExamResult> findByBatchIdOrderByStudentId(String batchId);

    // Lấy toàn bộ bài của 1 đề thi — dùng cho thống kê
    List<ExamResult> findByExamId(String examId);

    // Lịch sử chấm theo đề (chỉ bài nộp chính thức), mới nhất lên đầu
    List<ExamResult> findByExamIdAndModeOrderByUpdatedAtDesc(String examId, String mode);

    // Tìm kiếm (thanh search header): theo mã SV / tên SV / mã đề
    @Query("SELECT r FROM ExamResult r WHERE (r.mode IS NULL OR r.mode = 'submit') AND (" +
           "LOWER(r.studentId)   LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.studentName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.examId)      LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY r.updatedAt DESC")
    List<ExamResult> searchSubmissions(@Param("q") String q, Pageable pageable);

    // Danh sách examId đã từng được chấm — dùng để lọc dropdown thống kê
    @Query("select distinct r.examId from ExamResult r where r.examId is not null")
    List<String> findDistinctExamIds();

    // Tìm 1 bài cụ thể trong batch
    Optional<ExamResult> findByStudentIdAndBatchId(String studentId, String batchId);

    // Đếm theo trạng thái — dùng cho progress bar
    long countByBatchIdAndStatus(String batchId, GradingStatus status);

    // Job đang chờ/đang chấm — để khôi phục hàng đợi sau restart
    List<ExamResult> findByStatusIn(java.util.Collection<GradingStatus> statuses);

    // Kiểm tra đã nộp chính thức chưa
    boolean existsByStudentIdAndExamIdAndMode(String studentId, String examId, String mode);

    // Tìm bản ghi cũ để ghi đè khi chấm lại (cùng SV + đề + mode)
    Optional<ExamResult> findByStudentIdAndExamIdAndMode(String studentId, String examId, String mode);
}
