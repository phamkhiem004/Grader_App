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
      _repository.add(fullName: fullName, email: email);
      _nameController.clear();
      _emailController.clear();
    });
  }

  void _confirmDelete(User user) {
    setState(() => _repository.delete(user.id));
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
        TextField(
          key: const ValueKey<String>('field.name'),
          controller: _nameController,
          decoration: const InputDecoration(labelText: 'Full name'),
        ),
        if (_nameError != null)
          Text(
            _nameError!,
            key: const ValueKey<String>('validation.name'),
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
            key: const ValueKey<String>('validation.email'),
            style: const TextStyle(color: Colors.red),
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

  Widget _buildList() {
    final List<User> users = _repository.users;
    return ListView(
      key: const ValueKey<String>('list.items'),
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
          'Users',
          key: ValueKey<String>('text.title'),
        ),
      ),
      // Máy tính bảng nằm ngang đủ rộng thì tách 2 cột để không dồn dọc gây tràn.
      body: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          final Widget form = Padding(
            padding: const EdgeInsets.all(16),
            child: SingleChildScrollView(child: _buildForm()),
          );
          // Bố cục máy tính bảng dùng bề rộng cố định nên tràn khung ở landscape.
          if (constraints.maxWidth >= 1000) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SizedBox(width: 900, child: form),
                SizedBox(width: 900, child: _buildList()),
              ],
            );
          }
          if (constraints.maxWidth >= 700) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Expanded(child: form),
                Expanded(child: _buildList()),
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
