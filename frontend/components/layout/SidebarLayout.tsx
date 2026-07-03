"use client";

import React, { useEffect, useState, useRef, useCallback } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  Settings, FileText, CheckSquare, BarChart2, LogOut, Bell, Search,
  GraduationCap, UserCircle, Loader2, History, PanelLeftClose,
  Clock, CheckCircle2, AlertCircle, BookOpen, Package, Archive,
} from 'lucide-react';
import { clsx } from 'clsx';
import { useAuth } from '@/components/auth/AuthProvider';
import { API_BASE } from '@/lib/config';
import ThemeToggle from '@/components/layout/ThemeToggle';

interface SidebarLayoutProps {
  children: React.ReactNode;
  activePath?: string;
  title: string;
  subtitle?: string;
}

interface BatchNotif {
  batchId: string;
  examId: string;
  status: string;
  totalFiles: number;
  doneCount: number;
  errorCount: number;
  createdAt: string;
}
interface SearchRow {
  examId: string;
  studentId: string;
  studentName: string | null;
  score: number | null;
  status: string;
}

const PRIMARY_NAV = [
  { name: 'Chấm bài (Batch)', path: '/', icon: CheckSquare },
  { name: 'Cấu hình Đề thi', path: '/teacher', icon: Settings },
  { name: 'Kho đề thi', path: '/teacher/archive', icon: Archive },
  { name: 'Khung năng lực', path: '/syllabus', icon: BookOpen },
  { name: 'Thư viện chấm', path: '/teacher/libraries', icon: Package },
  { name: 'Không gian chấm', path: '/teacher/workspace', icon: FileText },
];

const SECONDARY_NAV = [
  { name: 'Lịch sử chấm', path: '/history', icon: History },
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

/** Diễn giải trạng thái 1 phiên chấm cho thông báo. */
function notifStatus(n: BatchNotif) {
  const done = n.doneCount || 0, err = n.errorCount || 0, total = n.totalFiles || 0;
  if (n.status === 'IN_PROGRESS') return { Icon: Clock, tone: 'text-blue-500', text: `Đang chấm ${done + err}/${total}` };
  if (n.status === 'COMPLETED') return { Icon: CheckCircle2, tone: 'text-emerald-500', text: `Hoàn tất ${done}/${total} bài` };
  if (n.status === 'PARTIAL') return { Icon: AlertCircle, tone: 'text-amber-500', text: `Xong ${done}/${total}, ${err} lỗi` };
  return { Icon: AlertCircle, tone: 'text-slate-400', text: `${done + err}/${total}` };
}

export default function SidebarLayout({ children, activePath = '/', title, subtitle }: SidebarLayoutProps) {
  const { teacher, loading, logout } = useAuth();
  const router = useRouter();

  // ── UI state ──────────────────────────────────────────────────
  // Đọc trạng thái thu gọn NGAY khi khởi tạo (tránh render mở rồi mới đóng = giật khi chuyển trang)
  const [collapsed, setCollapsed] = useState(() => {
    if (typeof window === "undefined") return false;
    try { return localStorage.getItem("sidebar_collapsed") === "1"; } catch { return false; }
  });
  const [notifOpen, setNotifOpen] = useState(false);
  const [notifs, setNotifs] = useState<BatchNotif[]>([]);
  const [lastSeen, setLastSeen] = useState(0);
  const [q, setQ] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [results, setResults] = useState<SearchRow[]>([]);
  const [searching, setSearching] = useState(false);

  const notifRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchSeq = useRef(0);   // chống response cũ ghi đè response mới

  // Bảo vệ route: chưa đăng nhập → về /login
  useEffect(() => {
    if (!loading && !teacher) router.replace("/login");
  }, [loading, teacher, router]);

  // Khôi phục mốc "đã xem thông báo" (collapsed đã đọc ở lazy-init phía trên)
  useEffect(() => {
    try {
      setLastSeen(Number(localStorage.getItem("notif_last_seen") || 0));
    } catch { /* bỏ qua */ }
  }, []);

  // Nạp thông báo (phiên chấm gần đây) + tự làm mới mỗi 30s
  const loadNotifs = useCallback(() => {
    fetch(`${API_BASE}/batch/recent`)
      .then((r) => r.json())
      .then((d) => setNotifs(Array.isArray(d) ? d : []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    loadNotifs();
    const id = setInterval(loadNotifs, 30000);
    return () => clearInterval(id);
  }, [loadNotifs]);

  // Đóng dropdown khi click ra ngoài
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
      if (searchRef.current && !searchRef.current.contains(e.target as Node)) setSearchOpen(false);
    };
    document.addEventListener("mousedown", handler);
    return () => {
      document.removeEventListener("mousedown", handler);
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  const toggleCollapse = () => {
    setCollapsed((c) => {
      const n = !c;
      try { localStorage.setItem("sidebar_collapsed", n ? "1" : "0"); } catch { /* bỏ qua */ }
      return n;
    });
  };

  const unread = notifs.filter(
    (n) => n.createdAt && new Date(n.createdAt).getTime() > lastSeen
  ).length;

  const toggleNotif = () => {
    setNotifOpen((o) => {
      const open = !o;
      if (open && notifs.length) {
        const latest = Math.max(...notifs.map((x) => (x.createdAt ? new Date(x.createdAt).getTime() : 0)));
        setLastSeen(latest);
        try { localStorage.setItem("notif_last_seen", String(latest)); } catch { /* bỏ qua */ }
      }
      return open;
    });
  };

  const onSearchChange = (val: string) => {
    setQ(val);
    setSearchOpen(true);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (val.trim().length < 1) { setResults([]); setSearching(false); return; }
    setSearching(true);
    const seq = ++searchSeq.current;
    debounceRef.current = setTimeout(() => {
      fetch(`${API_BASE}/results/search?q=${encodeURIComponent(val.trim())}`)
        .then((r) => (r.ok ? r.json() : []))
        .then((d) => { if (seq === searchSeq.current) setResults(Array.isArray(d) ? d : []); })
        .catch(() => { if (seq === searchSeq.current) setResults([]); })
        .finally(() => { if (seq === searchSeq.current) setSearching(false); });
    }, 300);
  };

  const gotoResult = (r: SearchRow) => {
    setSearchOpen(false);
    setQ("");
    setResults([]);
    router.push(`/history?exam=${encodeURIComponent(r.examId)}&q=${encodeURIComponent(r.studentId)}`);
  };

  const onSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (results.length) gotoResult(results[0]);
    else if (q.trim()) { setSearchOpen(false); router.push(`/history?q=${encodeURIComponent(q.trim())}`); }
  };

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

  // Vùng icon CỐ ĐỊNH (w-16) → icon luôn ở 1 vị trí, không "nhảy" khi đóng/mở.
  // Chữ luôn render, chỉ fade opacity + bị panel che dần khi thu gọn → trượt mượt.
  const renderLink = (item: { name: string; path: string; icon: React.ElementType }) => {
    const isActive = activePath === item.path;
    return (
      <Link
        key={item.name}
        href={item.path}
        title={collapsed ? item.name : undefined}
        className={clsx(
          'group relative flex h-11 items-center overflow-hidden rounded-lg text-sm font-medium transition-colors',
          isActive ? 'bg-indigo-500/10 text-white' : 'text-slate-400 hover:bg-slate-800/70 hover:text-white'
        )}
      >
        {isActive && (
          <span className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-indigo-400" />
        )}
        <span className="flex w-16 shrink-0 items-center justify-center">
          <item.icon
            size={18}
            className={clsx('transition-colors', isActive ? 'text-indigo-300' : 'text-slate-500 group-hover:text-slate-300')}
          />
        </span>
        <span className={clsx('truncate whitespace-nowrap pr-3 transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
          {item.name}
        </span>
      </Link>
    );
  };

  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-50 font-sans text-slate-800">
      {/* Sidebar */}
      <aside
        className={clsx(
          'z-20 flex shrink-0 flex-col overflow-hidden bg-[#0b1120] text-slate-300 shadow-xl transition-[width] duration-300 ease-in-out',
          collapsed ? 'w-20' : 'w-64'
        )}
      >
        {/* Brand + nút thu gọn */}
        <div className="flex h-16 shrink-0 items-center overflow-hidden border-b border-white/5">
          <button
            type="button"
            onClick={toggleCollapse}
            title={collapsed ? 'Mở rộng menu' : 'Thu gọn menu'}
            className="flex h-16 w-20 shrink-0 items-center justify-center"
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 shadow-lg shadow-indigo-600/30 ring-1 ring-white/10 transition-transform hover:scale-105">
              <GraduationCap size={20} className="text-white" />
            </span>
          </button>
          <div className={clsx('min-w-0 flex-1 whitespace-nowrap leading-tight transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
            <div className="text-[15px] font-bold tracking-wide text-white">Grader</div>
            <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-slate-500">Auto-grading</div>
          </div>
          <button
            type="button"
            onClick={toggleCollapse}
            title="Thu gọn menu"
            className={clsx('mr-3 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-slate-400 transition-all hover:bg-slate-800/70 hover:text-white', collapsed ? 'pointer-events-none opacity-0' : 'opacity-100')}
          >
            <PanelLeftClose size={18} />
          </button>
        </div>

        <div className="custom-scrollbar flex-1 overflow-y-auto px-2 py-6">
          <div className={clsx('mb-3 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-600 transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>Quản lý chấm thi</div>
          <nav className="space-y-1">{PRIMARY_NAV.map(renderLink)}</nav>

          <div className={clsx('mb-3 mt-7 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-600 transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>Báo cáo & Dữ liệu</div>
          <nav className="space-y-1">{SECONDARY_NAV.map(renderLink)}</nav>
        </div>

        {/* GV đang đăng nhập + đăng xuất */}
        <div className="shrink-0 overflow-hidden border-t border-white/5 px-2 py-3">
          <Link
            href="/profile"
            title={collapsed ? teacher.fullName : undefined}
            className="mb-1 flex h-12 items-center overflow-hidden rounded-lg transition-colors hover:bg-slate-800/70"
          >
            <span className="flex w-16 shrink-0 items-center justify-center">
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-blue-600 text-xs font-bold text-white ring-1 ring-white/10">
                {initialsOf(teacher.fullName)}
              </span>
            </span>
            <div className={clsx('min-w-0 whitespace-nowrap pr-2 leading-tight transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>
              <div className="truncate text-sm font-semibold text-white">{teacher.fullName}</div>
              <div className="truncate text-[11px] text-slate-500">{teacher.email}</div>
            </div>
          </Link>
          <button
            onClick={handleLogout}
            title={collapsed ? 'Đăng xuất' : undefined}
            className="flex h-11 w-full items-center overflow-hidden rounded-lg text-sm font-medium text-slate-400 transition-colors hover:bg-slate-800/70 hover:text-white"
          >
            <span className="flex w-16 shrink-0 items-center justify-center"><LogOut size={18} /></span>
            <span className={clsx('whitespace-nowrap transition-opacity duration-200', collapsed ? 'opacity-0' : 'opacity-100')}>Đăng xuất</span>
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
            {/* Thanh tìm kiếm */}
            <div ref={searchRef} className="relative hidden md:block">
              <form onSubmit={onSearchSubmit}>
                <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  value={q}
                  onChange={(e) => onSearchChange(e.target.value)}
                  onFocus={() => q.trim() && setSearchOpen(true)}
                  placeholder="Tìm mã đề, sinh viên..."
                  className="w-64 rounded-full border border-transparent bg-slate-100 py-2 pl-9 pr-4 text-sm outline-none transition-all focus:border-indigo-400 focus:bg-white focus:ring-2 focus:ring-indigo-100"
                />
              </form>
              {searchOpen && q.trim().length > 0 && (
                <div className="absolute left-0 top-full z-30 mt-2 w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl">
                  {searching ? (
                    <div className="p-4 text-center text-xs text-slate-400">
                      <Loader2 size={14} className="mr-1 inline animate-spin" /> Đang tìm...
                    </div>
                  ) : results.length === 0 ? (
                    <div className="p-4 text-center text-xs text-slate-400">Không tìm thấy kết quả</div>
                  ) : (
                    <ul className="max-h-80 overflow-y-auto py-1">
                      {results.map((r, i) => (
                        <li key={`${r.examId}-${r.studentId}-${i}`}>
                          <button
                            onClick={() => gotoResult(r)}
                            className="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-slate-50"
                          >
                            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                              {(r.studentName || r.studentId || '?').charAt(0).toUpperCase()}
                            </div>
                            <div className="min-w-0 flex-1">
                              <p className="truncate text-sm font-medium text-slate-700">{r.studentName || r.studentId}</p>
                              <p className="truncate font-mono text-xs text-slate-400">{r.studentId} · {r.examId}</p>
                            </div>
                            {r.score != null && (
                              <span className="shrink-0 text-xs font-bold text-slate-500">{r.score.toFixed(1)}</span>
                            )}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>

            {/* Nút đổi giao diện sáng/tối — hiển thị trên mọi trang dùng layout này */}
            <ThemeToggle />

            {/* Chuông thông báo */}
            <div ref={notifRef} className="relative">
              <button
                onClick={toggleNotif}
                className="relative flex h-9 w-9 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 transition-all hover:border-indigo-300 hover:text-indigo-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:text-indigo-300"
                title="Thông báo"
              >
                <Bell size={18} />
                {unread > 0 && (
                  <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white ring-2 ring-white dark:ring-slate-800">
                    {unread > 9 ? '9+' : unread}
                  </span>
                )}
              </button>
              {notifOpen && (
                <div className="absolute right-0 top-full z-30 mt-2 w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl">
                  <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
                    <h4 className="text-sm font-bold text-slate-700">Thông báo</h4>
                    <span className="text-[11px] text-slate-400">{notifs.length} phiên gần đây</span>
                  </div>
                  {notifs.length === 0 ? (
                    <div className="p-6 text-center text-xs text-slate-400">Chưa có phiên chấm nào</div>
                  ) : (
                    <ul className="max-h-96 overflow-y-auto py-1">
                      {notifs.map((n) => {
                        const s = notifStatus(n);
                        return (
                          <li key={n.batchId}>
                            <button
                              onClick={() => { setNotifOpen(false); router.push(`/history?exam=${encodeURIComponent(n.examId)}`); }}
                              className="flex w-full items-start gap-3 px-4 py-2.5 text-left transition-colors hover:bg-slate-50"
                            >
                              <s.Icon size={18} className={clsx('mt-0.5 shrink-0', s.tone)} />
                              <div className="min-w-0 flex-1">
                                <p className="truncate text-sm font-semibold text-slate-700">{n.examId}</p>
                                <p className="text-xs text-slate-500">{s.text}</p>
                                <p className="mt-0.5 text-[11px] text-slate-400">
                                  {n.createdAt ? new Date(n.createdAt).toLocaleString('vi-VN') : ''}
                                </p>
                              </div>
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              )}
            </div>

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
