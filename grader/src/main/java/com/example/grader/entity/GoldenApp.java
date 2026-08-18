package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Một bản build đáp án đã deploy. Recording và oracle luôn trỏ tới đúng hash này để kết quả
 * không âm thầm thay đổi khi giảng viên cập nhật lời giải mẫu.
 */
@Getter
@Setter
@Entity
@Table(name = "golden_apps", indexes = {
        @Index(name = "idx_golden_apps_exam", columnList = "exam_id"),
        @Index(name = "idx_golden_apps_status", columnList = "status")
})
public class GoldenApp {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "exam_id", length = 50)
    private String examId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "version", length = 40, nullable = false)
    private String version;

    /** WEB, ANDROID hoặc LINUX_DESKTOP. */
    @Column(name = "platform", length = 30, nullable = false)
    private String platform;

    /** URL runtime mà recorder mở trong vùng preview. */
    @Column(name = "runtime_url", length = 1000)
    private String runtimeUrl;

    @Column(name = "artifact_path", length = 1000)
    private String artifactPath;

    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private GoldenAppStatus status;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = GoldenAppStatus.REGISTERED;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
