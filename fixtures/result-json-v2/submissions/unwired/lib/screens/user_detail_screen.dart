import 'package:flutter/material.dart';

import '../models/user.dart';

class UserDetailScreen extends StatelessWidget {
  const UserDetailScreen({super.key, required this.user});

  final User user;

  @override
  Widget build(BuildContext context) {
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
