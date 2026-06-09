package com.example.grader.controller;

import com.example.grader.service.AuthService;
import com.example.grader.service.ExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Quản lý THƯ VIỆN môi trường chấm (pubspec.base.yaml của ảnh nền grading-base).
 *  - Đọc (GET): mở cho frontend hiển thị.
 *  - Ghi (POST /apply): cần đăng nhập; ghi pubspec + build lại ảnh nền (chạy nền), lỗi thì hoàn tác.
 */
@RestController
@RequestMapping("/api/grading-env")
@CrossOrigin(origins = "*")
public class GradingEnvController {

    @Autowired private ExamService examService;
    @Autowired private AuthService authService;

    /** Danh sách package + trạng thái build hiện tại. */
    @GetMapping("/packages")
    public ResponseEntity<?> packages() {
        return ResponseEntity.ok(Map.of(
                "packages", examService.listManagedPackages(),
                "build", examService.buildStatus()));
    }

    /** Trạng thái build (cho frontend poll khi đang build lại). */
    @GetMapping("/build-status")
    public ResponseEntity<?> buildStatus() {
        return ResponseEntity.ok(examService.buildStatus());
    }

    /** Áp dụng danh sách package SỬA ĐƯỢC rồi build lại ảnh nền. Body: { packages:[{name, version?}] }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestHeader(value = "Authorization", required = false) String authz,
                                   @RequestBody Map<String, Object> body) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            Object pk = body.get("packages");
            List<Map<String, Object>> desired =
                    (pk instanceof List) ? (List<Map<String, Object>>) pk : List.of();
            return ResponseEntity.ok(examService.applyPackages(desired));
        } catch (IllegalStateException e) {              // đang build dở
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // null = OK; khác null = 401
    private ResponseEntity<?> checkAuth(String authz) {
        String token = (authz != null && authz.startsWith("Bearer ")) ? authz.substring(7).trim() : null;
        try { authService.me(token); return null; }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Cần đăng nhập để chỉnh sửa thư viện chấm"));
        }
    }
}
