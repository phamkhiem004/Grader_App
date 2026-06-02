package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "exam_results", indexes = {
        @Index(name = "idx_batch",   columnList = "batch_id"),
        @Index(name = "idx_student", columnList = "student_id"),
        @Index(name = "idx_exam",    columnList = "exam_id")
})
public class ExamResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "student_id", nullable = false, length = 20)
    private String studentId;

    @Column(name = "student_name", length = 100)
    private String studentName;

    @Column(name = "exam_id", nullable = false, length = 50)
    private String examId;

    @Column(name = "batch_id", length = 60)
    private String batchId;

    @Column(name = "score")
    private Float score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private GradingStatus status;

    @ColumnDefault("'submit'")
    @Column(name = "mode", length = 10)
    private String mode;

    @Column(name = "details", columnDefinition = "LONGTEXT")
    private String details;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;     // JSON đầy đủ cho AI đọc & nhận xét

    @Column(name = "error_log", columnDefinition = "LONGTEXT")
    private String errorLog;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        submittedAt = Instant.now();
        updatedAt   = Instant.now();
        if (status == null) status = GradingStatus.QUEUED;
        if (mode   == null) mode   = "submit";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

}