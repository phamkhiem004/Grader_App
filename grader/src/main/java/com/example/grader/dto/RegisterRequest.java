package com.example.grader.dto;

/** Body đăng ký tài khoản giáo viên. */
public record RegisterRequest(String fullName, String email, String password) {}
