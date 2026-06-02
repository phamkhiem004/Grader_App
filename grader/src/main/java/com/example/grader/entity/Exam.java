package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "exams", indexes = {
        @Index(name = "idx_exam_has_results", columnList = "has_results")
})
public class Exam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "exam_id", nullable = false, length = 50)
    private String examId;

    @Column(name = "exam_name", length = 200)
    private String examName;

    @Lob
    @Column(name = "teacher_note")
    private String teacherNote;     // ghi chú/đề bài để AI hiểu ngữ cảnh khi nhận xét

    @Column(name = "image_name", length = 100)
    private String imageName;

    @Column(name = "testcase_path", length = 500)
    private String testcasePath;   // đường dẫn testcase trên host để mount lúc chấm

    /**
     * Cờ "đã có bài chấm xong" (Counter/Flag Pattern). Bật true khi bài đầu tiên
     * của đề được chấm DONE → trang Thống kê chỉ cần SELECT … WHERE hasResults = true
     * (đọc O(log N) nhờ index) thay vì quét toàn bảng exam_results mỗi lần load.
     */
    @ColumnDefault("false")
    @Column(name = "has_results", nullable = false)
    private boolean hasResults;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ExamStatus status;

    @Lob
    @Column(name = "allowed_packages")
    private String allowedPackages;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at")
    private Instant createdAt;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = ExamStatus.BUILDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

}