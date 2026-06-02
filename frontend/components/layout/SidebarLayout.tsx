"use client";

import React, { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Settings, FileText, CheckSquare, BarChart2, LogOut, Bell, Search, GraduationCap, UserCircle, Loader2 } from 'lucide-react';
import { clsx } from 'clsx';
import { useAuth } from '@/components/auth/AuthProvider';

interface SidebarLayoutProps {
  children: React.ReactNode;
  activePath?: string;
  title: string;
  subtitle?: string;
}

const PRIMARY_NAV = [
  { name: 'Chấm bài (Batch)', path: '/', icon: CheckSquare },
  { name: 'Cấu hình Đề thi', path: '/teacher', icon: Settings },
  { name: 'Không gian chấm', path: '/teacher/workspace', icon: FileText },
];

const SECONDARY_NAV = [
  { name: 'Thống kê', path: '/statistics', icon: BarChart2 },
  { name: 'Giáo viên', path: '/profile', icon: UserCircle },
];

/** Chữ cái đầu của tên để làm avatar. */
function initialsOf(name?: string): string {
  if (!name) return "GV";
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].charAt(0).toUpperCase();
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
}

export default function SidebarLayout({ children, activePath = '/', title, subtitle }: SidebarLayoutProps) {
  const { teacher, loading, logout } = useAuth();
  const router = useRouter();

  // Bảo vệ route: chưa đăng nhập → về /login
  useEffect(() => {
    if (!loading && !teacher) router.replace("/login");
  }, [loading, teacher, router]);

  const handleLogout = async () => {
    await logout();
    router.replace("/login");
  };

  // Đang kiểm tra phiên hoặc chuẩn bị chuyển hướng → màn chờ (tránh nháy nội dung)
  if (loading || !teacher) {
    return (
      <div className="flex h-screen w-full items-center justify-center bg-[#0b1120]">
        <Loader2 size={28} className="animate-spin text-indigo-400" />
      </div>
    );
  }

  const renderLink = (item: { name: string; path: string; icon: React.ElementType }) => {
    const isActive = activePath === item.path;
    return (
      <Link
        key={item.name}
        href={item.path}
        className={clsx(
          'group relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all',
          isActive
            ? 'bg-indigo-500/10 text-white'
            : 'text-slate-400 hover:bg-slate-800/70 hover:text-white'
        )}
      >
        {isActive && (
          <span className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-indigo-400" />
        )}
        <item.icon
          size={18}
          className={clsx('shrink-0 transition-colors', isActive ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-300')}
        />
        {item.name}
      </Link>
    );
  };

  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-50 font-sans text-slate-800">
      {/* Sidebar */}
      <aside className="z-20 flex w-64 shrink-0 flex-col bg-[#0b1120] text-slate-300 shadow-xl">
        {/* Brand */}
        <div className="flex h-16 shrink-0 items-center gap-3 border-b border-white/5 px-5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 shadow-lg shadow-indigo-600/30 ring-1 ring-white/10">
            <GraduationCap size={20} className="text-white" />
          </div>
          <div className="leading-tight">
            <div className="text-[15px] font-bold tracking-wide text-white">Grader</div>
            <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-slate-500">Auto-grading</div>
          </div>
        </div>

        <div className="custom-scrollbar flex-1 overflow-y-auto px-3 py-6">
          <div className="mb-3 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-600">Quản lý chấm thi</div>
          <nav className="space-y-1">{PRIMARY_NAV.map(renderLink)}</nav>

          <div className="mb-3 mt-8 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-600">Báo cáo & Dữ liệu</div>
          <nav className="space-y-1">{SECONDARY_NAV.map(renderLink)}</nav>
        </div>

        {/* GV đang đăng nhập + đăng xuất */}
        <div className="shrink-0 border-t border-white/5 p-3">
          <Link href="/profile" className="mb-1 flex items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-slate-800/70">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-blue-600 text-xs font-bold text-white ring-1 ring-white/10">
              {initialsOf(teacher.fullName)}
            </div>
            <div className="min-w-0 leading-tight">
              <div className="truncate text-sm font-semibold text-white">{teacher.fullName}</div>
              <div className="truncate text-[11px] text-slate-500">{teacher.email}</div>
            </div>
          </Link>
          <button
            onClick={handleLogout}
            className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-slate-400 transition-colors hover:bg-slate-800/70 hover:text-white"
          >
            <LogOut size={18} />
            Đăng xuất
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <div className="relative flex flex-1 flex-col overflow-hidden">
        {/* Top Navbar */}
        <header className="z-10 flex h-16 shrink-0 items-center justify-between border-b border-slate-200 bg-white/90 px-8 backdrop-blur-sm">
          <div className="min-w-0">
            <h1 className="truncate text-lg font-bold text-slate-900">{title}</h1>
            {subtitle && <p className="truncate text-xs text-slate-500">{subtitle}</p>}
          </div>
          <div className="flex items-center gap-5">
            <div className="relative hidden md:block">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="Tìm mã đề, sinh viên..."
                className="w-64 rounded-full border border-transparent bg-slate-100 py-2 pl-9 pr-4 text-sm outline-none transition-all focus:border-indigo-400 focus:bg-white focus:ring-2 focus:ring-indigo-100"
              />
            </div>
            <button className="relative text-slate-500 transition-colors hover:text-slate-800">
              <Bell size={20} />
              <span className="absolute right-0 top-0 h-2 w-2 rounded-full border-2 border-white bg-rose-500" />
            </button>
            <Link href="/profile" className="flex items-center gap-2.5">
              <div className="hidden text-right leading-tight sm:block">
                <div className="text-sm font-semibold text-slate-800">{teacher.fullName}</div>
                <div className="text-[11px] text-slate-500">Giáo viên</div>
              </div>
              <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-blue-600 text-sm font-semibold text-white shadow-sm ring-2 ring-white">
                {initialsOf(teacher.fullName)}
              </div>
            </Link>
          </div>
        </header>

        {/* Page Content */}
        <main className="custom-scrollbar flex-1 overflow-y-auto bg-slate-50 p-8">
          <div className="mx-auto max-w-6xl animate-fade-in-up">{children}</div>
        </main>
      </div>
    </div>
  );
}
