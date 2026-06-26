package com.example.grader.service;

import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghi ĐỒNG THỜI syllabus của GRADER (DB) sang KHO SKILL của BOT, để khi GV thêm/sửa/xóa ở
 * "Khung năng lực" thì file syllabus của bot (skill_mapping.json) + nguồn RAG (10_skillcode...md)
 * cập nhật ngay → không lệch khung kiến thức.
 *
 * <p>Lưu ý: CHỈ số RAG (ChromaDB) cần re-embed (python) → cập nhật khi bot khởi động lại hoặc chạy
 * {@code sync-skills}. Lời nhận xét vẫn đúng vì phần GROUNDING dùng skill_code/skill_name trong result_json.
 */
@Slf4j
@Component
public class BotSyllabusSync {

    /** Thư mục data của bot (sibling của grader). Mặc định hợp với start-all (working dir = grader/). */
    @Value("${feedback.bot-data-dir:../feedback-bot/data}")
    private String botDataDir;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Ghi skill_mapping.json + RAG skillcode doc của bot từ syllabus hiện tại. Không bao giờ ném ra ngoài. */
    public void sync(List<SkillCategory> cats, List<Skill> skills) {
        try {
            Path base = Path.of(botDataDir);
            if (!Files.isDirectory(base)) { return; }   // không thấy bot (vd chạy nơi khác) → bỏ qua êm

            // Gom skill theo category
            Map<String, List<Skill>> byCat = new LinkedHashMap<>();
            for (SkillCategory c : cats) byCat.put(c.getCode(), new ArrayList<>());
            for (Skill s : skills) byCat.computeIfAbsent(s.getCategoryCode(), k -> new ArrayList<>()).add(s);

            writeSkillMapping(base, cats, byCat);
            writeRagDoc(base, cats, byCat);
            log.info("Đã đồng bộ syllabus sang bot ({} category, {} skill)", cats.size(), skills.size());
        } catch (Exception e) {
            log.warn("Đồng bộ syllabus sang bot lỗi (bỏ qua): {}", e.getMessage());
        }
    }

    private void writeSkillMapping(Path base, List<SkillCategory> cats, Map<String, List<Skill>> byCat) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "synced");
        root.put("description", "Bản đồ skill (đồng bộ TỰ ĐỘNG từ Khung năng lực của grader). Nguồn chuẩn: grader DB.");
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> byCode = new LinkedHashMap<>();
        for (SkillCategory c : cats) {
            List<Skill> list = byCat.getOrDefault(c.getCode(), List.of());
            String catLabel = c.getCompetencyLabel() != null ? c.getCompetencyLabel() : c.getName();
            List<Map<String, Object>> codes = new ArrayList<>();
            for (Skill s : list) {
                Map<String, Object> sc = new LinkedHashMap<>();
                sc.put("skill_code", s.getCode());
                sc.put("skill_name", s.getName());
                codes.add(sc);
                Map<String, Object> bc = new LinkedHashMap<>();
                bc.put("skill", c.getCode());
                bc.put("skill_name", s.getName());
                bc.put("category", c.getCode());
                bc.put("category_label", catLabel);
                byCode.put(s.getCode(), bc);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("skill", c.getCode());
            g.put("skill_label", c.getName());
            g.put("category", c.getCode());
            g.put("category_label", catLabel);
            g.put("skill_codes", codes);
            groups.add(g);
        }
        root.put("skills", groups);
        root.put("by_skill_code", byCode);
        Files.writeString(base.resolve("skill_mapping.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private void writeRagDoc(Path base, List<SkillCategory> cats, Map<String, List<Skill>> byCat) throws Exception {
        Path docDir = base.resolve("rag_docs").resolve("skills");
        if (!Files.isDirectory(docDir)) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# Bản đồ skill_code SYLLABUS (đồng bộ từ Khung năng lực) — nhận xét đúng kỹ năng\n\n");
        sb.append("Mỗi mục là MỘT skill_code HIỆN HÀNH (đúng mã hệ thống chấm dùng).\n");
        for (SkillCategory c : cats) {
            String catLabel = c.getCompetencyLabel() != null ? c.getCompetencyLabel() : c.getName();
            for (Skill s : byCat.getOrDefault(c.getCode(), List.of())) {
                String desc = (s.getDescription() != null && !s.getDescription().isBlank()) ? s.getDescription() : s.getName();
                sb.append("\n---\n\n");
                sb.append("## skill_code: `").append(s.getCode()).append("`\n");
                sb.append("**Tên kỹ năng:** ").append(s.getName()).append(" · **Nhóm năng lực:** ")
                  .append(catLabel).append(" (`").append(c.getCode()).append("`)\n\n");
                sb.append("### Ý nghĩa\n").append(desc).append("\n\n");
                sb.append("### Khi viết nhận xét\n");
                sb.append("- `").append(s.getCode()).append("` PASS → ghi nhận em đã nắm vững “").append(s.getName()).append("”.\n");
                sb.append("- `").append(s.getCode()).append("` FAIL/ERROR → nêu em cần củng cố “").append(s.getName())
                  .append("”; gợi ý ôn lại nhóm “").append(catLabel).append("”.\n\n");
                sb.append("### Từ khóa\n").append(s.getCode()).append(", ").append(s.getName())
                  .append(", ").append(catLabel).append("\n");
            }
        }
        Files.writeString(docDir.resolve("10_skillcode_syllabus_hien_hanh.md"), sb.toString(), StandardCharsets.UTF_8);
    }
}
