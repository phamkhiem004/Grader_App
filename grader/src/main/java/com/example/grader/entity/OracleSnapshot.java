package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Kết quả chuẩn thu từ Golden App với một seed cụ thể. */
@Getter
@Setter
@Entity
@Table(name = "oracle_snapshots", indexes = {
        @Index(name = "idx_oracle_scenario", columnList = "scenario_id"),
        @Index(name = "idx_oracle_status", columnList = "status")
})
public class OracleSnapshot {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "scenario_id", length = 36, nullable = false)
    private String scenarioId;

    @Column(name = "golden_app_id", length = 36, nullable = false)
    private String goldenAppId;

    @Column(name = "golden_sha256", length = 64)
    private String goldenSha256;

    @Column(name = "seed", length = 160, nullable = false)
    private String seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OracleStatus status;

    @Lob
    @Column(name = "input_json", columnDefinition = "LONGTEXT", nullable = false)
    private String inputJson;

    @Lob
    @Column(name = "ui_observation_json", columnDefinition = "LONGTEXT", nullable = false)
    private String uiObservationJson;

    @Lob
    @Column(name = "database_observation_json", columnDefinition = "LONGTEXT", nullable = false)
    private String databaseObservationJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = OracleStatus.PENDING;
        if (inputJson == null) inputJson = "{}";
        if (uiObservationJson == null) uiObservationJson = "{}";
        if (databaseObservationJson == null) databaseObservationJson = "{}";
        createdAt = Instant.now();
    }
}
