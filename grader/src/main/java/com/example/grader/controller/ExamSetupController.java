package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.repository.ExamRepository;
import com.example.grader.service.ExamService;
import com.example.grader.service.SyllabusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/exam-setup")
@CrossOrigin(origins = "*")
public class ExamSetupController {

    @Autowired
    private ExamService examService;
    @Autowired
    private ExamRepository examRepo;
    @Autowired
    private SyllabusService syllabusService;
    @Autowired
    private com.example.grader.service.TestcaseTemplateService testcaseTemplateService;

    @PostMapping("/upload-testcase")
    public ResponseEntity<?> uploadTestcase(
            @RequestParam("examId")   String examId,
            @RequestParam(value = "examName",    required = false) String examName,
            @RequestParam(value = "teacherNote", required = false) String teacherNote,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.setupExam(examId, examName, teacherNote, zip));

        } catch (IllegalArgumentException e) {
            // Thiếu file bắt buộc, sai format...
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            // docker build thất bại...
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Build image thất bại: " + e.getMessage()));
        }
    }

    /**
     * Nhập bộ testcase viết thủ công. Mã/tên được suy ra từ tên ZIP; ZIP được giải nén rồi bỏ,
     * bộ nhập theo cách này không có testcase_config_json nên không mở lại bằng builder.
     */
    @PostMapping("/import-manual-testcase")
    public ResponseEntity<?> importManualTestcase(
            @RequestParam(value = "teacherNote", required = false) String teacherNote,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.importManualTestcase(
                    zip.getOriginalFilename(), teacherNote, zip.getBytes(), AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không nhập được bộ testcase: " + e.getMessage()));
        }
    }

    /** Build sandbox trực tiếp từ thư mục testcase đã lưu; không tạo hoặc giải nén ZIP trung gian. */
    @PostMapping("/{examId}/sandbox")
    public ResponseEntity<?> buildSandbox(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.buildSandbox(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Không build được sandbox: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{examId}")
    public ResponseEntity<?> getStatus(@PathVariable String examId) {
        return examRepo.findByExamId(examId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Danh sách đề đã cấu hình — cho bộ chọn đánh giá độ phủ theo syllabus. */
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(examService.listExams());
    }

    /**
     * ĐÁNH GIÁ ĐỘ PHỦ của đề theo SYLLABUS hiện tại (resolve trực tiếp → sửa syllabus là
     * phản chiếu ngay). Trả: testcase ↔ kiến thức/độ khó, độ phủ theo category & độ khó,
     * skill chưa phủ (gaps), issues.
     */
    @GetMapping("/coverage/{examId}")
    public ResponseEntity<?> coverage(@PathVariable String examId) {
        try {
            String matrix = examService.readSkillsMatrixJson(examId);
            return ResponseEntity.ok(syllabusService.evaluateCoverage(matrix));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Rubric (danh sách tiêu chí) của đề — cho trang chấm tay. */
    @GetMapping("/criteria/{examId}")
    public ResponseEntity<?> getCriteria(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.getCriteria(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đọc cấu hình template-instance của đề; không làm thay đổi skills_matrix đang publish. */
    @GetMapping("/{examId}/testcases")
    public ResponseEntity<?> getTestcaseConfig(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(testcaseTemplateService.getExamConfig(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không đọc được cấu hình testcase"));
        }
    }

    /** Lưu Draft: chỉ lưu instance/config, không đụng vào bộ testcase đang được chấm. */
    @RequestMapping(path = "/{examId}/testcases/draft", method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<?> saveTestcaseDraft(@PathVariable String examId,
                                                @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(testcaseTemplateService.saveDraft(examId, body, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Publish: sinh skills_matrix.json và ghi snapshot cấu hình versioned cho đề. */
    @PostMapping("/{examId}/testcases/publish")
    public ResponseEntity<?> publishTestcases(@PathVariable String examId,
                                              @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(testcaseTemplateService.publish(examId, body, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đổi mã bộ testcase (kéo theo thư mục, kết quả, phiên chấm). Bộ đang chấm dở bị từ chối. */
    @PostMapping("/{examId}/rename")
    public ResponseEntity<?> renameTestcaseSet(@PathVariable String examId,
                                               @RequestBody Map<String, Object> body) {
        try {
            Object raw = body == null ? null : (body.get("new_exam_id") != null ? body.get("new_exam_id") : body.get("newExamId"));
            return ResponseEntity.ok(examService.renameExam(examId, raw == null ? null : String.valueOf(raw).trim()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Clone toàn bộ cấu hình builder sang mã/tên mới; bộ nhập ZIP bị service từ chối. */
    @PostMapping("/{examId}/clone")
    public ResponseEntity<?> cloneTestcaseSet(@PathVariable String examId,
                                              @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(testcaseTemplateService.cloneExam(examId, body, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Sinh code từ trạng thái form hiện tại để xem trước; không ghi file, không tăng version và không sửa DB. */
    @PostMapping("/{examId}/testcases/preview")
    public ResponseEntity<?> previewTestcases(@PathVariable String examId,
                                              @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(testcaseTemplateService.preview(examId, body, AppActor.DEFAULT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đọc các file testcase của 1 đề (exam_test.dart, skills_matrix.json, grader.dart) — cho trang Kho đề. */
    @GetMapping("/{examId}/testcase")
    public ResponseEntity<?> testcaseFiles(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.readExamTestcaseFiles(examId));
        } catch (IllegalArgumentException e) {     // mã đề không hợp lệ (allowlist)
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải ĐỀ BÀI (de_bai.md) phát cho SV. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/de-bai")
    public ResponseEntity<?> downloadDeBai(@PathVariable String examId) {
        try {
            String md = examService.readDeBai(examId);
            if (md == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có đề bài (de_bai.md)."));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + examId + "_de_bai.md\"")
                    .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                    .body(utf8WithBom(md));   // BOM để Notepad/Word trên Windows nhận đúng UTF-8 (không lỗi font TV)
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải EXAM_TEST: ZIP 3 file testcase (exam_test.dart + grader.dart + skills_matrix.json) — upload lại được. */
    @GetMapping("/{examId}/download/exam-test")
    public ResponseEntity<?> downloadExamTest(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipTestcase(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy testcase của đề " + examId));
            return zipResponse(examId + "_exam_test.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải STARTER: ZIP khung code (lib/…) phát cho SV. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/starter")
    public ResponseEntity<?> downloadStarter(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipStarter(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có khung starter."));
            return zipResponse(examId + "_starter.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Tải LỜI GIẢI MẪU: ZIP lib/ — KHÔNG phát SV, chỉ GV tham khảo. 404 nếu đề chưa lưu kèm. */
    @GetMapping("/{examId}/download/solution")
    public ResponseEntity<?> downloadSolution(@PathVariable String examId) {
        try {
            byte[] zip = examService.zipSolution(examId);
            if (zip == null)
                return ResponseEntity.status(404).body(Map.of("error",
                        "Đề này chưa có lời giải mẫu."));
            return zipResponse(examId + "_solution.zip", zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /**
     * Mã hoá chuỗi thành UTF-8 KÈM BOM (EF BB BF). File .md tiếng Việt thiếu BOM hay bị Notepad/Word/Excel
     * trên Windows đọc theo bảng mã ANSI (CP1258) → lỗi font. BOM giúp các app này tự nhận UTF-8.
     * (KHÔNG thêm BOM cho .dart/.json trong ZIP — một số parser/JSON nghiêm ngặt không chịu BOM.)
     */
    private byte[] utf8WithBom(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && s.charAt(0) == '﻿') return s.getBytes(StandardCharsets.UTF_8);   // đã có BOM
        byte[] text = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + text.length];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(text, 0, out, 3, text.length);
        return out;
    }

    private ResponseEntity<byte[]> zipResponse(String filename, byte[] data) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(data);
    }

    /** Xóa 1 đề không dùng nữa: gỡ ảnh Docker + bản ghi DB (giải phóng dung lượng). */
    @DeleteMapping("/{examId}")
    public ResponseEntity<?> deleteExam(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.deleteExam(examId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
