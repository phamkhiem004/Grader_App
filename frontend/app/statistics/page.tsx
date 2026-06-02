"use client";

import React, { useEffect, useState, useCallback } from 'react';
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer,
  Line, Area, PieChart, Pie, Cell, ComposedChart
} from 'recharts';
import {
  TrendingUp, Users, Target, Award, RefreshCw, AlertCircle, Loader2, BarChart2, ChevronDown,
} from 'lucide-react';

// ── Kiểu dữ liệu khớp StatisticsResponse của backend ──────────────
interface Bucket { range: string; count: number; }
interface TrendPoint { date: string; graded: number; errors: number; }
interface Stats {
  examId: string;
  totalStudents: number;
  totalSubmissions: number;
  graded: number;
  errors: number;
  pending: number;
  passCount: number;
  failCount: number;
  passRate: number;
  avgScore: number;
  progressPct: number;
  passThreshold: number;
  scoreDistribution: Bucket[];
  trend: TrendPoint[];
}
interface ExamOption { examId: string; examName: string; }

// ── Theo dõi theme sáng/tối để tô màu biểu đồ cho khớp ────────────
function useIsDark() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const el = document.documentElement;
    const update = () => setDark(el.classList.contains('dark'));
    update();
    const obs = new MutationObserver(update);
    obs.observe(el, { attributes: true, attributeFilter: ['class'] });
    return () => obs.disconnect();
  }, []);
  return dark;
}

export default function StatisticsPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [examId, setExamId] = useState<string>("ALL");
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const dark = useIsDark();

  // Màu biểu đồ theo theme
  const grid = dark ? '#1e293b' : '#e2e8f0';
  const tick = dark ? '#94a3b8' : '#64748b';
  const tooltipStyle: React.CSSProperties = {
    borderRadius: 12,
    border: `1px solid ${dark ? '#1e2a42' : '#e6e8f2'}`,
    background: dark ? '#0f1729' : '#ffffff',
    color: dark ? '#e5e7eb' : '#0f172a',
    boxShadow: '0 10px 30px -12px rgba(2,6,23,.45)',
    fontSize: 13,
  };

  // Tải danh sách đề cho dropdown (chỉ đề đã có bài chấm)
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then(r => r.ok ? r.json() : [])
      .then((data) => setExams(Array.isArray(data) ? data : []))
      .catch(() => { /* để trống dropdown nếu lỗi */ });
  }, []);

  const loadStats = useCallback(async (id: string) => {
    setLoading(true); setError(null);
    try {
      const res = await fetch(`${API_BASE}/statistics?examId=${encodeURIComponent(id)}`);
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Lỗi tải thống kê");
      setStats(data);
    } catch (e: any) {
      setError(e.message?.includes("fetch") ? "Không kết nối được server." : (e.message || "Lỗi tải thống kê"));
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadStats(examId); }, [examId, loadStats]);

  const passFailData = stats ? [
    { name: `Đạt (≥${stats.passThreshold})`, value: stats.passCount, color: '#10b981' },
    { name: `Trượt (<${stats.passThreshold})`, value: stats.failCount, color: '#f43f5e' },
  ] : [];
  const hasPassFail = stats ? (stats.passCount + stats.failCount) > 0 : false;
  const isEmpty = stats != null && stats.totalStudents === 0;
  const selectedName = examId === "ALL"
    ? "Tất cả đề thi"
    : (exams.find(e => e.examId === examId)?.examName || examId);

  return (
    <SidebarLayout title="Thống kê & Báo cáo" subtitle="Phân tích phổ điểm và tiến độ chấm bài" activePath="/statistics">
      <div className="space-y-6 max-w-7xl mx-auto pb-10">

        {/* ── Thanh tiêu đề + bộ lọc ─────────────────────────────── */}
        <div className="card flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-4">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-lg shadow-indigo-600/30 ring-1 ring-white/10">
              <BarChart2 size={24} />
            </div>
            <div className="min-w-0">
              <h2 className="truncate text-lg font-bold tracking-tight text-slate-800">{selectedName}</h2>
              <p className="text-xs text-slate-500">
                Ngưỡng đạt {stats?.passThreshold ?? 5}.0 điểm · {stats?.totalSubmissions ?? 0} lượt chấm
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <div className="relative">
              <select
                value={examId}
                onChange={e => setExamId(e.target.value)}
                className="w-full appearance-none rounded-xl border border-slate-200 bg-slate-50 py-2.5 pl-4 pr-10 text-sm font-medium text-slate-700 shadow-sm outline-none transition-all focus:border-transparent focus:ring-2 focus:ring-indigo-500 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 sm:w-64"
              >
                <option value="ALL">Tất cả đề thi</option>
                {exams.map(e => (
                  <option key={e.examId} value={e.examId}>{e.examName} ({e.examId})</option>
                ))}
              </select>
              <ChevronDown size={16} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-slate-400" />
            </div>
            <button
              onClick={() => loadStats(examId)}
              disabled={loading}
              className="flex h-[42px] items-center gap-2 rounded-xl bg-gradient-to-r from-indigo-600 to-blue-600 px-4 text-sm font-semibold text-white shadow-sm shadow-blue-600/25 transition-all hover:from-indigo-700 hover:to-blue-700 active:scale-[0.98] disabled:opacity-60"
            >
              <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
              <span className="hidden sm:inline">Làm mới</span>
            </button>
          </div>
        </div>

        {/* Lỗi kết nối */}
        {error && (
          <div className="flex items-center gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-600 dark:border-rose-500/30">
            <AlertCircle size={18} className="shrink-0" />
            <p className="text-sm font-medium">{error}</p>
          </div>
        )}

        {/* Chưa có dữ liệu */}
        {!loading && !error && isEmpty && (
          <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-16 text-center backdrop-blur-sm dark:border-slate-700">
            <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
              <BarChart2 size={30} />
            </div>
            <h3 className="mb-2 text-base font-bold text-slate-700">Chưa có dữ liệu chấm bài</h3>
            <p className="max-w-sm text-sm text-slate-500">
              {examId === "ALL" ? "Hãy chấm một vài bài thi để xem thống kê tại đây." : `Đề "${examId}" chưa có bài nào được chấm.`}
            </p>
          </div>
        )}

        {/* ── Nội dung thống kê ──────────────────────────────────── */}
        {!error && !isEmpty && (
          <div className={loading ? "pointer-events-none space-y-6 opacity-50 transition-opacity" : "space-y-6 transition-opacity"}>
            {/* KPIs */}
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
              <KpiCard icon={Users}      tone="blue"    label="Tổng thí sinh"  value={stats?.totalStudents ?? 0} sub={stats && stats.totalSubmissions !== stats.totalStudents ? `${stats.totalSubmissions} lượt chấm` : "đã tham gia"} />
              <KpiCard icon={Target}     tone="emerald" label="Tỉ lệ Pass"     value={`${stats?.passRate ?? 0}%`} sub={`${stats?.passCount ?? 0}/${stats?.graded ?? 0} bài đạt`} />
              <KpiCard icon={Award}      tone="amber"   label="Điểm trung bình" value={stats?.avgScore ?? 0} suffix="/ 10" sub={`${stats?.graded ?? 0} bài đã chấm`} />
              <KpiCard icon={TrendingUp} tone="indigo"  label="Tiến độ chấm"   value={`${stats?.progressPct ?? 0}%`} sub={`${stats?.graded ?? 0}/${stats?.totalSubmissions ?? 0} hoàn thành`} progress={stats?.progressPct} />
            </div>

            {/* Hàng biểu đồ 1 */}
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              {/* Phổ điểm */}
              <div className="card p-6 lg:col-span-2">
                <div className="mb-6 flex items-center justify-between">
                  <div>
                    <h3 className="text-base font-bold text-slate-800">Phổ điểm của lớp</h3>
                    <p className="text-sm text-slate-500">Phân bố điểm số của {stats?.graded ?? 0} bài đã chấm</p>
                  </div>
                  <span className="hidden rounded-lg bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-600 sm:inline dark:bg-indigo-500/15 dark:text-indigo-300">Hệ điểm 10</span>
                </div>
                <div className="h-72 w-full">
                  {!loading && (
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={stats?.scoreDistribution ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                        <defs>
                          <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#818cf8" />
                            <stop offset="100%" stopColor="#6366f1" />
                          </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke={grid} />
                        <XAxis dataKey="range" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: tick }} dy={10} />
                        <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: tick }} />
                        <RechartsTooltip cursor={{ fill: dark ? 'rgba(148,163,184,0.08)' : '#f8fafc' }} contentStyle={tooltipStyle} />
                        <Bar dataKey="count" name="Số lượng SV" fill="url(#barGrad)" radius={[8, 8, 0, 0]} barSize={44} />
                      </BarChart>
                    </ResponsiveContainer>
                  )}
                </div>
              </div>

              {/* Tỉ lệ Đạt/Trượt */}
              <div className="card flex flex-col p-6">
                <div className="mb-4">
                  <h3 className="text-base font-bold text-slate-800">Tỉ lệ Đạt / Trượt</h3>
                  <p className="text-sm text-slate-500">Ngưỡng đạt: {stats?.passThreshold ?? 5}.0 điểm</p>
                </div>
                <div className="relative flex flex-1 flex-col items-center justify-center">
                  {hasPassFail ? (
                    <>
                      <ResponsiveContainer width="100%" height={220}>
                        <PieChart>
                          <Pie data={passFailData} innerRadius={66} outerRadius={92} paddingAngle={4} dataKey="value" stroke="none" cornerRadius={6}>
                            {passFailData.map((entry, index) => <Cell key={`cell-${index}`} fill={entry.color} />)}
                          </Pie>
                          <RechartsTooltip contentStyle={tooltipStyle} />
                        </PieChart>
                      </ResponsiveContainer>
                      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                        <span className="text-3xl font-bold text-slate-800">{stats?.passRate ?? 0}%</span>
                        <span className="text-xs font-semibold uppercase tracking-wide text-emerald-500">Đạt</span>
                      </div>
                    </>
                  ) : (
                    <p className="py-16 text-sm text-slate-400">Chưa có bài nào được chấm xong</p>
                  )}
                </div>
                {hasPassFail && (
                  <div className="mt-2 flex justify-center gap-6">
                    {passFailData.map((item) => (
                      <div key={item.name} className="flex items-center gap-2">
                        <div className="h-3 w-3 rounded-full" style={{ backgroundColor: item.color }}></div>
                        <span className="text-sm font-medium text-slate-600">{item.name} ({item.value})</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Hàng biểu đồ 2 */}
            <div className="card p-6">
              <div className="mb-6 flex items-end justify-between">
                <div>
                  <h3 className="text-base font-bold text-slate-800">Tiến độ chấm bài 7 ngày qua</h3>
                  <p className="text-sm text-slate-500">Số bài hoàn thành và lỗi mỗi ngày</p>
                </div>
                <div className="hidden items-center gap-4 text-xs font-medium text-slate-500 sm:flex">
                  <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-indigo-500" /> Hoàn thành</span>
                  <span className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-rose-500" /> Lỗi</span>
                </div>
              </div>
              <div className="h-64 w-full">
                {!loading && (
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={stats?.trend ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorGraded" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#6366f1" stopOpacity={0.28} />
                          <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke={grid} />
                      <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: tick }} dy={10} />
                      <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: tick }} />
                      <RechartsTooltip contentStyle={tooltipStyle} />
                      <Area type="monotone" dataKey="graded" name="Hoàn thành" stroke="#6366f1" strokeWidth={3} fillOpacity={1} fill="url(#colorGraded)" />
                      <Line type="monotone" dataKey="errors" name="Lỗi" stroke="#f43f5e" strokeWidth={2} dot={{ r: 4, fill: '#f43f5e' }} />
                    </ComposedChart>
                  </ResponsiveContainer>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Skeleton lần tải đầu */}
        {loading && !stats && !error && (
          <div className="card flex items-center justify-center p-16">
            <Loader2 size={24} className="mr-3 animate-spin text-indigo-500" />
            <span className="text-sm font-medium text-slate-500">Đang tải thống kê...</span>
          </div>
        )}
      </div>
    </SidebarLayout>
  );
}

// ── Thẻ KPI tái sử dụng ───────────────────────────────────────────
function KpiCard({ icon: Icon, tone, label, value, sub, suffix, progress }: {
  icon: React.ElementType; tone: string; label: string;
  value: React.ReactNode; sub?: string; suffix?: string; progress?: number;
}) {
  const tones: Record<string, { badge: string; bar: string }> = {
    blue:    { badge: "bg-blue-50 text-blue-600 dark:bg-blue-500/15 dark:text-blue-300",          bar: "bg-blue-500" },
    emerald: { badge: "bg-emerald-50 text-emerald-600 dark:bg-emerald-500/15 dark:text-emerald-300", bar: "bg-emerald-500" },
    amber:   { badge: "bg-amber-50 text-amber-600 dark:bg-amber-500/15 dark:text-amber-300",       bar: "bg-amber-500" },
    indigo:  { badge: "bg-indigo-50 text-indigo-600 dark:bg-indigo-500/15 dark:text-indigo-300",    bar: "bg-indigo-500" },
  };
  const t = tones[tone] || tones.blue;
  return (
    <div className="card card-hover p-5">
      <div className="mb-3 flex items-start justify-between">
        <p className="eyebrow pt-1">{label}</p>
        <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${t.badge}`}>
          <Icon size={20} />
        </span>
      </div>
      <p className="text-3xl font-bold tracking-tight text-slate-800">
        {value} {suffix && <span className="text-sm font-medium text-slate-400">{suffix}</span>}
      </p>
      {typeof progress === "number" && (
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
          <div className={`h-full rounded-full ${t.bar} transition-all duration-500`} style={{ width: `${Math.min(100, Math.max(0, progress))}%` }} />
        </div>
      )}
      {sub && <p className="mt-2 truncate text-xs text-slate-400">{sub}</p>}
    </div>
  );
}
