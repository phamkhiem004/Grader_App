"use client";

import React, { useEffect } from "react";
import { useRouter } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { useAuth } from "@/components/auth/AuthProvider";
import { Mail, Shield, CalendarDays, Settings, UploadCloud, CheckCircle2, LogOut } from "lucide-react";

function initialsOf(name?: string): string {
  if (!name) return "GV";
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].charAt(0).toUpperCase();
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
}

export default function ProfilePage() {
  const { teacher, refresh, logout } = useAuth();
  const router = useRouter();

  // Cập nhật lại số liệu chấm mỗi khi mở trang
  useEffect(() => { refresh(); }, [refresh]);

  const handleLogout = async () => {
    await logout();
    router.replace("/login");
  };

  const joined = teacher?.createdAt
    ? new Date(teacher.createdAt).toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" })
    : "—";

  return (
    <SidebarLayout title="Giáo viên" subtitle="Thông tin tài khoản đang đăng nhập" activePath="/profile">
      <div className="mx-auto max-w-4xl space-y-6">

        {/* Thẻ hồ sơ */}
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
          <div className="h-24 bg-gradient-to-r from-indigo-500 to-blue-600" />
          <div className="px-6 pb-6">
            <div className="-mt-10 flex flex-col items-start gap-4 sm:flex-row sm:items-end sm:justify-between">
              <div className="flex items-end gap-4">
                <div className="flex h-20 w-20 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-500 to-blue-600 text-2xl font-bold text-white shadow-lg ring-4 ring-white">
                  {initialsOf(teacher?.fullName)}
                </div>
                <div className="pb-1">
                  <h2 className="text-xl font-bold text-slate-900">{teacher?.fullName}</h2>
                  <span className="mt-1 inline-flex items-center gap-1.5 rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-semibold text-indigo-600">
                    <Shield size={12} /> {teacher?.role || "TEACHER"}
                  </span>
                </div>
              </div>
              <button
                onClick={handleLogout}
                className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-600 shadow-sm transition-all hover:border-rose-200 hover:text-rose-600 active:scale-95"
              >
                <LogOut size={16} /> Đăng xuất
              </button>
            </div>

            {/* Thông tin liên hệ */}
            <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
              <InfoRow icon={Mail} label="Email" value={teacher?.email || "—"} />
              <InfoRow icon={CalendarDays} label="Tham gia từ" value={joined} />
            </div>
          </div>
        </div>

        {/* Thống kê hoạt động chấm */}
        <div>
          <h3 className="mb-3 px-1 text-sm font-bold uppercase tracking-wider text-slate-500">Hoạt động chấm bài</h3>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <StatCard icon={Settings}     tone="indigo"  label="Đề đã cấu hình"   value={teacher?.examsCreated ?? 0} />
            <StatCard icon={UploadCloud}  tone="blue"     label="Lượt chấm hàng loạt" value={teacher?.batchesCreated ?? 0} />
            <StatCard icon={CheckCircle2} tone="emerald"  label="Bài đã chấm xong"  value={teacher?.submissionsGraded ?? 0} />
          </div>
        </div>
      </div>
    </SidebarLayout>
  );
}

function InfoRow({ icon: Icon, label, value }: { icon: React.ElementType; label: string; value: string }) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-slate-100 bg-slate-50/60 p-3.5">
      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-white text-slate-400 shadow-sm">
        <Icon size={16} />
      </div>
      <div className="min-w-0">
        <p className="text-[11px] font-bold uppercase tracking-wider text-slate-400">{label}</p>
        <p className="truncate text-sm font-semibold text-slate-700">{value}</p>
      </div>
    </div>
  );
}

function StatCard({ icon: Icon, tone, label, value }: {
  icon: React.ElementType; tone: string; label: string; value: number;
}) {
  const tones: Record<string, string> = {
    indigo: "bg-indigo-50 text-indigo-600",
    blue: "bg-blue-50 text-blue-600",
    emerald: "bg-emerald-50 text-emerald-600",
  };
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm transition-shadow hover:shadow-md">
      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
        <span className={`flex h-9 w-9 items-center justify-center rounded-lg ${tones[tone] || tones.indigo}`}>
          <Icon size={18} />
        </span>
      </div>
      <p className="text-3xl font-bold text-slate-800">{value}</p>
    </div>
  );
}
