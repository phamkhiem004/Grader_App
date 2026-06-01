// frontend/lib/config.js
// Cấu hình tập trung — KHÔNG hardcode URL/giá trị trong từng trang nữa.
// Ghi đè bằng biến môi trường NEXT_PUBLIC_* (xem .env.example).

export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE || "http://localhost:8080/api";

export const DEFAULT_EXAM_ID =
  process.env.NEXT_PUBLIC_DEFAULT_EXAM_ID || "FLUTTER_PE_01";

// Ngưỡng điểm đạt (Pass) — dùng chung cho bảng kết quả & thống kê
export const PASS_THRESHOLD = Number(
  process.env.NEXT_PUBLIC_PASS_THRESHOLD || 5
);
