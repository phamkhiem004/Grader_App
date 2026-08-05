import 'package:flutter/material.dart';

import '../models/user.dart';

class UserDetailScreen extends StatelessWidget {
  const UserDetailScreen({super.key, required this.user});

  final User user;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: const ValueKey<String>('screen.detail'),
      appBar: AppBar(title: const Text('User Detail')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(user.fullName, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(user.email),
            const SizedBox(height: 24),
            // Nút quay lại đặt trong body: AppBar bị lớp phủ chuyển cảnh chắn
            // con trỏ nên thao tác chạm của bộ chấm không tới nơi.
            ElevatedButton(
              key: const ValueKey<String>('action.back'),
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Back'),
            ),
          ],
        ),
      ),
    );
  }
}
