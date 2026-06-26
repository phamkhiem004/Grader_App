package com.example.grader.service.ai;

import com.example.grader.service.ExamService;
import com.example.grader.service.SyllabusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BỘ SINH ĐỀ BẰNG AI — vòng lặp "generate → compile-check → feed lỗi → sửa":
 *   1) Gọi LLM (qua {@link LlmService}) với hợp đồng JSON → nhận exam_test + skills_matrix + lời giải mẫu.
 *   2) Chèn grader.dart CHUẨN (resource ai/grader.dart) — không để LLM sinh.
 *   3) Chạy thử trong ảnh nền ({@link TestcaseCompiler}): biên dịch + chạy test với lời giải mẫu.
 *   4) PASS hết → xong; còn lỗi → đẩy lỗi ngược cho LLM sửa, lặp tối đa N lần.
 *
 * Chạy NỀN theo job (giống cơ chế build môi trường) để wizard poll xem tiến trình từng vòng.
 * KHUNG: chỉ cần cắm API key (application.properties) là hoạt động — không key thì job báo NO_API_KEY.
 */
@Slf4j
@Service
public class AiExamGenService {

    @Value("${grader.ai.max-fix-attempts:4}")
    private int maxAttempts;

    /** Sinh theo LÔ ở pha A: mỗi vòng thêm tối đa batchSize testcase (tránh giới hạn token). */
    @Value("${grader.ai.batch-size:20}")
    private int batchSize;

    /** Cỡ lô NỀN: nhỏ để hội tụ (PASS hết) nhanh; phần còn lại bù bằng các lô delta. */
    @Value("${grader.ai.base-size:12}")
    private int baseSize;

    /** Trần số vòng pha A (gồm cả vòng sửa lẫn vòng thêm lô) để không chạy vô hạn. */
    @Value("${grader.ai.max-batch-rounds:12}")
    private int maxBatchRounds;

    /** SÀN số testcase — luôn cố đạt ≥ mức này (kể cả khi người dùng nhập ít hơn). */
    @Value("${grader.ai.min-testcases:40}")
    private int minTestcases;

    /** Mục tiêu khi người dùng chọn "Tự động" (AI tạo nhiều nhất có thể). */
    @Value("${grader.ai.auto-target:60}")
    private int autoTarget;

    @Autowired private LlmService llm;
    @Autowired private PromptBuilder prompts;
    @Autowired private ArtifactParser parser;
    @Autowired private TestcaseCompiler compiler;
    @Autowired private TestPruner pruner;
    @Autowired private ExamService examService;
    @Autowired private SyllabusService syllabusService;   // tự sửa skill_code AI bịa trước khi lưu

    private final Map<String, GenJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile String canonicalGrader;   // bản chuẩn grader.dart (nạp 1 lần)

    // ── API cho controller ───────────────────────────────────────
    public Map<String, Object> status() { return llm.status(); }

    /** Tạo job sinh đề, chạy nền, trả jobId để frontend poll. */
    public String startGenerate(GenRequest req) {
        String id = "AIGEN_" + System.currentTimeMillis() + "_" + Long.toHexString(seq.incrementAndGet());
        GenJob job = new GenJob(id, llm.status());
        jobs.put(id, job);
        evictOld();
        Thread t = new Thread(() -> runLoop(job, req), "ai-gen-" + id);
        job.worker = t;
        t.start();
        return id;
    }

    public Map<String, Object> jobSnapshot(String jobId) {
        GenJob job = jobs.get(jobId);
        return job == null ? null : job.snapshot();
    }

    /** Yêu cầu DỪNG 1 job (huỷ hợp tác): vòng lặp sẽ thoát ở checkpoint gần nhất. */
    public boolean cancel(String jobId) {
        GenJob job = jobs.get(jobId);
        if (job == null) return false;
        job.cancel();
        return true;
    }

    /**
     * Đóng gói sản phẩm của 1 job thành ZIP để tải:
     *  - "testcase": exam_test.dart + grader.dart + skills_matrix.json (ở gốc) — để upload thành đề.
     *  - "handout" : de_bai.md + lib/* (khung code) — bộ PHÁT cho sinh viên.
     *  - "all"     : testcase/ + de_bai.md + starter/lib/ + solution/lib/ — lưu trữ đầy đủ.
     * Trả null nếu job chưa có artifacts hoặc kind không hợp lệ.
     */
    public byte[] buildZip(String jobId, String kind) {
        GenJob job = jobs.get(jobId);
        if (job == null) return null;
        GenArtifacts a = job.getArtifacts();
        if (a == null) return null;

        Map<String, String> files = new LinkedHashMap<>();
        switch (kind == null ? "testcase" : kind.toLowerCase()) {
            case "testcase" -> files.putAll(a.testcaseFiles());
            case "handout" -> {
                if (a.deBai() != null && !a.deBai().isBlank()) files.put("de_bai.md", a.deBai());
                if (a.starter() != null) files.putAll(a.starter());           // lib/...
            }
            case "all" -> {
                a.testcaseFiles().forEach((n, c) -> files.put("testcase/" + n, c));
                if (a.deBai() != null && !a.deBai().isBlank()) files.put("de_bai.md", a.deBai());
                if (a.starter() != null)  a.starter().forEach((n, c) -> files.put("starter/" + n, c));
                if (a.solution() != null) a.solution().forEach((n, c) -> files.put("solution/" + n, c));
            }
            default -> { return null; }
        }
        if (files.isEmpty()) return null;
        try { return zip(files); }
        catch (Exception e) { log.warn("buildZip lỗi: {}", e.getMessage()); return null; }
    }

    /**
     * Lưu artifacts đã duyệt thành ĐỀ thi:
     *  - 3 file testcase (exam_test + grader + skills_matrix) → pipeline upload-testcase (zip + validate + mount khi chấm);
     *  - đề bài (de_bai.md) + khung starter → lưu RIÊNG vào handout/ (KHÔNG mount lúc chấm) để tải về sau ở Kho đề.
     */
    public Map<String, Object> saveAsExam(String examId, String examName, String note,
                                          String examTest, String graderDart, String skillsMatrix,
                                          String deBai, List<Map<String, String>> starter,
                                          List<Map<String, String>> solution) throws Exception {
        ExamService.safeId(examId, "đề");
        // BẢO HIỂM: nếu AI lỡ "bịa" skill_code không có trong syllabus → tự thay bằng code hợp lệ,
        // để bước lưu KHÔNG bao giờ fail vì skill_code (skill_code chỉ gom năng lực, không ảnh hưởng chấm).
        skillsMatrix = syllabusService.sanitizeSkillsMatrix(skillsMatrix);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("exam_test.dart", examTest);
        files.put("grader.dart", (graderDart == null || graderDart.isBlank()) ? canonicalGrader() : graderDart);
        files.put("skills_matrix.json", skillsMatrix);
        byte[] zip = zip(files);
        var resp = examService.setupExamFromZipBytes(examId, examName, note, zip);
        // testcase đã lưu OK → lưu kèm bộ phát SV (đề bài + starter) + lời giải mẫu để Kho đề tải về.
        examService.saveHandout(examId, deBai, starter, solution);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examId", resp.getExamId());
        out.put("status", resp.getStatus());
        out.put("imageName", resp.getImageName());
        return out;
    }

    // ── Vòng lặp 2 pha: A=testcase (compile-fix) · B=đề bài + khung code ──
    private void runLoop(GenJob job, GenRequest req) {
        try {
            if (!llm.isReady()) {
                job.terminate("NO_API_KEY",
                        "Chưa cắm API key (hoặc tính năng đang tắt). Điền grader.ai." +
                        llm.status().get("provider") + ".api-key vào grader/secret.properties rồi thử lại.");
                return;
            }

            // Mở 1 phiên compile (container ấm) dùng chung cả pha A & B → nhanh hơn nhiều
            // (không tạo lại container; Flutter giữ cache biên dịch giữa các lần).
            try (TestcaseCompiler.CompileSession session = compiler.openSession()) {

            // ══ PHA A — TESTCASE + LỜI GIẢI MẪU ══
            // Lô NỀN: sinh ĐẦY ĐỦ + compile-fix tới khi PASS. Các lô sau: chỉ trả PHẦN MỚI (delta), backend
            // GHÉP vào bản tốt rồi biên dịch — lô nào hỏng thì BỎ (giữ bản tốt) → nhanh, không tụt lùi, không cụt token.
            int target = targetFor(req);
            int firstN = Math.min(Math.max(1, baseSize), target);   // lô nền NHỎ → hội tụ nhanh
            List<LlmMessage> convo = new ArrayList<>();
            convo.add(LlmMessage.system(prompts.systemTestcase()));
            convo.add(LlmMessage.user(prompts.userTestcase(req, firstN, target)));
            job.convoA = convo;   // giữ lại để có thể CHỈNH SỬA sau khi xong
            job.req = req;

            GenArtifacts art = null, lastGood = null;   // lastGood = bộ hợp lệ (đã biên dịch + PASS) gần nhất
            boolean solutionOk = false;
            int totalRounds = 0;
            int baseAttempts = Math.max(2, Math.min(maxAttempts, 3));   // ≤3 lượt cho lô nền (cap thời gian)

            // ── Lô NỀN (sinh nhỏ + compile-fix; lượt cuối "tỉa" test khó để có bộ XANH 100%) ──
            for (int round = 1; round <= baseAttempts && totalRounds < maxBatchRounds; round++) {
                totalRounds++;
                if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                job.setMessage("Pha A — sinh testcase nền (lần " + round + "/" + baseAttempts + ", mục tiêu ~" + target + ")...");
                String resp;
                try {
                    resp = llm.chat(convo);
                } catch (Exception e) {
                    if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                    job.addAttempt(attempt(round, "generate", false, "Gọi AI lỗi: " + e.getMessage()));
                    job.terminate("ERROR", "Gọi AI thất bại: " + e.getMessage());
                    return;
                }
                convo.add(LlmMessage.assistant(resp));

                GenArtifacts parsed;
                try {
                    parsed = parser.parse(resp).withGrader(canonicalGrader());
                } catch (ArtifactParser.ParseException pe) {
                    job.addAttempt(attempt(round, "parse", false, pe.getMessage()));
                    convo.add(LlmMessage.user("Phản hồi chưa đúng định dạng: " + pe.getMessage()
                            + " — trả lại ĐÚNG 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution)."));
                    continue;
                }
                art = parsed;
                job.setArtifacts(art);   // cập nhật dần để wizard xem được ngay

                int count = parser.countTestcases(art.skillsMatrix());
                job.setMessage("Pha A — biên dịch & chạy thử " + count + " testcase (lần " + round + ")...");
                CompileResult cr = session.checkSolution(art);
                job.addAttempt(compileAttempt(round, "compile", cr, cr.ok()));
                if (cr.ok()) { lastGood = art; solutionOk = true; break; }

                // TỐI ƯU: biên dịch OK nhưng vài test FAIL → BỎ các test fail (giữ phần PASS) → có nền XANH ngay.
                GenArtifacts pruned = tryPrune(session, cr, art, 1);
                if (pruned != null) {
                    art = pruned; job.setArtifacts(art); lastGood = art; solutionOk = true;
                    job.addAttempt(attempt(round, "prune", true, "Đã bỏ " + cr.failedTests().size()
                            + " test fail, giữ " + parser.countTestcases(art.skillsMatrix()) + " test xanh."));
                    break;
                }
                // Hỏng → sửa. Từ lượt áp chót: chuyển sang "TỈA" (xóa test khó) để chắc chắn có bộ xanh.
                convo.add(LlmMessage.user(round >= baseAttempts - 1
                        ? prompts.fixSolutionFinal(cr, round)
                        : prompts.fixSolution(cr, round)));
            }

            if (art == null) {
                job.terminate("FAILED", "Không tạo được testcase hợp lệ (AI không trả JSON đúng định dạng).");
                return;
            }

            // ── Lô THÊM (delta, ghép tăng dần) — chỉ khi đã có bản nền tốt ──
            if (lastGood != null) {
                int noProgress = 0;
                while (parser.countTestcases(lastGood.skillsMatrix()) < target && totalRounds < maxBatchRounds) {
                    if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                    totalRounds++;
                    int count = parser.countTestcases(lastGood.skillsMatrix());
                    ArtifactParser.MatrixInfo info = parser.matrixInfo(lastGood.skillsMatrix());
                    int addN = Math.min(batchSize, target - count);
                    job.setMessage("Pha A — thêm " + addN + " testcase (đang có " + count + ", mục tiêu " + target + ")...");

                    int mark = convo.size();   // mốc để GỠ cả lượt này (kể cả lượt sửa) khỏi hội thoại nếu lô hỏng
                    convo.add(LlmMessage.user(prompts.addBatchDelta(count, info.ids(), info.skills(), addN, target)));
                    boolean accepted = false;
                    for (int attempt = 1; attempt <= 2; attempt++) {   // 1 lần thử + 1 lần sửa rồi mới bỏ
                        if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                        String resp;
                        try {
                            resp = llm.chat(convo);
                        } catch (Exception e) {
                            if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                            break;   // gọi AI lỗi → bỏ lượt
                        }
                        convo.add(LlmMessage.assistant(resp));

                        GenArtifacts candidate;
                        try {
                            ArtifactParser.Delta delta = parser.parseDelta(resp);
                            candidate = mergeDelta(lastGood, delta);
                        } catch (Exception pe) {
                            job.addAttempt(attempt(totalRounds, "generate", false, "Lô thêm sai định dạng: " + pe.getMessage()));
                            if (attempt < 2) { convo.add(LlmMessage.user("Phản hồi chưa đúng: " + pe.getMessage()
                                    + " — trả lại ĐÚNG 1 object JSON delta { new_tests, skills_matrix.json, solution_patch? }.")); continue; }
                            break;
                        }

                        int newCount = parser.countTestcases(candidate.skillsMatrix());
                        job.setMessage("Pha A — biên dịch & chạy thử " + newCount + " testcase...");
                        CompileResult cr = session.checkSolution(candidate);
                        job.addAttempt(compileAttempt(totalRounds, "compile", cr, cr.ok()));
                        if (cr.ok() && newCount > count) {
                            lastGood = candidate;
                            job.setArtifacts(lastGood);
                            accepted = true;
                            break;
                        }
                        // TỐI ƯU: biên dịch OK nhưng vài test fail → BỎ test fail, GIỮ phần PASS (nếu vẫn tăng so với bản tốt → nhận).
                        GenArtifacts prunedC = tryPrune(session, cr, candidate, count + 1);
                        if (prunedC != null) {
                            lastGood = prunedC; job.setArtifacts(lastGood); accepted = true;
                            job.addAttempt(attempt(totalRounds, "prune", true, "Đã bỏ " + cr.failedTests().size()
                                    + " test fail, giữ " + parser.countTestcases(lastGood.skillsMatrix()) + " test xanh."));
                            break;
                        }
                        if (attempt < 2) convo.add(LlmMessage.user(prompts.fixDelta(cr)));   // 1 lượt sửa
                    }
                    if (accepted) {
                        noProgress = 0;
                    } else {
                        // lô thêm hỏng / không tăng → BỎ, giữ bản tốt; gỡ cả lượt khỏi hội thoại để khỏi lặp lỗi
                        truncate(convo, mark);
                        if (count >= minTestcases || ++noProgress >= 3) break;
                    }
                }
                art = lastGood;
                solutionOk = true;
            }

            // ĐỐI SOÁT exam_test ↔ matrix: bỏ entry matrix mồ côi (do cắt test nhưng tên ≠ key) để khỏi mất điểm oan.
            art = pruner.reconcile(art);
            job.setArtifacts(art);

            int finalCount = parser.countTestcases(art.skillsMatrix());
            if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }

            // ══ PHA B — ĐỀ BÀI PHÁT SV + KHUNG CODE (STARTER) ══
            int starterRounds = Math.min(Math.max(1, maxAttempts), 3);
            List<LlmMessage> convoB = new ArrayList<>();
            convoB.add(LlmMessage.system(prompts.systemStarter()));
            convoB.add(LlmMessage.user(prompts.userStarter(art)));
            PhaseB pb = phaseB(job, session, art, convoB);
            if (pb == null) return;            // bị huỷ trong pha B (đã terminate CANCELLED)
            art = pb.art();
            boolean starterOk = pb.ok();

            // ══ KẾT LUẬN ══
            String countNote = finalCount + "/" + target + " testcase";
            if (solutionOk && starterOk)
                job.terminate("SUCCESS",
                        "Đã tạo đề bài + " + countNote + " + khung code: lời giải PASS hết và starter biên dịch sạch.");
            else if (solutionOk)
                job.terminate("NEEDS_REVIEW",
                        "Testcase đạt (" + countNote + "), nhưng KHUNG CODE chưa biên dịch sạch sau " + starterRounds + " lần — xem lại starter trước khi phát.");
            else
                job.terminate("NEEDS_REVIEW",
                        "Chưa hội tụ pha testcase (" + countNote + ") sau " + totalRounds + " lần — đã giữ bản gần nhất, xem & chỉnh tay nếu cần.");

            }   // đóng phiên compile (container ấm tự dọn)
        } catch (Exception e) {
            if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
            log.warn("AI gen job {} lỗi: {}", job.id, e.getMessage());
            job.terminate("ERROR", "Lỗi: " + e.getMessage());
        }
    }

    /**
     * Yêu cầu CHỈNH SỬA 1 job đã xong theo prompt tự do của GV (chạy nền, cập nhật CHÍNH job đó).
     * Trả false nếu: job không tồn tại / đang chạy / chưa có kết quả để chỉnh / thiếu nội dung yêu cầu.
     */
    public boolean refine(String jobId, String instruction) {
        GenJob job = jobs.get(jobId);
        if (job == null) return false;
        if ("RUNNING".equals(job.status)) return false;          // đang chạy → không nhận thêm
        if (instruction == null || instruction.isBlank()) return false;
        GenArtifacts cur = job.getArtifacts();
        if (cur == null || !cur.hasCore() || job.convoA == null) return false;   // không có gì để chỉnh
        final String instr = instruction.trim();
        job.beginRefine("Đang chỉnh đề theo yêu cầu...");
        Thread t = new Thread(() -> runRefine(job, instr), "ai-refine-" + jobId);
        job.worker = t;
        t.start();
        return true;
    }

    /** Một lượt CHỈNH SỬA: áp yêu cầu vào pha A (testcase, incremental) rồi sinh lại pha B (đề + khung). */
    private void runRefine(GenJob job, String instruction) {
        try {
            if (!llm.isReady()) { job.terminate("NO_API_KEY", "Chưa cắm API key."); return; }
            try (TestcaseCompiler.CompileSession session = compiler.openSession()) {
                GenArtifacts base = job.getArtifacts();   // bản tốt trước khi chỉnh (đã có đề/starter)
                int target = job.req != null ? targetFor(job.req) : parser.countTestcases(base.skillsMatrix());

                // ── PHA A (refine): nối yêu cầu vào hội thoại cũ → cập nhật testcase + lời giải, biên dịch-sửa ──
                List<LlmMessage> convo = job.convoA;
                convo.add(LlmMessage.user(prompts.refineTestcase(instruction)));
                GenArtifacts art = base, lastGood = base;   // nền đã tốt → fallback an toàn
                boolean solutionOk = true;
                int rounds = Math.max(2, maxAttempts);
                for (int round = 1; round <= rounds; round++) {
                    if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                    job.setMessage("Chỉnh đề — cập nhật testcase (lần " + round + ")...");
                    String resp;
                    try {
                        resp = llm.chat(convo);
                    } catch (Exception e) {
                        if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
                        job.addAttempt(attempt(round, "generate", false, "Gọi AI lỗi: " + e.getMessage()));
                        break;   // giữ bản nền
                    }
                    convo.add(LlmMessage.assistant(resp));

                    GenArtifacts parsed;
                    try {
                        parsed = parser.parse(resp).withGrader(canonicalGrader());
                    } catch (ArtifactParser.ParseException pe) {
                        job.addAttempt(attempt(round, "parse", false, pe.getMessage()));
                        convo.add(LlmMessage.user("Phản hồi chưa đúng định dạng: " + pe.getMessage()
                                + " — trả lại ĐÚNG 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution)."));
                        continue;
                    }
                    // giữ tạm đề/starter cũ để wizard vẫn xem được trong lúc chỉnh (pha B sẽ sinh lại)
                    art = parsed.withHandout(base.deBai(), base.starter());
                    job.setArtifacts(art);

                    job.setMessage("Chỉnh đề — biên dịch & chạy thử lời giải (lần " + round + ")...");
                    CompileResult cr = session.checkSolution(art);
                    job.addAttempt(compileAttempt(round, "compile", cr, cr.ok()));
                    if (cr.ok()) { solutionOk = true; lastGood = art; break; }
                    solutionOk = false;
                    convo.add(LlmMessage.user(prompts.fixSolution(cr, round)));
                }
                if (!solutionOk) { art = lastGood; solutionOk = true; job.setArtifacts(art); }   // về bản nền tốt

                if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }

                // ── PHA B (refine): sinh lại đề bài + khung code khớp testcase mới + ý GV ──
                List<LlmMessage> convoB = new ArrayList<>();
                convoB.add(LlmMessage.system(prompts.systemStarter()));
                convoB.add(LlmMessage.user(prompts.userStarter(art, instruction, base.deBai())));
                PhaseB pb = phaseB(job, session, art, convoB);
                if (pb == null) return;   // bị huỷ
                art = pb.art();
                boolean starterOk = pb.ok();

                int finalCount = parser.countTestcases(art.skillsMatrix());
                String countNote = finalCount + "/" + target + " testcase";
                int starterRounds = Math.min(Math.max(1, maxAttempts), 3);
                if (solutionOk && starterOk)
                    job.terminate("SUCCESS",
                            "Đã chỉnh theo yêu cầu: " + countNote + ", lời giải PASS hết & khung code biên dịch sạch.");
                else
                    job.terminate("NEEDS_REVIEW",
                            "Đã chỉnh (" + countNote + ") nhưng khung code chưa biên dịch sạch sau " + starterRounds + " lần — xem lại starter trước khi phát.");
            }
        } catch (Exception e) {
            if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return; }
            log.warn("AI refine job {} lỗi: {}", job.id, e.getMessage());
            job.terminate("ERROR", "Lỗi khi chỉnh: " + e.getMessage());
        }
    }

    /** Kết quả pha B dùng chung. */
    private record PhaseB(GenArtifacts art, boolean ok) {}

    /**
     * PHA B dùng chung cho cả sinh ban đầu lẫn refine: sinh đề bài + khung code, biên dịch-sửa tối đa
     * {@code starterRounds} lần. Trả {@code null} nếu bị HUỶ (đã terminate CANCELLED).
     */
    private PhaseB phaseB(GenJob job, TestcaseCompiler.CompileSession session,
                          GenArtifacts art, List<LlmMessage> convoB) {
        boolean starterOk = false;
        int starterRounds = Math.min(Math.max(1, maxAttempts), 3);
        for (int round = 1; round <= starterRounds; round++) {
            if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return null; }
            job.setMessage("Pha B — sinh đề bài + khung code cho SV (lần " + round + "/" + starterRounds + ")...");
            String resp;
            try {
                resp = llm.chat(convoB);
            } catch (Exception e) {
                if (job.isCancelled()) { job.terminate("CANCELLED", "Đã dừng theo yêu cầu."); return null; }
                job.addAttempt(attempt(round, "starter-gen", false, "Gọi AI lỗi: " + e.getMessage()));
                break;   // thiếu starter vẫn trả phần testcase
            }
            convoB.add(LlmMessage.assistant(resp));

            ArtifactParser.Handout h;
            try {
                h = parser.parseHandout(resp);
            } catch (ArtifactParser.ParseException pe) {
                job.addAttempt(attempt(round, "starter-parse", false, pe.getMessage()));
                convoB.add(LlmMessage.user("Phản hồi chưa đúng định dạng: " + pe.getMessage()
                        + " — trả lại ĐÚNG 1 object JSON { de_bai, starter }."));
                continue;
            }
            art = art.withHandout(h.deBai(), h.starter());
            job.setArtifacts(art);

            job.setMessage("Pha B — biên dịch thử khung code (lần " + round + ")...");
            CompileResult sc = session.checkStarter(art);
            job.addAttempt(compileAttempt(round, "starter", sc, sc.compiled()));
            if (sc.compiled()) { starterOk = true; break; }
            convoB.add(LlmMessage.user(prompts.fixStarter(sc, round)));
        }
        return new PhaseB(art, starterOk);
    }

    private Map<String, Object> attempt(int round, String phase, boolean ok, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("phase", phase);
        m.put("ok", ok);
        m.put("summary", note);
        return m;
    }

    private Map<String, Object> compileAttempt(int round, String phase, CompileResult cr, boolean ok) {
        Map<String, Object> a = cr.toMap();
        a.put("round", round);
        a.put("phase", phase);
        a.put("ok", ok);
        return a;
    }

    /** Mục tiêu số testcase: null/0 = TỰ ĐỘNG (autoTarget); có nhập thì kẹp trong [minTestcases, 80]. */
    private int targetFor(GenRequest req) {
        int floor = Math.max(1, minTestcases);
        Integer n = req == null ? null : req.numTestcases();
        if (n == null || n <= 0) return Math.max(floor, autoTarget);
        return Math.min(80, Math.max(floor, n));
    }

    /** Gỡ các message thêm sau mốc {@code mark} (dùng để BỎ 1 lô hỏng khỏi hội thoại). */
    private static void truncate(List<LlmMessage> convo, int mark) {
        while (convo.size() > mark) convo.remove(convo.size() - 1);
    }

    /**
     * Khi {@code cr} là "biên dịch OK nhưng vài test FAIL": BỎ các test fail (giữ phần PASS) rồi KIỂM LẠI.
     * Trả bản XANH (compile + PASS hết) nếu còn ≥ {@code minKeep} test; ngược lại null (để caller xử lý cách khác).
     */
    private GenArtifacts tryPrune(TestcaseCompiler.CompileSession session, CompileResult cr,
                                  GenArtifacts art, int minKeep) {
        if (!cr.compiled() || cr.allPassed() || cr.failedTests().isEmpty()) return null;
        GenArtifacts pruned = pruner.prune(art, cr.failedTests());
        if (parser.countTestcases(pruned.skillsMatrix()) < minKeep) return null;   // bỏ nhiều quá → không lợi
        CompileResult cr2 = session.checkSolution(pruned);
        return cr2.ok() ? pruned : null;     // chỉ nhận khi đã thật sự XANH
    }

    /** Ghép 1 lô DELTA (test/matrix/solution mới) vào bộ artifacts hiện có → bản ứng viên để biên dịch. */
    private GenArtifacts mergeDelta(GenArtifacts base, ArtifactParser.Delta d) {
        String examTest = mergeExamTest(base.examTest(), d);
        String matrix = parser.mergeMatrix(base.skillsMatrix(), d.matrixEntries());
        Map<String, String> sol = new LinkedHashMap<>(base.solution() == null ? Map.of() : base.solution());
        if (d.solutionPatch() != null) sol.putAll(d.solutionPatch());   // ghi đè/thêm file lib cần thiết
        return new GenArtifacts(examTest, base.graderDart(), matrix, sol,
                base.starter(), base.deBai(), base.assumptions());
    }

    /**
     * Ghép phần MỚI vào exam_test.dart: import mới (sau import cuối), class top-level mới (trước main),
     * test mới (TRƯỚC dấu '}' đóng main = dấu '}' cuối file). Nếu ghép ra mã sai → vòng biên dịch sẽ loại bỏ.
     */
    private String mergeExamTest(String base, ArtifactParser.Delta d) {
        String t = base == null ? "" : base;
        if (d.newImports() != null) {
            for (String imp : d.newImports()) {
                if (imp == null || imp.isBlank() || t.contains(imp.trim())) continue;
                int li = t.lastIndexOf("\nimport ");
                int nl = li >= 0 ? t.indexOf('\n', li + 1) : -1;
                if (nl >= 0) t = t.substring(0, nl + 1) + imp.trim() + "\n" + t.substring(nl + 1);
                else t = imp.trim() + "\n" + t;
            }
        }
        if (d.newTopLevel() != null && !d.newTopLevel().isBlank()) {
            int mi = t.indexOf("void main(");
            if (mi < 0) mi = t.indexOf("main(");
            if (mi < 0) mi = 0;
            t = t.substring(0, mi) + d.newTopLevel().strip() + "\n\n" + t.substring(mi);
        }
        String tests = d.newTests() == null ? "" : d.newTests().strip();
        if (!tests.isEmpty()) {
            int lb = t.lastIndexOf('}');
            if (lb >= 0) t = t.substring(0, lb) + "\n" + tests + "\n" + t.substring(lb);
            else t = t + "\n" + tests + "\n";
        }
        return t;
    }

    // ── grader.dart chuẩn (nạp từ classpath 1 lần) ───────────────
    private String canonicalGrader() {
        String g = canonicalGrader;
        if (g != null) return g;
        try (var in = new ClassPathResource("ai/grader.dart").getInputStream()) {
            g = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Không nạp được resources/ai/grader.dart: {}", e.getMessage());
            g = "// LỖI: thiếu grader.dart chuẩn trong resources/ai/";
        }
        canonicalGrader = g;
        return g;
    }

    private byte[] zip(Map<String, String> files) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write((e.getValue() == null ? "" : e.getValue()).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Giữ tối đa ~50 job gần nhất để khỏi phình bộ nhớ. */
    private void evictOld() {
        if (jobs.size() <= 50) return;
        jobs.values().stream()
                .sorted((a, b) -> Long.compare(a.createdAt, b.createdAt))
                .limit(Math.max(0, jobs.size() - 50))
                .forEach(j -> jobs.remove(j.id));
    }

    // ── Job (mutable, đọc/ghi đồng bộ) ───────────────────────────
    private static class GenJob {
        final String id;
        final long createdAt = System.currentTimeMillis();
        final Map<String, Object> providerInfo;
        final List<Map<String, Object>> attempts = new ArrayList<>();
        volatile String status = "RUNNING";
        volatile String message = "Đang khởi tạo...";
        volatile GenArtifacts artifacts;
        volatile boolean cancelled = false;   // người dùng bấm Dừng
        volatile Thread worker;                // luồng chạy job (để ngắt lần gọi đang chờ)
        volatile GenRequest req;               // yêu cầu gốc (để biết mục tiêu khi refine)
        volatile List<LlmMessage> convoA;      // hội thoại pha A — giữ lại để CHỈNH SỬA tiếp

        GenJob(String id, Map<String, Object> providerInfo) {
            this.id = id;
            this.providerInfo = providerInfo;
        }

        synchronized void setMessage(String m) { this.message = m; }
        synchronized void addAttempt(Map<String, Object> a) { attempts.add(a); }
        synchronized void setArtifacts(GenArtifacts a) { this.artifacts = a; }
        synchronized GenArtifacts getArtifacts() { return artifacts; }
        synchronized void terminate(String status, String message) { this.status = status; this.message = message; }
        /** Mở 1 lượt CHỈNH SỬA: về RUNNING, xoá timeline cũ, cho phép Dừng lại từ đầu (giữ artifacts). */
        synchronized void beginRefine(String msg) {
            this.status = "RUNNING"; this.message = msg; this.cancelled = false; this.attempts.clear();
        }
        void cancel() { this.cancelled = true; Thread w = worker; if (w != null) w.interrupt(); }
        boolean isCancelled() { return cancelled; }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", id);
            m.put("status", status);
            m.put("message", message);
            m.put("provider", providerInfo.get("provider"));
            m.put("model", providerInfo.get("model"));
            m.put("createdAt", createdAt);
            m.put("attempts", new ArrayList<>(attempts));
            if (artifacts != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("assumptions", artifacts.assumptions());
                result.put("de_bai", artifacts.deBai());              // đề bài phát SV (markdown)
                List<Map<String, String>> files = new ArrayList<>();
                addFile(files, "exam_test.dart", artifacts.examTest());
                addFile(files, "skills_matrix.json", artifacts.skillsMatrix());
                addFile(files, "grader.dart", artifacts.graderDart());
                result.put("files", files);
                List<Map<String, String>> starter = new ArrayList<>();   // khung code phát SV
                if (artifacts.starter() != null)
                    artifacts.starter().forEach((k, v) -> addFile(starter, k, v));
                result.put("starter", starter);
                List<Map<String, String>> sol = new ArrayList<>();
                if (artifacts.solution() != null)
                    artifacts.solution().forEach((k, v) -> addFile(sol, k, v));
                result.put("solution", sol);
                m.put("result", result);
            }
            return m;
        }

        private void addFile(List<Map<String, String>> list, String name, String content) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("content", content == null ? "" : content);
            list.add(f);
        }
    }
}
