"use client";

import React, { useEffect, useState, useCallback } from 'react';
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer,
  Line, Area, PieChart, Pie, Cell, ComposedChart
} from 'recharts';
import { TrendingUp, Users, Target, Award, RefreshCw, Filter, AlertCircle, Loader2, BarChart2 } from 'lucide-react';

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

  // Tải danh sách đề cho dropdown (1 lần)
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

  return (
    <SidebarLayout title="Thống kê & Báo cáo" subtitle="Phân tích phổ điểm và tiến độ chấm bài" activePath="/statistics">
      <div className="space-y-6 max-w-7xl mx-auto pb-10">

        {/* Header Actions */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            <div className="relative">
              <Filter size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <select
                value={examId}
                onChange={e => setExamId(e.target.value)}
                className="appearance-none rounded-lg border border-slate-200 bg-white py-2 pl-9 pr-9 text-sm font-medium text-slate-700 shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="ALL">Tất cả đề thi</option>
                {exams.map(e => (
                  <option key={e.examId} value={e.examId}>{e.examName} ({e.examId})</option>
                ))}
              </select>
            </div>
          </div>
          <button
            onClick={() => loadStats(examId)}
            disabled={loading}
            className="flex items-center gap-2 rounded-lg bg-gradient-to-r from-indigo-600 to-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-blue-600/20 transition-all hover:from-indigo-700 hover:to-blue-700 disabled:opacity-60"
          >
            <RefreshCw size={16} className={loading ? "animate-spin" : ""} /> Làm mới
          </button>
        </div>

        {/* Lỗi kết nối */}
        {error && (
          <div className="flex items-center gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-600">
            <AlertCircle size={18} className="shrink-0" />
            <p className="text-sm font-medium">{error}</p>
          </div>
        )}

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
        {!error && !isEmpty && (
          <div className={loading ? "pointer-events-none opacity-50 transition-opacity" : "transition-opacity"}>
            {/* Top KPIs */}
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
              <KpiCard icon={Users}      tone="blue"    label="Tổng thí sinh"  value={stats?.totalStudents ?? 0} sub={stats && stats.totalSubmissions !== stats.totalStudents ? `${stats.totalSubmissions} lượt chấm` : undefined} />
              <KpiCard icon={Target}     tone="emerald" label="Tỉ lệ Pass"     value={`${stats?.passRate ?? 0}%`} sub={`${stats?.passCount ?? 0}/${stats?.graded ?? 0} bài`} />
              <KpiCard icon={Award}      tone="amber"   label="Điểm trung bình" value={stats?.avgScore ?? 0} suffix="/ 10" />
              <KpiCard icon={TrendingUp} tone="indigo"  label="Tiến độ chấm"   value={`${stats?.progressPct ?? 0}%`} sub={`${stats?.graded ?? 0}/${stats?.totalSubmissions ?? 0} đã chấm`} />
            </div>

            {/* Charts Row 1 */}
            <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
              {/* Bar Chart: Phổ điểm */}
              <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm lg:col-span-2">
                <div className="mb-6">
                  <h3 className="text-base font-bold text-slate-800">Phổ điểm của lớp</h3>
                  <p className="text-sm text-slate-500">Phân bố điểm số của {stats?.graded ?? 0} bài đã chấm</p>
                </div>
                <div className="h-72 w-full">
                  {!loading && (
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart data={stats?.scoreDistribution ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                        <XAxis dataKey="range" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                        <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                        <RechartsTooltip cursor={{ fill: '#f8fafc' }} contentStyle={{ borderRadius: '12px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
                        <Bar dataKey="count" name="Số lượng SV" fill="#6366f1" radius={[6, 6, 0, 0]} barSize={40} />
                      </BarChart>
                    </ResponsiveContainer>
                  )}
                </div>
              </div>

              {/* Pie Chart: Pass/Fail Ratio */}
              <div className="flex flex-col rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
                <div className="mb-4">
                  <h3 className="text-base font-bold text-slate-800">Tỉ lệ Đạt / Trượt</h3>
                  <p className="text-sm text-slate-500">Ngưỡng đạt: {stats?.passThreshold ?? 5}.0 điểm</p>
                </div>
                <div className="relative flex flex-1 flex-col items-center justify-center">
                  {hasPassFail ? (
                    <>
                      <ResponsiveContainer width="100%" height={220}>
                        <PieChart>
                          <Pie data={passFailData} innerRadius={65} outerRadius={90} paddingAngle={5} dataKey="value" stroke="none">
                            {passFailData.map((entry, index) => <Cell key={`cell-${index}`} fill={entry.color} />)}
                          </Pie>
                          <RechartsTooltip contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
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

            {/* Charts Row 2 */}
            <div className="mt-6 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
              <div className="mb-6 flex items-end justify-between">
                <div>
                  <h3 className="text-base font-bold text-slate-800">Tiến độ chấm bài 7 ngày qua</h3>
                  <p className="text-sm text-slate-500">Số bài hoàn thành và lỗi mỗi ngày</p>
                </div>
              </div>
              <div className="h-64 w-full">
                {!loading && (
                  <ResponsiveContainer width="100%" height="100%">
                    <ComposedChart data={stats?.trend ?? []} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorGraded" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#6366f1" stopOpacity={0.25} />
                          <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                      <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} dy={10} />
                      <YAxis allowDecimals={false} axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#64748b' }} />
                      <RechartsTooltip contentStyle={{ borderRadius: '12px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
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
          <div className="flex items-center justify-center rounded-2xl border border-slate-200 bg-white p-16 shadow-sm">
            <Loader2 size={24} className="mr-3 animate-spin text-indigo-500" />
            <span className="text-sm font-medium text-slate-500">Đang tải thống kê...</span>
          </div>
        )}
      </div>
    </SidebarLayout>
  );
}

// ── Thẻ KPI tái sử dụng ───────────────────────────────────────────
function KpiCard({ icon: Icon, tone, label, value, sub, suffix }: {
  icon: React.ElementType; tone: string; label: string;
  value: React.ReactNode; sub?: string; suffix?: string;
}) {
  const tones: Record<string, string> = {
    blue: "bg-blue-50 text-blue-600",
    emerald: "bg-emerald-50 text-emerald-600",
    amber: "bg-amber-50 text-amber-600",
    indigo: "bg-indigo-50 text-indigo-600",
  };
  return (
    <div className="flex items-center gap-4 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm transition-shadow hover:shadow-md">
      <div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-full ${tones[tone] || tones.blue}`}>
        <Icon size={24} />
      </div>
      <div className="min-w-0">
        <p className="mb-0.5 text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
        <p className="truncate text-2xl font-bold text-slate-800">
          {value} {suffix && <span className="text-sm font-medium text-slate-400">{suffix}</span>}
        </p>
        {sub && <p className="truncate text-xs text-slate-400">{sub}</p>}
      </div>
    </div>
  );
}
