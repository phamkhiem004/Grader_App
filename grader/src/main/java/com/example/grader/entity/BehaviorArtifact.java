package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Metadata file artifact; nội dung lớn được lưu trên đĩa, DB chỉ giữ version và hash. */
@Getter
@Setter
@Entity
@Table(name = "behavior_artifacts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_behavior_artifact_version",
                columnNames = {"suite_id", "artifact_type", "version"})
}, indexes = {
        @Index(name = "idx_behavior_artifact_suite", columnList = "suite_id"),
        @Index(name = "idx_behavior_artifact_active", columnList = "suite_id,artifact_type,active")
})
public class BehaviorArtifact {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "suite_id", length = 36, nullable = false)
    private String suiteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", length = 40, nullable = false)
    private BehaviorArtifactType artifactType;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "original_name", length = 255, nullable = false)
    private String originalName;

    @Column(name = "storage_path", length = 800, nullable = false)
    private String storagePath;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "content_type", length = 160)
    private String contentType;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT", nullable = false)
    private String metadataJson;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (version == null) version = 1;
        if (metadataJson == null) metadataJson = "{}";
        if (active == null) active = true;
        createdAt = Instant.now();
    }
}
