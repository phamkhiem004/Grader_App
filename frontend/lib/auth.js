// frontend/lib/auth.js
// Lớp gọi API xác thực + lưu token phiên vào localStorage.
import { API_BASE } from "./config";

const TOKEN_KEY = "grader_token";

// Lưu token ở sessionStorage: mở app/tab mới sẽ YÊU CẦU đăng nhập lại,
// nhưng F5 / chuyển trang TRONG cùng phiên vẫn giữ đăng nhập.
export function getToken() {
  if (typeof window === "undefined") return null;
  try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setToken(token) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(TOKEN_KEY);   // dọn token cũ từng lưu ở localStorage (phiên trước)
  } catch { /* ignore */ }
}

async function postJson(path, body) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

export function apiLogin(email, password) {
  return postJson("/auth/login", { email, password });
}

export function apiRegister(fullName, email, password) {
  return postJson("/auth/register", { fullName, email, password });
}

export async function apiMe(token) {
  const res = await fetch(`${API_BASE}/auth/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error("unauthorized");
  return res.json();
}

export async function apiLogout(token) {
  if (!token) return;
  try {
    await fetch(`${API_BASE}/auth/logout`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch { /* ignore */ }
}

async function postAuthed(path, token, body) {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

export function apiUpdateProfile(token, fullName) {
  return postAuthed("/auth/update-profile", token, { fullName });
}

export function apiChangePassword(token, currentPassword, newPassword) {
  return postAuthed("/auth/change-password", token, { currentPassword, newPassword });
}
