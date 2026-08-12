package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Kỹ năng chi tiết trong syllabus (vd DART_LOGIC, UI_BASIC, VAL_INPUT). Mỗi testcase
 * trỏ tới một skill bằng `code`. Skill thuộc về một {@link SkillCategory} qua categoryCode.
 *
 * Quy tắc quản trị: KHÔNG đổi `code`; xóa = đặt deprecated=true (soft-delete) để đề cũ
 * vẫn map được. Xem docs/syllabus-design.md.
 */
@Getter
@Setter
@Entity
@Table(name = "skill", indexes = {
        @Index(name = "idx_skill_category", columnList = "category_code")
})
public class Skill {

    @Id
    @Column(name = "code", length = 60, nullable = false)
    private String code;                 // 'DART_LOGIC', 'UI_BASIC', ...

    @Column(name = "category_code", length = 40, nullable = false)
    private String categoryCode;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** basic | intermediate | advanced — gợi ý độ khó mặc định khi ra đề. */
    @Column(name = "default_difficulty", length = 20)
    @ColumnDefault("'basic'")
    private String defaultDifficulty = "basic";

    /**
     * Cách kiểm được kỹ năng này: `auto` (chấm tự động), `manual_evidence`,
     * `auto_with_isolated_database`, `pipeline_and_manual_evidence`… — từ vựng do `syllabus.json`
     * định nghĩa, KHÔNG phải enum đóng ở đây.
     *
     * <p>Trần 32 chứ không phải 10: syllabus 2026.5 mở rộng từ vựng lên 10 giá trị, dài nhất là
     * `pipeline_and_manual_evidence` (28 ký tự). Để nguyên 10 thì RE-SEED chết ngay dòng đầu với
     * `Data truncation`, bảng `skill` đứng ở bản cũ, và mọi template dùng skill mới không lưu được
     * đề (`ExamService.validateSkillCodes` ném lỗi) — đã xảy ra thật lúc merge syllabus 2026.5.
     */
    @Column(name = "testable", length = 32)
    @ColumnDefault("'auto'")
    private String testable = "auto";

    /** Danh sách học liệu, lưu dạng JSON array (vd ["dart.dev/...", "Slide buổi 3"]). */
    @Column(name = "resources_json", columnDefinition = "TEXT")
    private String resourcesJson;

    @Column(name = "display_order")
    @ColumnDefault("0")
    private Integer displayOrder = 0;

    @Column(name = "deprecated")
    @ColumnDefault("false")
    private Boolean deprecated = false;
}
