package com.example.grader.repository;

import com.example.grader.entity.GradingBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface GradingBatchRepository extends JpaRepository<GradingBatch,Long> {
    Optional<GradingBatch> findByBatchId(String batchId);

    List<GradingBatch> findByExamIdOrderByCreatedAtDesc(String examId);

    // Cập nhật progress sau mỗi bài chấm xong
    @Modifying
    @Transactional
    @Query("""
        UPDATE GradingBatch b SET
            b.doneCount  = b.doneCount  + :done,
            b.errorCount = b.errorCount + :error
        WHERE b.batchId = :batchId
    """)
    void incrementCounts(String batchId, int done, int error);
}
