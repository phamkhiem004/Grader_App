class User {
  const User({required this.id, required this.fullName, required this.email});

  final int id;
  final String fullName;
  final String email;

  User copyWith({int? id, String? fullName, String? email}) => User(
        id: id ?? this.id,
        fullName: fullName ?? this.fullName,
        email: email ?? this.email,
      );
}
