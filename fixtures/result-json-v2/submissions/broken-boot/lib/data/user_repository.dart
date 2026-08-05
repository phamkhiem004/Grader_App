import '../models/user.dart';

/// Kho dữ liệu trong bộ nhớ. Mỗi lần mở app tạo một instance mới nên trạng thái
/// luôn sạch — engine chấm gọi main() lại cho từng testcase.
class UserRepository {
  // Id bắt đầu từ 1 — dạng tự nhiên nhất, và cố ý CHẠM vào khoá item.1/item.2 mà
  // engine chung từng có fallback theo chỉ số. Fallback đó đã bị bỏ khỏi phép kiểm
  // "đã biến mất" ở P3b, nên bộ này phải kiểm được chuyện đó không tái diễn.
  final List<User> _users = <User>[
    const User(id: 1, fullName: 'Tran Thi Binh', email: 'binhtt@fpt.edu.vn'),
    const User(id: 2, fullName: 'Le Van Cuong', email: 'cuonglv@fpt.edu.vn'),
  ];

  int _nextId = 3;

  List<User> get users => List<User>.unmodifiable(_users);

  User add({required String fullName, required String email}) {
    final user = User(id: _nextId++, fullName: fullName, email: email);
    _users.add(user);
    return user;
  }

  void delete(int id) => _users.removeWhere((user) => user.id == id);
}
