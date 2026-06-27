"use client";

import React, { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { GraduationCap, Mail, Lock, User, Loader2, AlertCircle, UserPlus } from "lucide-react";
import ThemeToggle from "@/components/layout/ThemeToggle";

export default function RegisterPage() {
  const { teacher, loading: authLoading, register } = useAuth();
  const router = useRouter();
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!authLoading && teacher) router.replace("/");
  }, [authLoading, teacher, router]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (password.length < 6) { setError("Mật khẩu phải từ 6 ký tự trở lên"); return; }
    if (password !== confirm) { setError("Mật khẩu nhập lại không khớp"); return; }
    setSubmitting(true);
    try {
      await register(fullName.trim(), email.trim(), password);
      router.replace("/");
    } catch (err: any) {
      setError(err?.message || "Đăng ký thất bại");
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-900 via-[#0b1120] to-indigo-950 p-4">
      <div className="fixed right-4 top-4 z-50">
        <ThemeToggle />
      </div>
      <div className="w-full max-w-md">
        <div className="mb-8 flex flex-col items-center text-center">
          <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-500 to-blue-600 shadow-lg shadow-indigo-600/40 ring-1 ring-white/15">
            <GraduationCap size={28} className="text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white">Grader</h1>
          <p className="mt-1 text-sm text-slate-400">Tạo tài khoản giáo viên</p>
        </div>

        <form onSubmit={submit} className="rounded-2xl border border-white/10 bg-white p-8 shadow-2xl">
          <h2 className="mb-1 text-lg font-bold text-slate-800">Đăng ký</h2>
          <p className="mb-6 text-sm text-slate-500">Điền thông tin để tạo tài khoản chấm thi</p>

          {error && (
            <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-rose-200 bg-rose-50 p-3 text-rose-600">
              <AlertCircle size={18} className="mt-0.5 shrink-0" />
              <p className="text-sm font-medium">{error}</p>
            </div>
          )}

          <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Họ và tên</label>
          <div className="relative mb-4">
            <User size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text" required value={fullName} onChange={e => setFullName(e.target.value)}
              placeholder="Nguyễn Văn A"
              className="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-4 text-sm font-medium text-slate-800 outline-none transition-all focus:border-transparent focus:bg-white focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Email</label>
          <div className="relative mb-4">
            <Mail size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="email" required value={email} onChange={e => setEmail(e.target.value)}
              placeholder="giaovien@fpt.edu.vn"
              className="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-4 text-sm font-medium text-slate-800 outline-none transition-all focus:border-transparent focus:bg-white focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Mật khẩu</label>
              <div className="relative mb-6">
                <Lock size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="password" required value={password} onChange={e => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-3 text-sm font-medium text-slate-800 outline-none transition-all focus:border-transparent focus:bg-white focus:ring-2 focus:ring-indigo-500"
                />
              </div>
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Nhập lại</label>
              <div className="relative mb-6">
                <Lock size={16} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="password" required value={confirm} onChange={e => setConfirm(e.target.value)}
                  placeholder="••••••••"
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 py-3 pl-10 pr-3 text-sm font-medium text-slate-800 outline-none transition-all focus:border-transparent focus:bg-white focus:ring-2 focus:ring-indigo-500"
                />
              </div>
            </div>
          </div>

          <button
            type="submit"
            disabled={submitting}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-600 to-blue-600 px-4 py-3.5 font-semibold text-white shadow-sm shadow-indigo-600/30 transition-all hover:from-indigo-700 hover:to-blue-700 active:scale-[0.98] disabled:opacity-60"
          >
            {submitting ? <Loader2 size={18} className="animate-spin" /> : <UserPlus size={18} />}
            {submitting ? "Đang tạo tài khoản..." : "Đăng ký"}
          </button>

          <p className="mt-6 text-center text-sm text-slate-500">
            Đã có tài khoản?{" "}
            <Link href="/login" className="font-semibold text-indigo-600 hover:text-indigo-700">Đăng nhập</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
