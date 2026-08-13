import 'package:flutter/material.dart';

import '../data/user_repository.dart';
import '../models/user.dart';
import 'user_detail_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final UserRepository _repository = UserRepository();
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _emailController = TextEditingController();

  String? _nameError;
  String? _emailError;
  bool _notify = true;
  int? _editingId;

  static final RegExp _emailPattern = RegExp(r'^[\w.+-]+@[\w-]+\.[\w.-]+$');

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    super.dispose();
  }

  void _save() {
    final String fullName = _nameController.text.trim();
    final String email = _emailController.text.trim();

    setState(() {
      _nameError = fullName.isEmpty ? 'Vui lòng nhập họ tên' : null;
      _emailError = _emailPattern.hasMatch(email) ? null : 'Email không hợp lệ';
    });
    if (_nameError != null || _emailError != null) return;

    setState(() {
      final int? editingId = _editingId;
      if (editingId == null) {
        _repository.add(fullName: fullName, email: email);
      } else {
        _repository.update(id: editingId, fullName: fullName, email: email);
      }
      _editingId = null;
      _nameController.clear();
      _emailController.clear();
    });
  }

  /// Nạp người dùng đang chọn vào form để sửa — nguồn của FORM_PREFILL và BUTTON_ACTION.
  void _startEdit(User user) {
    setState(() {
      _editingId = user.id;
      _nameController.text = user.fullName;
      _emailController.text = user.email;
    });
  }

  /// Xoá toàn bộ danh sách — nguồn của STATE_REACTIVE_FLOW (trạng thái rỗng phải hiện ra
  /// VÀ mục cũ phải biến mất).
  void _clearAll() {
    setState(_repository.clear);
  }

  void _confirmDelete(User user) {
    showDialog<void>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        key: const ValueKey<String>('dialog.delete'),
        title: const Text('Delete user'),
        content: Text('Xoá ${user.fullName}?'),
        actions: <Widget>[
          TextButton(
            key: const ValueKey<String>('action.delete.cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          TextButton(
            key: const ValueKey<String>('action.delete.confirm'),
            onPressed: () {
              setState(() => _repository.delete(user.id));
              Navigator.of(dialogContext).pop();
            },
            child: const Text('Delete'),
          ),
        ],
      ),
    );
  }

  void _openDetail(User user) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => UserDetailScreen(user: user)),
    );
  }

  Widget _buildForm() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Row(
          children: <Widget>[
            const Icon(
              Icons.people_outline,
              key: ValueKey<String>('icon.header'),
              semanticLabel: 'Danh sách người dùng',
            ),
            const SizedBox(width: 8),
            Container(
              key: const ValueKey<String>('box.avatar'),
              width: 48,
              height: 48,
              decoration: const BoxDecoration(
                color: Color(0xFFE0E0E0),
                shape: BoxShape.circle,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        if (_editingId != null)
          const Text(
            'Đang sửa người dùng',
            key: ValueKey<String>('message.editing'),
          ),
        // Khoảng cách giữa hai ô nhập phải đúng 8 — WIDGET_GAP đo chỗ này, nên đừng
        // chèn thêm widget nào vào giữa.
        TextField(
          key: const ValueKey<String>('field.name'),
          controller: _nameController,
          decoration: const InputDecoration(labelText: 'Full name'),
        ),
        if (_nameError != null)
          Text(
            _nameError!,
            key: const ValueKey<String>('error.name'),
            style: const TextStyle(color: Colors.red),
          ),
        const SizedBox(height: 8),
        TextField(
          key: const ValueKey<String>('field.email'),
          controller: _emailController,
          decoration: const InputDecoration(labelText: 'Email'),
        ),
        if (_emailError != null)
          Text(
            _emailError!,
            key: const ValueKey<String>('error.email'),
            style: const TextStyle(color: Colors.red),
          ),
        const SizedBox(height: 12),
        Row(
          children: <Widget>[
            Checkbox(
              key: const ValueKey<String>('field.notify'),
              value: _notify,
              onChanged: (bool? value) => setState(() => _notify = value ?? false),
            ),
            const Expanded(child: Text('Nhận thông báo khi thêm người dùng')),
          ],
        ),
        const SizedBox(height: 12),
        ElevatedButton(
          key: const ValueKey<String>('action.save'),
          onPressed: _save,
          child: const Text('Add User'),
        ),
      ],
    );
  }

  Widget _buildList({bool twoColumns = false}) {
    final List<User> users = _repository.users;
    if (users.isEmpty) {
      return const Center(
        child: Text('Chưa có người dùng', key: ValueKey<String>('state.empty')),
      );
    }
    return GridView.count(
      key: const ValueKey<String>('list.items'),
      crossAxisCount: twoColumns ? 2 : 1,
      childAspectRatio: twoColumns ? 2.6 : 4.5,
      children: <Widget>[
        for (final User user in users)
          ListTile(
            key: ValueKey<String>('item.${user.id}'),
            title: Text(user.fullName),
            subtitle: Text(user.email),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                IconButton(
                  key: ValueKey<String>('action.edit.${user.id}'),
                  icon: const Icon(Icons.edit_outlined),
                  onPressed: () => _startEdit(user),
                ),
                IconButton(
                  key: ValueKey<String>('action.detail.${user.id}'),
                  icon: const Icon(Icons.info_outline),
                  onPressed: () => _openDetail(user),
                ),
                IconButton(
                  key: ValueKey<String>('action.delete.${user.id}'),
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => _confirmDelete(user),
                ),
              ],
            ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: const ValueKey<String>('screen.home'),
      appBar: AppBar(
        title: const Text(
          'User Manager',
          key: ValueKey<String>('text.title'),
          // Cỡ và độ đậm khai TƯỜNG MINH trên Text: WIDGET_TEXT_STYLE hợp nhất style của
          // Text lên DefaultTextStyle, để mặc định theo theme thì phép đo phụ thuộc theme.
          style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        actions: <Widget>[
          IconButton(
            key: const ValueKey<String>('action.clear-all'),
            icon: const Icon(Icons.delete_sweep_outlined),
            onPressed: _clearAll,
          ),
        ],
      ),
      // Máy tính bảng nằm ngang đủ rộng thì tách 2 cột để không dồn dọc gây tràn.
      body: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          final Widget form = Padding(
            key: const ValueKey<String>('padding.form'),
            padding: const EdgeInsets.all(16),
            child: SingleChildScrollView(child: _buildForm()),
          );
          if (constraints.maxWidth >= 700) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Expanded(child: form),
                Expanded(child: _buildList(twoColumns: true)),
              ],
            );
          }
          return Column(
            children: <Widget>[
              Flexible(child: form),
              Expanded(child: _buildList()),
            ],
          );
        },
      ),
    );
  }
}
