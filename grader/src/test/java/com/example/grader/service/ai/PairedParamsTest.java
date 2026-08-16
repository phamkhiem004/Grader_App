package com.example.grader.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tham số đi theo CẶP phải cùng số phần tử: {@code fieldKeys} ↔ {@code invalidValues} ↔
 * {@code errorKeys}.
 *
 * <p>AI rất hay trả 2 ô nhập nhưng chỉ 1 giá trị sai. Lỗi khi đó chỉ nổ lúc BẤM LƯU cả bộ
 * ("fieldKeys, invalidValues và errorKeys phải cùng số phần tử ở PE_62_item_06"), và người dùng
 * phải tự dò xem item_06 là testcase nào rồi tự đếm lại từng danh sách. Chỉnh ngay tại nguồn thì
 * bộ testcase luôn lưu được.
 */
class PairedParamsTest {

    /** Khai giống catalog thật: hai danh sách con trỏ về `fieldKeys` qua `pair_with`. */
    private static final List<Map<String, Object>> SCHEMA = List.of(
            Map.of("name", "fieldKeys", "type", "semantic_keys"),
            Map.of("name", "invalidValues", "type", "values", "pair_with", "fieldKeys"),
            Map.of("name", "errorKeys", "type", "semantic_keys", "pair_with", "fieldKeys"));

    private Map<String, Object> align(Map<String, Object> params) {
        AiExamAuthorService service = new AiExamAuthorService();
        ReflectionTestUtils.invokeMethod(service, "alignPairedLists", params, SCHEMA);
        return params;
    }

    private Map<String, Object> params(String fields, String values, String errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldKeys", fields);
        m.put("invalidValues", values);
        m.put("errorKeys", errors);
        return m;
    }

    @Test
    void buThemKhiDanhSachDiKemBiThieu() {
        Map<String, Object> fixed = align(params(
                "field.fullName,field.email", "ab", "error.fullName,error.email"));

        assertEquals("ab,ab", fixed.get("invalidValues"),
                "Thiếu giá trị sai thì lặp lại phần tử cuối, không để lệch số phần tử");
        assertEquals("error.fullName,error.email", fixed.get("errorKeys"), "Danh sách đủ thì giữ nguyên");
    }

    @Test
    void catBotKhiDanhSachDiKemBiThua() {
        Map<String, Object> fixed = align(params(
                "field.email", "ab,cd,ef", "error.email,error.fullName"));

        assertEquals("ab", fixed.get("invalidValues"));
        assertEquals("error.email", fixed.get("errorKeys"));
    }

    @Test
    void danhSachDaKhopThiKhongDungToi() {
        Map<String, Object> before = params(
                "field.fullName,field.email", "a,b", "error.fullName,error.email");
        Map<String, Object> fixed = align(new LinkedHashMap<>(before));
        assertEquals(before, fixed);
    }

    /** Thiếu hẳn một tham số thì để nguyên — bộ kiểm khi lưu mới là chỗ báo "thiếu tham số bắt buộc". */
    @Test
    void thieuHanThamSoThiKhongTuBia() {
        Map<String, Object> only = new LinkedHashMap<>();
        only.put("fieldKeys", "field.email");
        Map<String, Object> fixed = align(only);
        assertEquals(1, fixed.size());
        assertFalse(fixed.containsKey("invalidValues"));
    }
}
