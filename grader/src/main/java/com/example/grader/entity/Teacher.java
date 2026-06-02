package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/** Tài khoản giáo viên dùng để đăng nhập & chấm bài. */
@Getter
@Setter
@Entity
@Table(name = "teachers", indexes = {
        @Index(name = "idx_teacher_token", columnList = "auth_token")
})
public class Teacher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    /** Mật khẩu đã băm BCrypt (KHÔNG bao giờ trả về client). */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @ColumnDefault("'TEACHER'")
    @Column(name = "role", length = 20)
    private String role;

    /** Token phiên đăng nhập (ngẫu nhiên), gửi kèm mỗi request để xác thực. */
    @Column(name = "auth_token", length = 80)
    private String authToken;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (role == null) role = "TEACHER";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
