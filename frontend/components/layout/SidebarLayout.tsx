import React from 'react';
import Link from 'next/link';
import { Settings, FileText, CheckSquare, BarChart2, Users, LogOut, Bell, Search, GraduationCap } from 'lucide-react';
import { clsx } from 'clsx';
import ThemeToggle from './ThemeToggle';

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
  { name: 'Sinh viên', path: '#', icon: Users },
];

export default function SidebarLayout({ children, activePath = '/', title, subtitle }: SidebarLayoutProps) {
  const renderLink = (item: { name: string; path: string; icon: React.ElementType }) => {
    const isActive = activePath === item.path;
    return (
      <Link
        key={item.name}
        href={item.path}
        className={clsx(
          'group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200',
          isActive
            ? 'bg-gradient-to-r from-indigo-500/25 via-indigo-500/10 to-transparent text-white shadow-sm'
            : 'text-slate-400 hover:bg-white/[0.04] hover:text-white'
        )}
      >
        {isActive && (
          <span className="absolute left-0 top-1/2 h-6 w-1 -translate-y-1/2 rounded-r-full bg-gradient-to-b from-indigo-400 to-blue-500 shadow-[0_0_12px_rgba(99,102,241,0.7)]" />
        )}
        <item.icon
          size={18}
          className={clsx('shrink-0 transition-colors', isActive ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-200')}
        />
        {item.name}
      </Link>
    );
  };

  return (
    <div className="flex h-screen w-full overflow-hidden font-sans text-slate-800">
      {/* Sidebar */}
      <aside className="z-20 flex w-64 shrink-0 flex-col bg-gradient-to-b from-[#0c1322] to-[#0a0f1c] text-slate-300 shadow-xl ring-1 ring-white/5">
        {/* Brand */}
        <div className="flex h-16 shrink-0 items-center gap-3 border-b border-white/[0.06] px-5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 shadow-lg shadow-indigo-600/40 ring-1 ring-white/15">
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

        <div className="shrink-0 border-t border-white/[0.06] p-4">
          <button className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-slate-400 transition-colors hover:bg-white/[0.04] hover:text-white">
            <LogOut size={18} />
            Đăng xuất
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <div className="relative flex flex-1 flex-col overflow-hidden">
        {/* Top Navbar */}
        <header className="z-10 flex h-16 shrink-0 items-center justify-between border-b border-slate-200/80 bg-white/80 px-8 shadow-[0_1px_2px_rgba(15,23,42,0.04)] backdrop-blur-md dark:border-slate-800/80 dark:bg-slate-900/70">
          <div className="min-w-0">
            <h1 className="truncate text-lg font-bold tracking-tight text-slate-900">{title}</h1>
            {subtitle && <p className="truncate text-xs text-slate-500">{subtitle}</p>}
          </div>
          <div className="flex items-center gap-4">
            <div className="relative hidden md:block">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder="Tìm mã đề, sinh viên..."
                className="w-64 rounded-full border border-slate-200 bg-slate-100/70 py-2 pl-9 pr-4 text-sm outline-none transition-all focus:border-indigo-400 focus:bg-white focus:ring-2 focus:ring-indigo-100 dark:border-slate-700 dark:bg-slate-800/70 dark:text-slate-200 dark:focus:bg-slate-800"
              />
            </div>
            <ThemeToggle />
            <button className="relative text-slate-500 transition-colors hover:text-slate-800 dark:hover:text-slate-200">
              <Bell size={20} />
              <span className="absolute right-0 top-0 h-2 w-2 rounded-full border-2 border-white bg-rose-500 dark:border-slate-900" />
            </button>
            <div className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-blue-600 text-sm font-semibold text-white shadow-sm ring-2 ring-white dark:ring-slate-900">
              GV
            </div>
          </div>
        </header>

        {/* Page Content */}
        <main className="app-canvas custom-scrollbar flex-1 overflow-y-auto p-8">
          <div className="mx-auto max-w-6xl animate-fade-in-up">{children}</div>
        </main>
      </div>
    </div>
  );
}
