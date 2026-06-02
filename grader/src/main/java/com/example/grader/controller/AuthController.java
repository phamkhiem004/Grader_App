package com.example.grader.controller;

import com.example.grader.dto.LoginRequest;
import com.example.grader.dto.RegisterRequest;
import com.example.grader.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        try {
            return ResponseEntity.ok(authService.register(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ khi đăng ký"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(authService.login(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ khi đăng nhập"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authz,
                                @RequestParam(value = "token", required = false) String tokenParam) {
        try {
            return ResponseEntity.ok(authService.me(extractToken(authz, tokenParam)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authz,
                                    @RequestParam(value = "token", required = false) String tokenParam) {
        authService.logout(extractToken(authz, tokenParam));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Đổi tên hiển thị của GV đang đăng nhập. */
    @PostMapping("/update-profile")
    public ResponseEntity<?> updateProfile(@RequestHeader(value = "Authorization", required = false) String authz,
                                           @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.updateProfile(extractToken(authz, null), body.get("fullName")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đổi mật khẩu của GV đang đăng nhập. */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @RequestBody Map<String, String> body) {
        try {
            authService.changePassword(extractToken(authz, null),
                    body.get("currentPassword"), body.get("newPassword"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Lấy token từ header "Authorization: Bearer xxx" hoặc query param ?token=. */
    private String extractToken(String authz, String tokenParam) {
        if (authz != null && authz.startsWith("Bearer ")) return authz.substring(7).trim();
        return tokenParam;
    }
}
