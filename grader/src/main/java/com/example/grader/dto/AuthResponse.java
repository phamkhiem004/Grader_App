package com.example.grader.dto;

/** Kết quả đăng nhập/đăng ký: token phiên + hồ sơ giáo viên. */
public record AuthResponse(String token, TeacherProfile teacher) {}
