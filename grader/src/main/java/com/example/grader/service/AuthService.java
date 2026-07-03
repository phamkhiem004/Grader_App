package com.example.grader.service;

import com.example.grader.dto.AuthResponse;
import com.example.grader.dto.LoginRequest;
import com.example.grader.dto.RegisterRequest;
import com.example.grader.dto.TeacherProfile;
import com.example.grader.entity.Teacher;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.GradingBatchRepository;
import com.example.grader.repository.TeacherRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Xác thực giáo viên — mức nhẹ: mật khẩu băm BCrypt + token phiên ngẫu nhiên
 * lưu trên bản ghi giáo viên (KHÔNG dùng Spring Security framework).
 */
@Slf4j
@Service
public class AuthService {

    private static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired private TeacherRepository teacherRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private GradingBatchRepository batchRepo;

    // ── Đăng ký ──────────────────────────────────────────────────
    public AuthResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        String password = req.password();
        if (email.isBlank())                     throw new IllegalArgumentException("Vui lòng nhập email");
        if (password == null || password.length() < 6)
            throw new IllegalArgumentException("Mật khẩu phải từ 6 ký tự trở lên");
        if (teacherRepo.existsByEmail(email))    throw new IllegalArgumentException("Email đã được đăng ký");

        Teacher t = new Teacher();
        t.setFullName(req.fullName() != null && !req.fullName().isBlank() ? req.fullName().trim() : email);
        t.setEmail(email);
        t.setPasswordHash(encoder.encode(password));
        t.setRole("TEACHER");
        issueToken(t);
        teacherRepo.save(t);
        log.info("Đăng ký GV mới: {}", email);
        return new AuthResponse(t.getAuthToken(), toProfile(t));
    }

    // ── Đăng nhập ────────────────────────────────────────────────
    public AuthResponse login(LoginRequest req) {
        String email = normalizeEmail(req.email());
        Teacher t = teacherRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Email hoặc mật khẩu không đúng"));
        if (!encoder.matches(req.password() == null ? "" : req.password(), t.getPasswordHash()))
            throw new IllegalArgumentException("Email hoặc mật khẩu không đúng");
        issueToken(t);
        teacherRepo.save(t);
        return new AuthResponse(t.getAuthToken(), toProfile(t));
    }

    // ── Lấy hồ sơ GV đang đăng nhập từ token ─────────────────────
    public TeacherProfile me(String token) {
        return toProfile(authenticate(token));
    }

    /**
     * Xác thực token và trả về entity giáo viên, không tính lại các số liệu hồ sơ.
     * Dùng cho filter/endpoint cần kiểm tra phiên với chi phí thấp.
     */
    public Teacher authenticate(String token) {
        return resolve(token);
    }

    public String emailFromBearer(String authz) {
        return authenticate(extractBearer(authz)).getEmail();
    }

    // ── Đổi tên hiển thị ─────────────────────────────────────────
    public TeacherProfile updateProfile(String token, String fullName) {
        Teacher t = resolve(token);
        if (fullName == null || fullName.isBlank())
            throw new IllegalArgumentException("Tên hiển thị không được để trống");
        t.setFullName(fullName.trim());
        teacherRepo.save(t);
        log.info("GV {} đổi tên hiển thị", t.getEmail());
        return toProfile(t);
    }

    // ── Đổi mật khẩu (yêu cầu mật khẩu hiện tại) ─────────────────
    public void changePassword(String token, String currentPassword, String newPassword) {
        Teacher t = resolve(token);
        if (!encoder.matches(currentPassword == null ? "" : currentPassword, t.getPasswordHash()))
            throw new IllegalArgumentException("Mật khẩu hiện tại không đúng");
        if (newPassword == null || newPassword.length() < 6)
            throw new IllegalArgumentException("Mật khẩu mới phải từ 6 ký tự trở lên");
        t.setPasswordHash(encoder.encode(newPassword));
        teacherRepo.save(t);
        log.info("GV {} đổi mật khẩu", t.getEmail());
    }

    // ── Đăng xuất: xoá token ─────────────────────────────────────
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        teacherRepo.findByAuthToken(token).ifPresent(t -> {
            t.setAuthToken(null);
            t.setTokenExpiresAt(null);
            teacherRepo.save(t);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────
    private Teacher resolve(String token) {
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("Chưa đăng nhập");
        Teacher t = teacherRepo.findByAuthToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Phiên đăng nhập không hợp lệ"));
        if (t.getTokenExpiresAt() == null || t.getTokenExpiresAt().isBefore(Instant.now()))
            throw new IllegalArgumentException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        return t;
    }

    private void issueToken(Teacher t) {
        t.setAuthToken(UUID.randomUUID().toString().replace("-", "")
                + Long.toHexString(System.nanoTime()));
        t.setTokenExpiresAt(Instant.now().plus(TOKEN_TTL));
    }

    private TeacherProfile toProfile(Teacher t) {
        long exams   = examRepo.countByCreatedBy(t.getEmail());
        long batches = batchRepo.countByCreatedBy(t.getEmail());
        long graded  = batchRepo.sumDoneByCreatedBy(t.getEmail());
        return new TeacherProfile(
                t.getId(), t.getFullName(), t.getEmail(), t.getRole(), t.getCreatedAt(),
                exams, batches, graded);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public String extractBearer(String authz) {
        if (authz != null && authz.startsWith("Bearer ")) return authz.substring(7).trim();
        return null;
    }

    // ── Seed tài khoản mẫu khi khởi động (mật khẩu băm tại runtime) ──
    @PostConstruct
    public void seedDefaults() {
        try {
            seed("giaovien@fpt.edu.vn", "Giáo viên FPT", "123456");
            seed("admin@fpt.edu.vn",    "Quản trị viên", "123456");
        } catch (Exception e) {
            log.warn("Seed tài khoản GV lỗi: {}", e.getMessage());
        }
    }

    private void seed(String email, String name, String password) {
        if (teacherRepo.existsByEmail(email)) return;
        Teacher t = new Teacher();
        t.setFullName(name);
        t.setEmail(email);
        t.setPasswordHash(encoder.encode(password));
        t.setRole("TEACHER");
        teacherRepo.save(t);
        log.info("Seed tài khoản GV mẫu: {}", email);   // KHÔNG log mật khẩu
    }
}
