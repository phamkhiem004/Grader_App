package com.example.grader.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Locale;
import java.util.Objects;

/**
 * Giữ tương thích với database đã được tạo bởi các phiên bản cũ.
 *
 * <p>Hibernate tạo {@code error_log LONGTEXT} trên database mới nhưng không tự nâng cột
 * {@code TINYTEXT} đã tồn tại. Khi nội dung chẩn đoán dài hơn 255 byte, giao dịch lưu kết quả
 * có thể bị rollback và giáo viên không thấy nguyên nhân thật.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaCompatibility implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String product;
            try (Connection connection = Objects.requireNonNull(jdbcTemplate.getDataSource())
                    .getConnection()) {
                product = connection.getMetaData().getDatabaseProductName();
            }
            if (!product.toLowerCase(Locale.ROOT).contains("mysql")) return;

            jdbcTemplate.execute("ALTER TABLE exam_results MODIFY COLUMN error_log LONGTEXT NULL");
            log.info("Đã xác nhận exam_results.error_log sử dụng LONGTEXT");
        } catch (Exception error) {
            // Không chặn backend nếu tài khoản DB không có quyền ALTER. BatchGradingService vẫn
            // cắt ngắn thông báo dự phòng; nội dung đầy đủ nằm trong details/result_json.
            log.warn("Không thể nâng exam_results.error_log lên LONGTEXT: {}", error.getMessage());
        }
    }
}
