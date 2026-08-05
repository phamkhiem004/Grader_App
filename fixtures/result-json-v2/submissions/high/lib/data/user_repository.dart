import '../models/user.dart';

/// Kho dữ liệu trong bộ nhớ. Mỗi lần mở app tạo một instance mới nên trạng thái
/// luôn sạch — engine chấm gọi main() lại cho từng testcase.
class UserRepository {
  // Id bắt đầu từ 5 để semantic key là item.5/item.6 — engine chung có sẵn
  // fallback theo chỉ số cho item.1..item.3, làm mọi phép kiểm "đã biến mất"
  // không còn đúng. Xem fixtures/result-json-v2/README.md.
  final List<User> _users = <User>[
    const User(id: 5, fullName: 'Tran Thi Binh', email: 'binhtt@fpt.edu.vn'),
    const User(id: 6, fullName: 'Le Van Cuong', email: 'cuonglv@fpt.edu.vn'),
  ];

  int _nextId = 7;

  List<User> get users => List<User>.unmodifiable(_users);

  User add({required String fullName, required String email}) {
    final user = User(id: _nextId++, fullName: fullName, email: email);
    _users.add(user);
    return user;
  }

  void delete(int id) => _users.removeWhere((user) => user.id == id);
}
