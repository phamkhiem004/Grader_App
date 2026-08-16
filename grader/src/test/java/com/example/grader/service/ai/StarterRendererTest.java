package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Khung starter phát cho sinh viên chỉ được có class + hằng số key; UI và logic là phần thi.
 * Test này chốt đúng ranh giới đó: dù bản mô tả của AI có cố nhét sẵn thân hàm hay giao diện,
 * code sinh ra vẫn phải là TODO.
 */
class StarterRendererTest {

    private static final String SPEC = """
            {
              "entry_class": "HomeScreen",
              "files": [
                {"path":"lib/models/user.dart","kind":"model","class_name":"User","doc":"Người dùng",
                 "fields":[{"name":"id","type":"int?","doc":"Khóa chính"},
                           {"name":"fullName","type":"String","doc":"Họ tên"}],
                 "methods":[{"signature":"Map<String, dynamic> toMap()","doc":"đổi sang map"}]},
                {"path":"lib/repositories/user_repository.dart","kind":"repository",
                 "class_name":"UserRepository","doc":"Truy xuất dữ liệu",
                 "methods":[{"signature":"Future<List<User>> getUsers()","doc":"đọc danh sách"},
                            {"signature":"Future<void> addUser(User user)","doc":"thêm mới"}]},
                {"path":"lib/screens/home_screen.dart","kind":"screen","class_name":"HomeScreen",
                 "doc":"Màn hình danh sách","keys":["screen.home","field.fullName","action.save"]}
              ]
            }
            """;

    private JsonNode spec(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private Map<String, Object> file(StarterRenderer.Rendered r, String path) {
        return r.files().stream().filter(f -> path.equals(f.get("path"))).findFirst()
                .orElseThrow(() -> new AssertionError("Thiếu file " + path + " trong " + r.files()));
    }

    private String content(StarterRenderer.Rendered r, String path) {
        return String.valueOf(file(r, path).get("content"));
    }

    @Test
    void sinhKhungDayDuNhungThanHamLuonLaTodo() throws Exception {
        StarterRenderer.Rendered r = new StarterRenderer().render(
                spec(SPEC), "class ExamKeys {}\n", Map.of("field.fullName", "Ô nhập họ tên"));

        // Đủ file: 3 file AI mô tả + exam_keys + main
        assertEquals(5, r.files().size(), "Phải có đủ khung + exam_keys.dart + main.dart");

        String model = content(r, "lib/models/user.dart");
        assertTrue(model.contains("class User {"));
        assertTrue(model.contains("required this.fullName"), "Trường không null phải required");
        assertTrue(model.contains("this.id"), "Trường nullable vẫn nằm trong constructor");
        assertFalse(model.contains("required this.id"), "Trường nullable KHÔNG được required");
        assertTrue(model.contains("final int? id;"));
        assertTrue(model.contains("throw UnimplementedError("), "Thân hàm phải là TODO");

        String repo = content(r, "lib/repositories/user_repository.dart");
        assertTrue(repo.contains("import '../models/user.dart';"), "Import phải tự tính, ra đường dẫn tương đối");
        assertTrue(repo.contains("Future<List<User>> getUsers() {"));
        assertEquals(2, repo.split("throw UnimplementedError\\(").length - 1, "Cả hai hàm đều để trống");

        String screen = content(r, "lib/screens/home_screen.dart");
        assertTrue(screen.contains("import 'package:flutter/material.dart';"));
        assertTrue(screen.contains("class HomeScreen extends StatelessWidget"));
        assertTrue(screen.contains("return const Placeholder();"), "Giao diện để trống cho sinh viên");
        assertTrue(screen.contains("/// - field.fullName: Ô nhập họ tên"), "Phải ghi key kèm nhãn");
        assertTrue(screen.contains("TODO(sinh viên)"));

        String main = content(r, "lib/main.dart");
        assertTrue(main.contains("import 'screens/home_screen.dart';"));
        assertTrue(main.contains("home: HomeScreen(),"), "main phải mở đúng màn hình chính");

        assertTrue(r.warnings().isEmpty(), "Bản mô tả hợp lệ thì không có cảnh báo: " + r.warnings());
    }

    @Test
    void tuChoiMoiChuKyKemSanCodeCuaAi() throws Exception {
        String sneaky = """
                {"entry_class":"HomeScreen","files":[
                  {"path":"lib/services/logic.dart","kind":"service","class_name":"Logic","methods":[
                    {"signature":"int add(int a, int b) => a + b"},
                    {"signature":"void run() { doEverything(); }"},
                    {"signature":"String greet();"},
                    {"signature":"bool isValid(String email)"}]}]}
                """;
        StarterRenderer.Rendered r = new StarterRenderer().render(spec(sneaky), null, Map.of());
        String code = content(r, "lib/services/logic.dart");

        assertFalse(code.contains("=>"), "Không được giữ lại thân hàm dạng =>");
        assertFalse(code.contains("doEverything"), "Không được giữ lại code AI nhét vào");
        assertTrue(code.contains("bool isValid(String email) {"), "Chữ ký hợp lệ vẫn được giữ");
        assertTrue(code.contains("throw UnimplementedError("));
        assertEquals(3, r.warnings().stream().filter(w -> w.contains("chữ ký")).count(),
                "Ba chữ ký hỏng phải được báo: " + r.warnings());
    }

    /**
     * Getter là chữ ký TRẦN hợp lệ của Dart, chỉ khác ở chỗ không có ngoặc. Luật "phải kết thúc
     * bằng ')'" từng loại oan đúng những thành viên khung starter cần khai nhất — `count` của
     * ViewModel, `isEditMode` của màn hình form — kèm cảnh báo đọc như thể AI viết sai.
     */
    @Test
    void giuLaiGetterVaVanDeThanHamLaTodo() throws Exception {
        String withGetters = """
                {"entry_class":"HomeScreen","files":[
                  {"path":"lib/models/user.dart","kind":"model","class_name":"User",
                   "fields":[{"name":"id","type":"int?"}]},
                  {"path":"lib/viewmodels/user_viewmodel.dart","kind":"viewmodel",
                   "class_name":"UserViewModel","methods":[
                    {"signature":"int get count","doc":"số người dùng hiện có"},
                    {"signature":"List<User> get users"}]},
                  {"path":"lib/screens/home_screen.dart","kind":"screen","class_name":"HomeScreen",
                   "methods":[{"signature":"bool get isEditMode"}]}]}
                """;
        StarterRenderer.Rendered r = new StarterRenderer().render(spec(withGetters), null, Map.of());

        String vm = content(r, "lib/viewmodels/user_viewmodel.dart");
        assertTrue(vm.contains("int get count {"), "Getter phải được sinh ra: " + vm);
        assertTrue(vm.contains("List<User> get users {"), "Getter kiểu generic cũng phải qua");
        assertTrue(vm.contains("UserViewModel.count"), "Thân getter vẫn là TODO ném lỗi");
        assertEquals(2, vm.split("throw UnimplementedError\\(").length - 1);
        assertTrue(vm.contains("import '../models/user.dart';"), "Kiểu trong getter vẫn phải kéo import");

        assertTrue(content(r, "lib/screens/home_screen.dart").contains("bool get isEditMode {"));
        assertTrue(r.warnings().isEmpty(), "Getter hợp lệ thì KHÔNG được cảnh báo: " + r.warnings());
    }

    /**
     * Thân hàm đã bị ép thành TODO, nên đường cuối cùng để AI tuồn lời giải vào khung là ô "doc".
     * Chú thích phải rút về một dòng ngắn, không thành bài mẫu chép sẵn trong comment.
     */
    @Test
    void chuThichBiRutVeMotDongNgan() throws Exception {
        String chatty = """
                {"entry_class":"HomeScreen","files":[
                  {"path":"lib/screens/home_screen.dart","kind":"screen","class_name":"HomeScreen",
                   "doc":"Màn hình danh sách.\\nBước 1: dựng Scaffold với AppBar tiêu đề 'Danh sách người dùng (n)'.\\nBước 2: thân là ListView.builder duyệt viewModel.users, mỗi item là Card chứa ListTile.\\nBước 3: FloatingActionButton điều hướng sang form bằng Navigator.push.",
                   "methods":[{"signature":"void openForm()",
                     "doc":"Gọi Navigator.push(context, MaterialPageRoute(builder: (_) => UserFormScreen())) rồi setState để làm mới danh sách sau khi quay lại màn hình trước đó"}]}]}
                """;
        String code = content(new StarterRenderer().render(spec(chatty), null, Map.of()),
                "lib/screens/home_screen.dart");

        assertFalse(code.contains("Bước 2"), "Chú thích nhiều dòng phải bị rút gọn: " + code);
        assertFalse(code.contains("MaterialPageRoute"), "Không được để lộ nguyên lời giải trong comment");
        for (String line : code.split("\n")) {
            assertTrue(line.length() <= 140, "Dòng quá dài (chú thích chưa được cắt): " + line);
        }
        assertTrue(code.contains("return const Placeholder();"), "Giao diện vẫn phải để trống");
    }

    @Test
    void loaiKieuDuLieuKhongCoTrongKhung() throws Exception {
        String risky = """
                {"entry_class":"HomeScreen","files":[
                  {"path":"lib/repositories/db.dart","kind":"repository","class_name":"Db",
                   "fields":[{"name":"database","type":"Database"},{"name":"name","type":"String"}],
                   "methods":[{"signature":"Future<void> open(Database db)"},
                              {"signature":"Future<int> count()"}]}]}
                """;
        StarterRenderer.Rendered r = new StarterRenderer().render(spec(risky), null, Map.of());
        String code = content(r, "lib/repositories/db.dart");

        assertFalse(code.contains("Database"), "Kiểu của thư viện ngoài phải bị loại, tránh starter không build được");
        assertTrue(code.contains("final String name;"));
        assertTrue(code.contains("Future<int> count()"));
        assertEquals(2, r.warnings().stream().filter(w -> w.contains("Db.")).count(),
                "Phải nói rõ đã bỏ thuộc tính và hàm nào: " + r.warnings());
    }

    @Test
    void boQuaFileHeThongTuDungVaDuongDanXau() throws Exception {
        String bad = """
                {"entry_class":"HomeScreen","files":[
                  {"path":"lib/main.dart","kind":"screen","class_name":"MyApp"},
                  {"path":"lib/exam_keys.dart","kind":"other","class_name":"ExamKeys"},
                  {"path":"../../etc/passwd","kind":"other","class_name":"Evil"},
                  {"path":"lib/screens/home_screen.dart","kind":"screen","class_name":"HomeScreen"}]}
                """;
        StarterRenderer.Rendered r = new StarterRenderer().render(bad != null ? spec(bad) : null,
                "class ExamKeys {}\n", Map.of());

        List<String> paths = r.files().stream().map(f -> String.valueOf(f.get("path"))).toList();
        assertEquals(List.of("lib/exam_keys.dart", "lib/screens/home_screen.dart", "lib/main.dart"), paths);
        assertTrue(content(r, "lib/exam_keys.dart").contains("class ExamKeys"),
                "exam_keys.dart phải là bản dựng từ hợp đồng, không phải bản AI viết");
    }

    /** Xuất khung ra target/ để kiểm cú pháp thật bằng Dart trong ảnh chấm. */
    @Test
    void xuatKhungDeKiemCuPhapBangDart() throws Exception {
        StarterRenderer.Rendered r = new StarterRenderer().render(
                spec(SPEC), "class ExamKeys { const ExamKeys._(); }\n", Map.of());
        // Giữ NGUYÊN cây thư mục thật: import tương đối chỉ kiểm chứng được khi lib/ đúng cấu trúc.
        Path dir = Path.of("target", "starter-preview");
        for (Map<String, Object> f : r.files()) {
            Path out = dir.resolve(String.valueOf(f.get("path")));
            Files.createDirectories(out.getParent());
            Files.writeString(out, String.valueOf(f.get("content")), StandardCharsets.UTF_8);
        }
        try (var walk = Files.walk(dir)) {
            assertEquals(5, walk.filter(p -> p.toString().endsWith(".dart")).count());
        }
    }
}
