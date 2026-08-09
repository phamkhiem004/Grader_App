import 'package:flutter/material.dart';

import 'screens/home_screen.dart';

void main() {
  runApp(const UserManagerApp());
}

class UserManagerApp extends StatelessWidget {
  const UserManagerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'User Manager',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const HomeScreen(),
    );
  }
}
