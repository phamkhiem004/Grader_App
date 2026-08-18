package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Bằng chứng execution plan đã chạy thành công trên đúng phiên bản Golden Solution. */
@Getter
@Setter
@Entity
@Table(name = "golden_validation_runs", indexes = {
        @Index(name = "idx_golden_validation_suite", columnList = "suite_id,created_at")
})
public class GoldenValidationRun {
    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "suite_id", length = 36, nullable = false)
    private String suiteId;

    @Column(name = "golden_sha256", length = 64, nullable = false)
    private String goldenSha256;

    @Column(name = "plan_sha256", length = 64, nullable = false)
    private String planSha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private GoldenValidationStatus status;

    @Column(name = "total_checkpoints", nullable = false)
    private Integer totalCheckpoints;

    @Column(name = "passed_checkpoints", nullable = false)
    private Integer passedCheckpoints;

    @Lob
    @Column(name = "log_text", columnDefinition = "LONGTEXT")
    private String logText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = GoldenValidationStatus.RUNNING;
        if (totalCheckpoints == null) totalCheckpoints = 0;
        if (passedCheckpoints == null) passedCheckpoints = 0;
        createdAt = Instant.now();
    }
}
