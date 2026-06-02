"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { getToken, setToken, apiLogin, apiRegister, apiMe, apiLogout, apiUpdateProfile, apiChangePassword } from "@/lib/auth";

export interface Teacher {
  id: number;
  fullName: string;
  email: string;
  role: string;
  createdAt: string;
  examsCreated: number;
  batchesCreated: number;
  submissionsGraded: number;
}

interface AuthContextValue {
  teacher: Teacher | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (fullName: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  updateName: (fullName: string) => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [teacher, setTeacher] = useState<Teacher | null>(null);
  const [loading, setLoading] = useState(true);

  // Khôi phục phiên khi tải app: dùng token để hỏi /me
  useEffect(() => {
    const token = getToken();
    if (!token) { setLoading(false); return; }
    apiMe(token)
      .then((t: Teacher) => setTeacher(t))
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const data = await apiLogin(email, password);
    // Đăng nhập mới = phiên mới → bỏ phiên chấm còn sót để KHÔNG hiện kết quả lần trước
    try { localStorage.removeItem("grader_active_batch"); } catch { /* bỏ qua */ }
    setToken(data.token);
    setTeacher(data.teacher);
  }, []);

  const register = useCallback(async (fullName: string, email: string, password: string) => {
    const data = await apiRegister(fullName, email, password);
    try { localStorage.removeItem("grader_active_batch"); } catch { /* bỏ qua */ }
    setToken(data.token);
    setTeacher(data.teacher);
  }, []);

  const logout = useCallback(async () => {
    const token = getToken();
    setTeacher(null);
    setToken(null);
    // Xóa phiên chấm bài đã lưu → đăng nhập lại KHÔNG còn dính kết quả của người trước
    try { localStorage.removeItem("grader_active_batch"); } catch { /* bỏ qua */ }
    await apiLogout(token);
  }, []);

  const refresh = useCallback(async () => {
    const token = getToken();
    if (!token) return;
    try { setTeacher(await apiMe(token)); } catch { /* giữ nguyên */ }
  }, []);

  const updateName = useCallback(async (fullName: string) => {
    const token = getToken();
    if (!token) throw new Error("Chưa đăng nhập");
    const profile = await apiUpdateProfile(token, fullName);
    setTeacher(profile);
  }, []);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const token = getToken();
    if (!token) throw new Error("Chưa đăng nhập");
    await apiChangePassword(token, currentPassword, newPassword);
  }, []);

  return (
    <AuthContext.Provider value={{ teacher, loading, login, register, logout, refresh, updateName, changePassword }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth phải dùng bên trong <AuthProvider>");
  return ctx;
}
