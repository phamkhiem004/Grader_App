package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Phiên ghi thao tác trực tiếp trên Golden App, giữ raw trace để audit và abstract lại. */
@Getter
@Setter
@Entity
@Table(name = "golden_recordings", indexes = {
        @Index(name = "idx_golden_recording_suite", columnList = "suite_id"),
        @Index(name = "idx_golden_recording_status", columnList = "status")
})
public class GoldenRecording {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "suite_id", length = 36, nullable = false)
    private String suiteId;

    @Column(name = "golden_app_id", length = 36, nullable = false)
    private String goldenAppId;

    @Column(name = "name", length = 240, nullable = false)
    private String name;

    @Column(name = "seed", length = 160, nullable = false)
    private String seed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RecordingStatus status;

    @Lob
    @Column(name = "viewport_json", columnDefinition = "LONGTEXT", nullable = false)
    private String viewportJson;

    @Lob
    @Column(name = "initial_state_json", columnDefinition = "LONGTEXT", nullable = false)
    private String initialStateJson;

    @Lob
    @Column(name = "raw_trace_json", columnDefinition = "LONGTEXT", nullable = false)
    private String rawTraceJson;

    @Lob
    @Column(name = "final_observation_json", columnDefinition = "LONGTEXT")
    private String finalObservationJson;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (status == null) status = RecordingStatus.ACTIVE;
        if (seed == null || seed.isBlank()) seed = UUID.randomUUID().toString();
        if (viewportJson == null) viewportJson = "{}";
        if (initialStateJson == null) initialStateJson = "{}";
        if (rawTraceJson == null) rawTraceJson = "[]";
        startedAt = Instant.now();
    }
}
