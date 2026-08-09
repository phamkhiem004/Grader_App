package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Template testcase do giáo viên tạo thêm hoặc sửa đè lên template gốc trong classpath.
 *
 * Thư viện gốc (common-testcase-templates.json) vẫn là nguồn chuẩn; bảng này chỉ chứa
 * phần KHÁC BIỆT nên nâng cấp file gốc không làm mất chỉnh sửa của giáo viên.
 *
 * Quy tắc quản trị: KHÔNG xóa cứng template khỏi thư viện — đề cũ lưu template_id trong
 * testcase_config_json, mất template là không mở lại được đề. "Xóa" = hidden=true (ẩn khỏi
 * Khu vực 2) nhưng vẫn tra cứu được khi lưu lại đề cũ.
 */
@Getter
@Setter
@Entity
@Table(name = "testcase_template")
public class TestcaseTemplate {

    @Id
    @Column(name = "template_id", length = 80, nullable = false)
    private String templateId;

    /** CUSTOM = template mới của giáo viên; OVERRIDE = bản sửa đè lên template gốc. */
    @Column(name = "origin", length = 20, nullable = false)
    private String origin;

    /** Toàn bộ template dạng JSON, cùng shape với common-testcase-templates.json. */
    @Lob
    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    /** Ẩn khỏi thư viện Khu vực 2 nhưng vẫn resolve được cho đề cũ. */
    @ColumnDefault("false")
    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
