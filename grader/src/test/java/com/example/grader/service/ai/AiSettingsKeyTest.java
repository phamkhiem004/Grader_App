package com.example.grader.service.ai;

import com.example.grader.entity.AiSetting;
import com.example.grader.repository.AiSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Key vừa tạo, nhìn đúng y hệt, mà hãng vẫn trả 401 API key is invalid".
 *
 * <p>Thủ phạm hay gặp nhất là KÝ TỰ VÔ HÌNH dính theo lúc copy từ trang web / email / khung chat:
 * zero-width space (U+200B), BOM (U+FEFF), non-breaking space, soft hyphen. Mắt không thấy, ô key
 * lại luôn hiện dạng che nên không có cách nào soi ra — và {@code Character.isWhitespace} KHÔNG
 * bắt được U+200B/U+FEFF, nên bộ kiểm cũ cho lọt thẳng xuống DB.
 */
class AiSettingsKeyTest {

    /** Key Claude thật dài 108 ký tự; test dùng chuỗi giả cùng khuôn, không dùng key thật của ai. */
    private static final String KEY = "sk-ant-api03-" + "A1b2C3d4E5f6G7h8I9j0".repeat(4) + "-xyz0123456789A";

    private record Fixture(AiSettingsService settings, AiSetting saved) {}

    private Fixture service() {
        AiSetting row = new AiSetting();
        AiSettingRepository repo = mock(AiSettingRepository.class);
        when(repo.findById(AiSetting.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repo.save(any(AiSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        AiSettingsService settings = new AiSettingsService();
        ReflectionTestUtils.setField(settings, "repo", repo);
        ReflectionTestUtils.setField(settings, "model", "claude-haiku-4-5");
        ReflectionTestUtils.setField(settings, "timeoutSeconds", 180);
        return new Fixture(settings, row);
    }

    private Map<String, Object> body(String apiKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", "claude-haiku-4-5");
        m.put("apiKey", apiKey);
        return m;
    }

    @Test
    void catKyTuVoHinhDinhTheoKhiCopyKey() {
        Fixture f = service();
        // Đúng cái key trên, nhưng dính zero-width space giữa chừng và BOM + nbsp hai đầu.
        String dirty = "﻿" + KEY.substring(0, 20) + "​" + KEY.substring(20) + " ";

        f.settings().update(body(dirty), "test");

        assertEquals(KEY, f.saved().getApiKey(),
                "Key lưu xuống DB phải sạch ký tự vô hình, nếu không hãng luôn trả 401");
        assertEquals(108, KEY.length(), "Khuôn key Claude dùng trong test phải đúng độ dài thật");
    }

    @Test
    void keySachThiGiuNguyenTungKyTu() {
        Fixture f = service();
        f.settings().update(body(KEY), "test");
        assertEquals(KEY, f.saved().getApiKey());
    }

    /** Độ dài key được phát ra ngoài để soi "dán thiếu/thừa" — nhưng KHÔNG bao giờ lộ nội dung. */
    @Test
    void baoDoDaiKeyNhungKhongLoNoiDung() {
        Fixture f = service();
        Map<String, Object> described = f.settings().update(body(KEY), "test");

        assertEquals(108, described.get("apiKeyLength"));
        String masked = String.valueOf(described.get("apiKeyMasked"));
        assertTrue(masked.contains("••••"), "Key phải hiện dạng che: " + masked);
        assertFalse(described.toString().contains(KEY), "Không được trả key nguyên vẹn ra API");
    }

    /** Ô key chỉ chứa ký tự vô hình = coi như không nhập, KHÔNG được ghi đè key đang dùng được. */
    @Test
    void danNhamChuoiRongThiGiuKeyCu() {
        Fixture f = service();
        f.settings().update(body(KEY), "test");
        f.settings().update(body("​﻿"), "test");
        assertEquals(KEY, f.saved().getApiKey(), "Key cũ phải còn nguyên");
    }
}
