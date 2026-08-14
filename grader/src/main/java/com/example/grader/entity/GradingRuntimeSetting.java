package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Cấu hình tài nguyên chấm bài (CPU/RAM mỗi container + số bài chấm song song) do giáo viên
 * chỉnh ngay trong trang "Chấm bài tự động".
 *
 * <p>CHỈ có DUY NHẤT một hàng (id = {@link #SINGLETON_ID}): đây là cấu hình của cả hệ thống chấm,
 * không phải của từng đề — nếu cho nhiều hàng thì không biết hàng nào đang có hiệu lực.
 */
@Getter
@Setter
@Entity
@Table(name = "grading_runtime_settings")
public class GradingRuntimeSetting {

    /** Khoá cố định: mọi thao tác đọc/ghi đều nhắm vào hàng này. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    /** Số CPU mỗi container chấm (tham số `docker run --cpus`). */
    @Column(name = "cpus", nullable = false)
    private Double cpus;

    /** RAM mỗi container chấm, tính bằng MB (tham số `docker run --memory`). */
    @Column(name = "memory_mb", nullable = false)
    private Integer memoryMb;

    /** Số bài chấm SONG SONG (số container chạy cùng lúc = số worker). */
    @Column(name = "max_concurrent", nullable = false)
    private Integer maxConcurrent;

    /** Watchdog mỗi bài: quá số giây này thì container bị giết và bài vào diện chấm tay. */
    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
