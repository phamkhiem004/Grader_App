"use client";

import React, { useEffect, useState, useCallback } from 'react';
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer,
  Line, Area, PieChart, Pie, Cell, ComposedChart
} from 'recharts';
import {
  TrendingUp, Users, Target, Award, RefreshCw, Filter, AlertCircle, BarChart2,
  CheckCircle2, Clock, XCircle
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

export default function StatisticsPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [examId, setExamId] = useState<string>("ALL");
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);

  // Tải danh sách đề cho dropdown (1 lần) — backend chỉ trả về đề đã chấm
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
      setUpdatedAt(new Date());
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
  const currentExamName = examId === "ALL"
    ? "Tất cả đề thi"
    : (exams.find(e => e.examId === examId)?.examName ?? examId);

  return (
    <SidebarLayout title="Thống kê & Báo cáo" subtitle="Phân tích phổ điểm và tiến độ chấm bài" activePath="/statistics">
      <div className="space-y-6 pb-10">

        {/* Header / Toolbar */}
        <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-4">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-lg shadow-indigo-600/25">
                <BarChart2 size={24} />
              </div>
              <div className="min-w-0">
                <h2 className="truncate text-lg font-bold text-slate-900">{currentExamName}</h2>
                <p className="text-xs text-slate-500">
                  {updatedAt
                    ? `Cập nhật lúc ${updatedAt.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}`
                    : "Đang tải dữ liệu..."}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2.5">
              <div className="relative">
                <Filter size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <select
                  value={examId}
                  onChange={e => setExamId(e.target.value)}
                  className="appearance-none rounded-lg border border-slate-200 bg-slate-50 py-2.5 pl-9 pr-9 text-sm font-medium text-slate-700 shadow-sm transition-all hover:bg-white focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="ALL">Tất cả đề thi</option>
                  {exams.map(e => (
                    <option key={e.examId} value={e.examId}>{e.examName} ({e.examId})</option>
                  ))}
                </select>
                <svg className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.17l3.71-3.94a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clipRule="evenodd" /></svg>
              </div>
              <button
                onClick={() => loadStats(examId)}
                disabled={loading}
                className="flex items-center gap-2 rounded-lg bg-gradient-to-r from-indigo-600 to-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-blue-600/20 transition-all hover:from-indigo-700 hover:to-blue-700 disabled:opacity-60"
              >
                <RefreshCw size={16} className={loading ? "animate-spin" : ""} /> Làm mới
              </button>
            </div>
          </div>
        </div>

        {/* Lỗi kết nối */}
        {error && (
          <div className="flex items-center gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-600">
            <AlertCircle size={18} className="shrink-0" />
            <p className="text-sm font-medium">{error}</p>
          </div>
        )}

        {/* Skeleton lần tải đầu */}
        {loading && !stats && !error && <StatsSkeleton />}

        {/* Trạng thái chưa có dữ liệu */}
        {!loading && !error && isEmpty && (
          <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-200 bg-white/50 p-16 text-center">
            <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
              <BarChart2 size={30} />
            </div>
            <h3 className="mb-2 text-base font-bold text-slate-700">Chưa có dữ liệu chấm bài</h3>
            <p className="max-w-sm text-sm text-slate-500">
              {examId === "ALL" ? "Hãy chấm một vài bài thi để xem thống kê tại đây." : `Đề "${examId}" chưa có bài nào được chấm.`}
            </p>
          </div>
        )}

        {/* Nội dung thống kê */}
        {!error && !isEmpty && stats && (
          <div className={loading ? "space-y-6 pointer-events-none opacity-60 transition-opacity" : "space-y-6 transition-opacity"}>
            {/* Top KPIs */}
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
              <KpiCard icon={Users}      tone="blue"    label="Tổng thí sinh"  value={stats.totalStudents} sub={stats.totalSubmissions !== stats.totalStudents ? `${stats.totalSubmissions} lượt chấm` : "Số sinh viên dự thi"} />
              <KpiCard icon={Target}     tone="emerald" label="Tỉ lệ Pass"     value={`${stats.passRate}%`} sub={`${stats.passCount}/${stats.graded} bài đạt`} progress={stats.passRate} />
              <KpiCard icon={Award}      tone="amber"   label="Điểm trung bình" value={stats.avgScore} suffix="/ 10" progress={stats.avgScore * 10} />
              <KpiCard icon={TrendingUp} tone="indigo"  label="Tiến độ chấm"   value={`${stats.progressPct}%`} sub={`${stats.graded}/${stats.totalSubmissions} đã chấm`} progress={stats.progressPct} />
            </div>

            {/* Status breakdown */}
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <StatusPill icon={CheckCircle2} tone="emerald" label="Đã chấm xong" value={stats.graded} />
              <StatusPill icon={Clock}        tone="amber"   label="Đang chờ / chấm" value={stats.pending} />
              <StatusPill icon={XCircle}      tone="rose"    label="Lỗi" value={stats.errors} />
            </div>

            {/* Charts Row 1 */}
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              {/* Bar Chart: Phổ điểm */}
              <ChartCard
                className="lg:col-span-2"
                icon={BarChart2}
                tone="indigo"
                title="Phổ điểm của lớp"
                subtitle={`Phân bố điểm số của ${stats.graded} bài đã chấm`}
              >
                <div className="h-72 w-full">
                  {!loading && (
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={stats.scoreDistribution ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                        <defs>
                          <linearGradient id="barGrad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#818cf8" />
                            <stop offset="100%" stopColor="#6366f1" />
                          </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#eef2f7" />
                        <XAxis dataKey="range" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                        <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                        <RechartsTooltip cursor={{ fill: '#f8fafc' }} content={<ChartTooltip unit="sinh viên" />} />
                        <Bar dataKey="count" name="Số sinh viên" fill="url(#barGrad)" radius={[6, 6, 0, 0]} barSize={44} />
                      </BarChart>
                    </ResponsiveContainer>
                  )}
                </div>
              </ChartCard>

              {/* Pie Chart: Pass/Fail Ratio */}
              <ChartCard icon={Target} tone="emerald" title="Tỉ lệ Đạt / Trượt" subtitle={`Ngưỡng đạt: ${stats.passThreshold}.0 điểm`}>
                <div className="relative flex flex-1 flex-col items-center justify-center">
                  {hasPassFail ? (
                    <>
                      <ResponsiveContainer width="100%" height={220}>
                        <PieChart>
                          <Pie data={passFailData} innerRadius={65} outerRadius={90} paddingAngle={4} dataKey="value" stroke="none">
                            {passFailData.map((entry, index) => <Cell key={`cell-${index}`} fill={entry.color} />)}
                          </Pie>
                          <RechartsTooltip content={<ChartTooltip unit="bài" />} />
                        </PieChart>
                      </ResponsiveContainer>
                      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                        <span className="text-3xl font-bold text-slate-800">{stats.passRate}%</span>
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
              </ChartCard>
            </div>

            {/* Charts Row 2 */}
            <ChartCard icon={TrendingUp} tone="blue" title="Tiến độ chấm bài 7 ngày qua" subtitle="Số bài hoàn thành và lỗi mỗi ngày">
              <div className="h-64 w-full">
                {!loading && (
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={stats.trend ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorGraded" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#6366f1" stopOpacity={0.25} />
                          <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#eef2f7" />
                      <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                      <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                      <RechartsTooltip content={<ChartTooltip unit="bài" />} />
                      <Area type="monotone" dataKey="graded" name="Hoàn thành" stroke="#6366f1" strokeWidth={3} fillOpacity={1} fill="url(#colorGraded)" />
                      <Line type="monotone" dataKey="errors" name="Lỗi" stroke="#f43f5e" strokeWidth={2} dot={{ r: 4, fill: '#f43f5e' }} />
                    </ComposedChart>
                  </ResponsiveContainer>
                )}
              </div>
              <div className="mt-3 flex justify-center gap-6">
                <div className="flex items-center gap-2"><span className="h-3 w-3 rounded-full bg-indigo-500" /><span className="text-sm font-medium text-slate-600">Hoàn thành</span></div>
                <div className="flex items-center gap-2"><span className="h-3 w-3 rounded-full bg-rose-500" /><span className="text-sm font-medium text-slate-600">Lỗi</span></div>
              </div>
            </ChartCard>
          </div>
        )}
      </div>
    </SidebarLayout>
  );
}

// ── Bảng màu dùng chung ───────────────────────────────────────────
const TONES: Record<string, { icon: string; bar: string; soft: string }> = {
  blue:    { icon: "bg-blue-50 text-blue-600",       bar: "from-blue-500 to-blue-600",       soft: "bg-blue-500" },
  emerald: { icon: "bg-emerald-50 text-emerald-600", bar: "from-emerald-500 to-emerald-600", soft: "bg-emerald-500" },
  amber:   { icon: "bg-amber-50 text-amber-600",     bar: "from-amber-500 to-amber-600",     soft: "bg-amber-500" },
  indigo:  { icon: "bg-indigo-50 text-indigo-600",   bar: "from-indigo-500 to-indigo-600",   soft: "bg-indigo-500" },
  rose:    { icon: "bg-rose-50 text-rose-600",       bar: "from-rose-500 to-rose-600",       soft: "bg-rose-500" },
};

// ── Thẻ KPI tái sử dụng ───────────────────────────────────────────
function KpiCard({ icon: Icon, tone, label, value, sub, suffix, progress }: {
  icon: React.ElementType; tone: string; label: string;
  value: React.ReactNode; sub?: string; suffix?: string; progress?: number;
}) {
  const t = TONES[tone] || TONES.blue;
  return (
    <div className="group relative overflow-hidden rounded-2xl border border-slate-200 bg-white p-5 shadow-sm transition-all hover:-translate-y-0.5 hover:shadow-md">
      <div className={`absolute inset-x-0 top-0 h-1 bg-gradient-to-r ${t.bar}`} />
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="mb-1 text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
          <p className="truncate text-2xl font-bold text-slate-800">
            {value} {suffix && <span className="text-sm font-medium text-slate-400">{suffix}</span>}
          </p>
        </div>
        <div className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${t.icon} transition-transform group-hover:scale-110`}>
          <Icon size={22} />
        </div>
      </div>
      {typeof progress === "number" && (
        <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
          <div className={`h-full rounded-full ${t.soft} transition-all`} style={{ width: `${Math.max(0, Math.min(100, progress))}%` }} />
        </div>
      )}
      {sub && <p className="mt-2 truncate text-xs text-slate-400">{sub}</p>}
    </div>
  );
}

// ── Ô trạng thái nhỏ ──────────────────────────────────────────────
function StatusPill({ icon: Icon, tone, label, value }: {
  icon: React.ElementType; tone: string; label: string; value: number;
}) {
  const t = TONES[tone] || TONES.blue;
  return (
    <div className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 shadow-sm">
      <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${t.icon}`}>
        <Icon size={20} />
      </div>
      <div>
        <p className="text-xl font-bold leading-none text-slate-800">{value}</p>
        <p className="mt-1 text-xs font-medium text-slate-500">{label}</p>
      </div>
    </div>
  );
}

// ── Khung biểu đồ tái sử dụng ─────────────────────────────────────
function ChartCard({ icon: Icon, tone, title, subtitle, children, className = "" }: {
  icon: React.ElementType; tone: string; title: string; subtitle?: string;
  children: React.ReactNode; className?: string;
}) {
  const t = TONES[tone] || TONES.indigo;
  return (
    <div className={`flex flex-col rounded-2xl border border-slate-200 bg-white p-6 shadow-sm ${className}`}>
      <div className="mb-6 flex items-center gap-3">
        <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${t.icon}`}>
          <Icon size={18} />
        </div>
        <div>
          <h3 className="text-base font-bold text-slate-800">{title}</h3>
          {subtitle && <p className="text-sm text-slate-500">{subtitle}</p>}
        </div>
      </div>
      {children}
    </div>
  );
}

// ── Tooltip biểu đồ tuỳ chỉnh ─────────────────────────────────────
function ChartTooltip({ active, payload, label, unit }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-xl border border-slate-100 bg-white px-3.5 py-2.5 shadow-lg">
      {label && <p className="mb-1.5 text-xs font-semibold text-slate-700">{label}</p>}
      {payload.map((p: any, i: number) => (
        <div key={i} className="flex items-center gap-2 text-sm">
          <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: p.color || p.payload?.color }} />
          <span className="text-slate-500">{p.name}:</span>
          <span className="font-semibold text-slate-800">{p.value} {unit}</span>
        </div>
      ))}
    </div>
  );
}

// ── Skeleton khi tải lần đầu ──────────────────────────────────────
function StatsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-28 animate-pulse rounded-2xl border border-slate-200 bg-white shadow-sm" />
        ))}
      </div>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="h-96 animate-pulse rounded-2xl border border-slate-200 bg-white shadow-sm lg:col-span-2" />
        <div className="h-96 animate-pulse rounded-2xl border border-slate-200 bg-white shadow-sm" />
      </div>
    </div>
  );
}
