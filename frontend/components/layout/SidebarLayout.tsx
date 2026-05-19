import React from 'react';
import Link from 'next/link';
import { Home, Settings, FileText, CheckSquare, BarChart2, Users, LogOut, Bell, Search } from 'lucide-react';
import { clsx } from 'clsx';

interface SidebarLayoutProps {
  children: React.ReactNode;
  activePath?: string;
  title: string;
}

export default function SidebarLayout({ children, activePath = '/', title }: SidebarLayoutProps) {
  const navItems = [
    { name: 'Chấm bài (Batch)', path: '/', icon: CheckSquare },
    { name: 'Cấu hình Đề thi', path: '/teacher', icon: Settings },
    { name: 'Không gian chấm', path: '/teacher/workspace', icon: FileText },
    { name: 'Thống kê', path: '/statistics', icon: BarChart2 },
    { name: 'Sinh viên', path: '#', icon: Users },
  ];

  return (
    <div className="flex h-screen w-full bg-slate-50 text-slate-800 font-sans overflow-hidden">
      {/* Sidebar */}
      <aside className="w-64 bg-[#0f172a] text-slate-300 flex flex-col shadow-xl z-20 shrink-0">
        <div className="h-16 flex items-center px-6 border-b border-slate-800/50 shrink-0">
          <div className="w-8 h-8 rounded bg-blue-600 flex items-center justify-center mr-3 shadow-lg shadow-blue-500/20">
            <span className="text-white font-bold text-lg leading-none">G</span>
          </div>
          <span className="text-white font-bold text-lg tracking-wide">Grader</span>
        </div>
        
        <div className="flex-1 py-6 px-3 overflow-y-auto custom-scrollbar">
          <div className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3 px-3">Quản lý chấm thi</div>
          <nav className="space-y-1">
            {navItems.slice(0, 3).map((item) => {
              const isActive = activePath === item.path;
              return (
                <Link key={item.name} href={item.path}
                  className={clsx(
                    "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                    isActive 
                      ? "bg-blue-600/10 text-blue-400" 
                      : "hover:bg-slate-800 hover:text-white"
                  )}
                >
                  <item.icon size={18} className={isActive ? "text-blue-400" : "text-slate-400"} />
                  {item.name}
                </Link>
              );
            })}
          </nav>

          <div className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3 px-3 mt-8">Báo cáo & Dữ liệu</div>
          <nav className="space-y-1">
            {navItems.slice(3).map((item) => (
              <Link key={item.name} href={item.path}
                className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium hover:bg-slate-800 hover:text-white transition-colors"
              >
                <item.icon size={18} className="text-slate-400" />
                {item.name}
              </Link>
            ))}
          </nav>
        </div>

        <div className="p-4 border-t border-slate-800/50 shrink-0">
          <button className="flex items-center gap-3 px-3 py-2.5 w-full rounded-lg text-sm font-medium text-slate-400 hover:bg-slate-800 hover:text-white transition-colors">
            <LogOut size={18} />
            Đăng xuất
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <div className="flex-1 flex flex-col overflow-hidden relative">
        {/* Top Navbar */}
        <header className="h-16 bg-white border-b border-slate-200 flex items-center justify-between px-8 shrink-0 z-10 shadow-sm">
          <h1 className="text-lg font-bold text-slate-800">{title}</h1>
          <div className="flex items-center gap-6">
            <div className="relative hidden md:block">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input type="text" placeholder="Tìm kiếm mã đề, sinh viên..." className="pl-9 pr-4 py-2 bg-slate-100 border-transparent focus:bg-white focus:border-blue-500 focus:ring-2 focus:ring-blue-200 rounded-full text-sm w-64 transition-all outline-none" />
            </div>
            <button className="relative text-slate-500 hover:text-slate-700 transition-colors">
              <Bell size={20} />
              <span className="absolute top-0 right-0 w-2 h-2 bg-rose-500 rounded-full border border-white"></span>
            </button>
            <div className="h-8 w-8 rounded-full bg-indigo-100 flex items-center justify-center border border-indigo-200 shadow-sm overflow-hidden cursor-pointer">
              <span className="text-indigo-700 font-semibold text-sm">GV</span>
            </div>
          </div>
        </header>

        {/* Page Content */}
        <main className="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 p-8">
          <div className="max-w-6xl mx-auto">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}
