# Bộ testcase chấm theo khung template mẫu — User CRUD V12

Chế độ `TODO_USER_V12` dùng cho bộ starter mà sinh viên chỉ hoàn thành các vị trí `TODO` có sẵn. Chế độ này không dùng Widget Key và không có `grading_adapter.dart`.

## Public contract không được đổi tên

- `lib/models/user_model.dart`: `UserModel`, `copyWith`, `fromMap`, `toMap`.
- `lib/database/database_service.dart`: `DatabaseService`, `SqliteDatabaseService`.
- `lib/repositories/user_repository.dart`: `UserRepository`, `SqliteUserRepository`.
- `lib/viewmodels/user_view_model.dart`: các provider đã phát và `UserViewModel`.
- `lib/screens/user_list_screen.dart`: `UserListScreen` và alias `HomeScreen`.
- `lib/screens/user_detail_screen.dart`: `UserDetailScreen`.
- `lib/main.dart`: `main`, `ProviderScope` và `MaterialApp`.

Sinh viên được tự thiết kế widget tree bên trong các screen, nhưng phải giữ file, public symbol và luồng chức năng mà starter quy định.

## Cách tạo đề

1. Mở **Tạo testcase** và chọn **Bộ testcase chấm theo khung template mẫu**.
2. Kéo các testcase Logic, Widget và Behavior cần chấm sang cột **Testcase trong đề**.
3. Chỉnh trọng số, độ khó hoặc mô tả rubric nếu cần. Không có tham số Key/setup/adapter trong chế độ này.
4. Lưu Draft, xem ba file sinh ra, sau đó Publish snapshot.
5. Phát đúng ZIP starter V12 cùng contract này cho sinh viên.

## Quy tắc engine

- Mỗi testcase cố định chỉ được chọn một lần.
- ID trong `skills_matrix.json` luôn giữ nguyên ID của engine V9.
- Không được trộn `TODO_USER_V12` với `COMMON_V1` trong cùng một đề.
- Thay đổi expected chỉ thay mô tả rubric; assertion thật nằm trong engine V9 cố định.
- Test bị skip không được tính là pass.
