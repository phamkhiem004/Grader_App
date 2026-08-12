import 'package:flutter/material.dart';

import '../models/user.dart';

class UserDetailScreen extends StatelessWidget {
  const UserDetailScreen({super.key, required this.user});

  final User user;

  @override
  Widget build(BuildContext context) {
    // ⛔ LỖI CẤY CỐ Ý CỦA FIXTURE — đây là bài nộp DUY NHẤT ép engine chạm trần thời gian.
    //
    // Vì sao cần: `observation.kind = PROCESS_TIMEOUT` chỉ được phép công bố trong SPEC 5.5 khi
    // nó ĐÃ XUẤT HIỆN THẬT trên ít nhất một bài chấm (cổng `fixtureEmitsEveryObservationKind`).
    // Không có bài này thì nhãn đó là lời khai suông.
    //
    // Vì sao đặt ở MÀN CHI TIẾT chứ không ở `main()`: treo ngay lúc khởi động thì mọi testcase
    // đều chết, chẳng chứng minh được gì. Treo ở đây thì các testcase trước `TC_DETAIL_NAV`
    // trong cùng lô vẫn chạy xong — nhờ vậy bài này đo được luôn cách engine QUY TỘI
    // (`missing.first` phải trỏ đúng `TC_DETAIL_NAV`, không đổ oan sang testcase khác).
    //
    // Vòng lặp dùng biến để trình biên dịch không tối ưu mất; `n >= 0` luôn đúng nên không thoát.
    var n = 0;
    while (n >= 0) {
      n = (n + 1) % 1000000;
    }
    return Scaffold(
      key: const ValueKey<String>('screen.detail'),
      // Nút quay lại đặt TRÊN AppBar — chỗ tự nhiên của nó, và cố ý CHẠM vào lỗi
      // `_settle` không đẩy đồng hồ ảo: hoạt ảnh chuyển cảnh không chạy xong thì lớp
      // phủ chắn con trỏ, thao tác chạm của bộ chấm trượt. Đã sửa ở P3b.
      appBar: AppBar(
        title: const Text('User Detail'),
        leading: IconButton(
          key: const ValueKey<String>('action.back'),
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(user.fullName, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(user.email),
          ],
        ),
      ),
    );
  }
}
