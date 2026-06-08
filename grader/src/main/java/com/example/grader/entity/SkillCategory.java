package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * Category năng lực (mảng kiến thức lớn) trong syllabus — vd DART ("Code Dart"),
 * UI ("Giao diện"), VALIDATION ("Validate"). Đây là cấp ROLL-UP để đánh giá năng lực.
 * `code` là ID ổn định: KHÔNG cho phép đổi (testcase/skill trỏ vào đây).
 */
@Getter
@Setter
@Entity
@Table(name = "skill_category")
public class SkillCategory {

    @Id
    @Column(name = "code", length = 40, nullable = false)
    private String code;                 // 'DART', 'UI', ...

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "competency_label", length = 80)
    private String competencyLabel;      // 'Code Dart', 'Giao diện', 'Validate'

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order")
    @ColumnDefault("0")
    private Integer displayOrder = 0;

    /** Ngưỡng xếp loại: ratio < weak => YẾU; weak..good => TRUNG BÌNH; >= good => TỐT. */
    @Column(name = "weak_threshold")
    @ColumnDefault("0.4")
    private Double weakThreshold = 0.4;

    @Column(name = "good_threshold")
    @ColumnDefault("0.7")
    private Double goodThreshold = 0.7;

    @Column(name = "active")
    @ColumnDefault("true")
    private Boolean active = true;
}
