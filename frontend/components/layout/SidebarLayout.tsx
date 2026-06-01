import React from 'react';
import Link from 'next/link';
import { Settings, FileText, CheckSquare, BarChart2, Users, LogOut, Bell, Search, GraduationCap } from 'lucide-react';
import { clsx } from 'clsx';

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

        <div className="shrink-0 border-t border-white/5 p-4">
          <button className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-slate-400 transition-colors hover:bg-slate-800/70 hover:text-white">
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
            <div className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-blue-600 text-sm font-semibold text-white shadow-sm ring-2 ring-white">
              GV
            </div>
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
