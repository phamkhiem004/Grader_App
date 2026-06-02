"use client";

import React, { useEffect, useMemo, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import { Skeleton, SkeletonRow } from "@/components/ui/Skeleton";
import { Tooltip } from "@/components/ui/Tooltip";
import {
  History, FileJson, DownloadCloud, Search, ChevronRight,
  CheckCircle, AlertCircle, Clock, Users, FileText, FileArchive,
} from "lucide-react";

interface ExamOption { examId: string; examName: string; }
interface ResultRow {
  id: number;
  studentId: string;
  studentName: string | null;
  score: number | null;
  status: "DONE" | "ERROR" | "GRADING" | "QUEUED";
  batchId: string | null;
  updatedAt: string | null;
  details: string | null;
  errorLog: string | null;
  hasJson: boolean;
}

/** Lấy pass/total từ field details (JSON gọn của grader). */
function passInfo(details: string | null): { pass: number; total: number } {
  try {
    const d = JSON.parse(details || "{}");
    return { pass: d.soTestPass ?? 0, total: d.tongSoTest ?? 0 };
  } catch {
    return { pass: 0, total: 0 };
  }
}

export default function HistoryPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [loadingExams, setLoadingExams] = useState(true);
  const [loadingRows, setLoadingRows] = useState(false);
  const [q, setQ] = useState("");

  // Đọc query param từ thanh search header (?exam=...&q=...) — ưu tiên trước khi chọn mặc định
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const ex = params.get("exam");
    const query = params.get("q");
    if (query) setQ(query);
    if (ex) setSelected(ex);
  }, []);

  // Nạp danh sách đề đã chấm
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then((r) => r.json())
      .then((data: ExamOption[]) => {
        setExams(Array.isArray(data) ? data : []);
        // Chỉ tự chọn đề đầu khi CHƯA có đề nào được chọn từ URL
        if (Array.isArray(data) && data.length) {
          setSelected((prev) => prev || data[0].examId);
        }
      })
      .catch(() => setExams([]))
      .finally(() => setLoadingExams(false));
  }, []);

  // Nạp danh sách bài đã chấm của đề được chọn
  useEffect(() => {
    if (!selected) return;
    setLoadingRows(true);
    setRows([]);
    fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}`)
      .then((r) => r.json())
      .then((data: ResultRow[]) => setRows(Array.isArray(data) ? data : []))
      .catch(() => setRows([]))
      .finally(() => setLoadingRows(false));
  }, [selected]);

  const selectedName = exams.find((e) => e.examId === selected)?.examName || selected || "";

  const filtered = useMemo(() => {
    const k = q.trim().toLowerCase();
    if (!k) return rows;
    return rows.filter(
      (r) =>
        r.studentId.toLowerCase().includes(k) ||
        (r.studentName || "").toLowerCase().includes(k)
    );
  }, [rows, q]);

  // Thống kê nhanh
  const stats = useMemo(() => {
    const done = rows.filter((r) => r.status === "DONE");
    const error = rows.filter((r) => r.status === "ERROR").length;
    const avg = done.length
      ? done.reduce((s, r) => s + (r.score || 0), 0) / done.length
      : 0;
    return { total: rows.length, done: done.length, error, avg };
  }, [rows]);

  // Tải JSON của 1 sinh viên
  const downloadStudentJson = async (r: ResultRow) => {
    if (!selected) return;
    try {
      const res = await fetch(
        `${API_BASE}/results/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`
      );
      if (!res.ok) return;
      const text = await res.text();
      const blob = new Blob([text], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${r.studentId}.json`;
      a.click();
    } catch {
      /* bỏ qua */
    }
  };

  // Tải JSON GỘP toàn bộ bài đã chấm xong của đề (cho AI đọc / lưu trữ)
  const downloadAllJson = async () => {
    if (!selected) return;
    try {
      const res = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}/full`);
      if (!res.ok) return;
      const text = await res.text();
      const blob = new Blob([text], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      const dateStr = new Date().toISOString().split("T")[0];
      a.download = `${selected}_tatca_${dateStr}.json`;
      a.click();
    } catch {
      /* bỏ qua */
    }
  };

  // Xuất CSV toàn bộ đề
  const exportCSV = () => {
    if (!rows.length) return;
    const header = "Mã SV,Họ tên,Điểm,Trạng thái,Pass,Thời gian\n";
    const body = rows
      .map((r) => {
        const { pass, total } = passInfo(r.details);
        const statusVi =
          r.status === "DONE" ? "Đã xong" : r.status === "ERROR" ? "Lỗi" : r.status;
        const time = r.updatedAt ? new Date(r.updatedAt).toLocaleString("vi-VN") : "";
        const name = (r.studentName || "").replace(/"/g, '""');
        return `${r.studentId},"${name}",${r.score != null ? r.score.toFixed(1) : ""},${statusVi},"${pass}/${total}","${time}"`;
      })
      .join("\n");
    const blob = new Blob(["﻿" + header + body], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    const dateStr = new Date().toISOString().split("T")[0];
    a.download = `${selected}_lichsu_${dateStr}.csv`;
    a.click();
  };

  return (
    <SidebarLayout
      title="Lịch sử chấm"
      subtitle="Xem lại kết quả các bài đã chấm theo đề thi"
      activePath="/history"
    >
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-4">
        {/* Cột trái: danh sách đề đã chấm */}
        <div className="xl:col-span-1">
          <div className="card overflow-hidden">
            <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-4">
              <FileText size={16} className="text-indigo-500" />
              <h2 className="text-sm font-bold uppercase tracking-wider text-slate-600">
                Đề đã chấm ({exams.length})
              </h2>
            </div>
            <div className="custom-scrollbar max-h-[70vh] overflow-y-auto p-2">
              {loadingExams ? (
                Array.from({ length: 6 }).map((_, i) => (
                  <div key={i} className="px-2 py-1"><Skeleton className="h-12 w-full rounded-lg" /></div>
                ))
              ) : exams.length === 0 ? (
                <div className="p-6 text-center text-sm text-slate-400">
                  Chưa có đề nào được chấm.
                </div>
              ) : (
                exams.map((e) => {
                  const active = e.examId === selected;
                  return (
                    <button
                      key={e.examId}
                      onClick={() => setSelected(e.examId)}
                      className={`group mb-1 flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2.5 text-left transition-colors ${
                        active ? "bg-indigo-50 text-indigo-700" : "hover:bg-slate-50 text-slate-700"
                      }`}
                    >
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold">{e.examName}</p>
                        <p className="truncate font-mono text-xs text-slate-400">{e.examId}</p>
                      </div>
                      <ChevronRight
                        size={16}
                        className={active ? "text-indigo-500" : "text-slate-300 group-hover:text-slate-400"}
                      />
                    </button>
                  );
                })
              )}
            </div>
          </div>
        </div>

        {/* Cột phải: danh sách bài đã chấm của đề */}
        <div className="space-y-6 xl:col-span-3">
          {/* Thống kê nhanh */}
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            <MiniStat label="Tổng bài" value={stats.total} icon={Users} tone="slate" />
            <MiniStat label="Đã xong" value={stats.done} icon={CheckCircle} tone="emerald" />
            <MiniStat label="Lỗi" value={stats.error} icon={AlertCircle} tone="rose" />
            <MiniStat label="Điểm TB" value={stats.avg.toFixed(1)} icon={Clock} tone="indigo" />
          </div>

          <div className="card overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/50 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">{selectedName || "—"}</h3>
                <p className="text-xs text-slate-500">{filtered.length} bài hiển thị</p>
              </div>
              <div className="flex items-center gap-2">
                <div className="relative">
                  <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-400" />
                  <input
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    placeholder="Tìm mã SV / tên..."
                    className="w-44 rounded-lg border border-slate-200 bg-white py-1.5 pl-8 pr-3 text-xs outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                </div>
                <button
                  onClick={downloadAllJson}
                  disabled={!rows.some((r) => r.hasJson)}
                  title="Tải JSON gộp toàn bộ bài đã chấm của đề (cho AI nhận xét)"
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <FileArchive size={15} /> JSON
                </button>
                <button
                  onClick={exportCSV}
                  disabled={!rows.length}
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <DownloadCloud size={15} /> Xuất CSV
                </button>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-left">
                <thead>
                  <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                    <th className="px-6 py-3.5">Sinh viên</th>
                    <th className="px-6 py-3.5 text-center">Trạng thái</th>
                    <th className="px-6 py-3.5">Pass</th>
                    <th className="px-6 py-3.5 text-right">Điểm</th>
                    <th className="px-6 py-3.5">Thời gian</th>
                    <th className="px-4 py-3.5 text-center">JSON</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {loadingRows ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <tr key={i}>
                        <td className="px-6 py-3.5"><div className="flex items-center gap-3"><Skeleton className="h-9 w-9 rounded-full" /><div className="flex-1 space-y-2"><Skeleton className="h-3.5 w-28" /><Skeleton className="h-3 w-16" /></div></div></td>
                        <td className="px-6 py-3.5"><Skeleton className="mx-auto h-5 w-16 rounded-full" /></td>
                        <td className="px-6 py-3.5"><Skeleton className="h-3 w-24" /></td>
                        <td className="px-6 py-3.5"><Skeleton className="ml-auto h-6 w-10 rounded-lg" /></td>
                        <td className="px-6 py-3.5"><Skeleton className="h-3 w-24" /></td>
                        <td className="px-4 py-3.5"><Skeleton className="mx-auto h-7 w-7 rounded-lg" /></td>
                      </tr>
                    ))
                  ) : filtered.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-6 py-10 text-center text-sm text-slate-400">
                        Không có bài nào.
                      </td>
                    </tr>
                  ) : (
                    filtered.map((r) => {
                      const { pass, total } = passInfo(r.details);
                      const ratio = total > 0 ? Math.round((pass / total) * 100) : 0;
                      const isDone = r.status === "DONE";
                      const isError = r.status === "ERROR";
                      const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                      return (
                        <tr key={r.id} className="transition-colors hover:bg-slate-50/70">
                          <td className="px-6 py-3.5">
                            <div className="flex items-center gap-3">
                              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                                {initials}
                              </div>
                              <div className="min-w-0">
                                <p className="truncate text-sm font-semibold text-slate-800">
                                  {r.studentName || "—"}
                                </p>
                                <p className="font-mono text-xs text-slate-400">{r.studentId}</p>
                              </div>
                            </div>
                          </td>
                          <td className="px-6 py-3.5 text-center">
                            <span
                              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
                                isDone
                                  ? "bg-emerald-100 text-emerald-700"
                                  : isError
                                  ? "bg-rose-100 text-rose-700"
                                  : "bg-slate-100 text-slate-600"
                              }`}
                            >
                              <span
                                className={`h-1.5 w-1.5 rounded-full ${
                                  isDone ? "bg-emerald-500" : isError ? "bg-rose-500" : "bg-slate-400"
                                }`}
                              ></span>
                              {isDone ? "Đã xong" : isError ? "Lỗi" : r.status}
                            </span>
                          </td>
                          <td className="px-6 py-3.5">
                            {isDone ? (
                              <div className="flex items-center gap-2">
                                <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                                  <div
                                    className={`h-full rounded-full ${ratio >= 50 ? "bg-emerald-500" : "bg-amber-500"}`}
                                    style={{ width: `${ratio}%` }}
                                  ></div>
                                </div>
                                <span className="text-xs font-medium text-slate-500">
                                  {pass}/{total}
                                </span>
                              </div>
                            ) : isError && r.errorLog ? (
                              <span className="line-clamp-2 max-w-xs text-xs text-rose-500" title={r.errorLog}>
                                {r.errorLog}
                              </span>
                            ) : (
                              <span className="text-slate-300">—</span>
                            )}
                          </td>
                          <td className="px-6 py-3.5 text-right">
                            {r.score != null ? (
                              <span
                                className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${
                                  r.score >= PASS_THRESHOLD
                                    ? "bg-emerald-50 text-emerald-600"
                                    : "bg-rose-50 text-rose-600"
                                }`}
                              >
                                {r.score.toFixed(1)}
                              </span>
                            ) : (
                              <span className="font-medium text-slate-300">—</span>
                            )}
                          </td>
                          <td className="px-6 py-3.5 text-xs text-slate-500">
                            {r.updatedAt ? new Date(r.updatedAt).toLocaleString("vi-VN") : "—"}
                          </td>
                          <td className="px-4 py-3.5 text-center">
                            {r.hasJson ? (
                              <Tooltip label={`Tải JSON của ${r.studentId}`} side="left">
                                <button
                                  onClick={() => downloadStudentJson(r)}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                >
                                  <FileJson size={15} />
                                </button>
                              </Tooltip>
                            ) : (
                              <span className="text-slate-300">—</span>
                            )}
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </SidebarLayout>
  );
}

function MiniStat({
  label, value, icon: Icon, tone,
}: {
  label: string; value: number | string; icon: React.ElementType; tone: string;
}) {
  const tones: Record<string, string> = {
    slate: "bg-slate-100 text-slate-500",
    emerald: "bg-emerald-100 text-emerald-600",
    rose: "bg-rose-100 text-rose-600",
    indigo: "bg-indigo-100 text-indigo-600",
  };
  return (
    <div className="card p-5">
      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${tones[tone] || tones.slate}`}>
          <Icon size={16} />
        </span>
      </div>
      <p className="text-3xl font-bold tracking-tight text-slate-800">{value}</p>
    </div>
  );
}
