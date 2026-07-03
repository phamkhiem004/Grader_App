package com.example.grader.config;

import com.example.grader.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bảo vệ tối thiểu cho API: yêu cầu TOKEN hợp lệ với mọi route dưới {@code /api/**}
 * (trừ {@code /api/auth/*}). Frontend tự gắn Bearer token cho cả GET và POST.
 *
 * Token lấy từ header {@code Authorization: Bearer <token>}. Sai/thiếu → 401 JSON.
 * (Đây là lớp chặn tấn công ẩn danh; chưa phải phân quyền RBAC đầy đủ.)
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    @Autowired
    private AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path   = req.getRequestURI();
        String method = req.getMethod();

        boolean isApi       = path.startsWith("/api/");
        boolean isAuth      = path.startsWith("/api/auth/");
        boolean isPreflight = "OPTIONS".equalsIgnoreCase(method);
        boolean needsAuth = isApi && !isAuth && !isPreflight;
        if (!needsAuth) { chain.doFilter(req, res); return; }

        String h = req.getHeader("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
        try {
            var teacher = authService.authenticate(token);    // ném nếu thiếu/sai/hết hạn
            req.setAttribute("teacherEmail", teacher.getEmail());
            req.setAttribute("teacherRole", teacher.getRole());
            chain.doFilter(req, res);
        } catch (Exception e) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.setHeader("Access-Control-Allow-Origin", "*");   // để trình duyệt đọc được body 401
            res.getWriter().write("{\"error\":\"Cần đăng nhập để thực hiện thao tác này.\"}");
        }
    }
}
