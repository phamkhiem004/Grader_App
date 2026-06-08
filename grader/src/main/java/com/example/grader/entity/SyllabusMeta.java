package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Lưu version syllabus đã áp dụng vào DB (1 dòng, id=1). Khi version trong
 * resources/syllabus.json đổi → SyllabusService re-seed lại từ file.
 */
@Getter
@Setter
@Entity
@Table(name = "syllabus_meta")
public class SyllabusMeta {

    @Id
    @Column(name = "id")
    private Long id;          // luôn = 1

    @Column(name = "version", length = 20)
    private String version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
