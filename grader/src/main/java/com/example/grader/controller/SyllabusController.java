package com.example.grader.controller;

import com.example.grader.service.AuthService;
import com.example.grader.service.SyllabusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API SYLLABUS (danh mục năng lực):
 *  - Đọc (GET): mở cho frontend dùng khi ra đề & hiển thị năng lực.
 *  - Ghi (POST/PUT/DELETE): yêu cầu đăng nhập (Authorization: Bearer &lt;token&gt;).
 *  - DELETE = xóa MỀM (category: active=false; skill: deprecated=true).
 */
@RestController
@RequestMapping("/api/syllabus")
@CrossOrigin(origins = "*")
public class SyllabusController {

    @Autowired private SyllabusService syllabus;
    @Autowired private AuthService authService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Đọc ──────────────────────────────────────────────────────
    /** Cây đầy đủ: meta + categories (kèm skills). ?includeInactive=true để xem cả mục ẩn. */
    @GetMapping
    public ResponseEntity<?> getTree(
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(syllabus.tree(includeInactive));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> getCategories() {
        List<Map<String, Object>> out = syllabus.categories().stream()
                .map(syllabus::categoryToMap).toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/skills")
    public ResponseEntity<?> getSkills(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "testable", required = false) String testable) {
        List<Map<String, Object>> out = syllabus.skills().stream()
                .filter(s -> category == null || category.equalsIgnoreCase(s.getCategoryCode()))
                .filter(s -> testable == null || testable.equalsIgnoreCase(s.getTestable()))
                .map(syllabus::skillToMap).toList();
        return ResponseEntity.ok(out);
    }

    // ── Ghi: Category ────────────────────────────────────────────
    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @RequestBody Map<String, Object> body) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            return ResponseEntity.ok(syllabus.categoryToMap(syllabus.saveCategory(body, true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/categories/{code}")
    public ResponseEntity<?> updateCategory(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @PathVariable String code,
                                            @RequestBody Map<String, Object> body) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            body.put("code", code);   // path là nguồn quyết định mã
            return ResponseEntity.ok(syllabus.categoryToMap(syllabus.saveCategory(body, false)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/categories/{code}")
    public ResponseEntity<?> deleteCategory(@RequestHeader(value = "Authorization", required = false) String authz,
                                            @PathVariable String code) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            syllabus.softDeleteCategory(code.toUpperCase());
            return ResponseEntity.ok(Map.of("ok", true, "code", code.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Ghi: Skill ───────────────────────────────────────────────
    @PostMapping("/skills")
    public ResponseEntity<?> createSkill(@RequestHeader(value = "Authorization", required = false) String authz,
                                         @RequestBody Map<String, Object> body) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            return ResponseEntity.ok(syllabus.skillToMap(syllabus.saveSkill(body, true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/skills/{code}")
    public ResponseEntity<?> updateSkill(@RequestHeader(value = "Authorization", required = false) String authz,
                                         @PathVariable String code,
                                         @RequestBody Map<String, Object> body) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            body.put("code", code);
            return ResponseEntity.ok(syllabus.skillToMap(syllabus.saveSkill(body, false)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/skills/{code}")
    public ResponseEntity<?> deleteSkill(@RequestHeader(value = "Authorization", required = false) String authz,
                                         @PathVariable String code) {
        ResponseEntity<?> authErr = checkAuth(authz);
        if (authErr != null) return authErr;
        try {
            syllabus.softDeleteSkill(code.toUpperCase());
            return ResponseEntity.ok(Map.of("ok", true, "code", code.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Validate skills_matrix (trước khi upload đề) ─────────────
    /** Body = nội dung skills_matrix.json (object). Trả {valid, problems:[...]}. */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, Object> matrix) {
        try {
            String json = mapper.writeValueAsString(matrix);
            List<Map<String, Object>> problems = syllabus.validateSkillsMatrix(json);
            return ResponseEntity.ok(Map.of("valid", problems.isEmpty(), "problems", problems));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Auth helper: null = OK; khác null = 401 ─────────────────
    private ResponseEntity<?> checkAuth(String authz) {
        String token = (authz != null && authz.startsWith("Bearer ")) ? authz.substring(7).trim() : null;
        try {
            authService.me(token);    // ném IllegalArgumentException nếu chưa/không hợp lệ
            return null;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Cần đăng nhập để chỉnh sửa syllabus"));
        }
    }
}
