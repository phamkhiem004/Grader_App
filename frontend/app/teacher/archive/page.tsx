"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Archive, RotateCcw, Trash2, Loader2, AlertTriangle, CheckCircle2,
  Database, FileArchive, Pencil, Plus,
} from "lucide-react";
import ErrorScreen from "@/components/ui/ErrorScreen";
import { appError, kindOf, messageOf } from "@/lib/errors";

interface ExamRow {
  examId: string;
  examName?: string;
  status?: string;
  hasTestcase?: boolean;
  resultCount?: number;
  /** true = bộ dựng từ template nên mở lại sửa được; false = bộ upload ZIP, không có config. */
  editable?: boolean;
}
interface RegradeState { examId: string; batchId: string; total: number; done: number; error: number; running: boolean; }

// Nút hành động chính của trang: tạo bộ testcase mới.
const newBtnCls = "inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-colors hover:bg-emerald-700";

// Kiểu chung cho mọi nút trong cột Thao tác — để chúng cùng cao, cùng cỡ chữ, ngang hàng nhau.
const btnCls = (accent: string) =>
  `inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition-colors disabled:opacity-40 disabled:hover:bg-white disabled:hover:text-slate-600 ${accent}`;

async function api(path: string, method: string, body?: unknown) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

export default function ArchivePage() {
  const [exams, setExams] = useState<ExamRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);          // lỗi của một thao tác → banner
  const [loadErr, setLoadErr] = useState<unknown>(null);        // lỗi tải danh sách → màn lỗi
  const [msg, setMsg] = useState<string | null>(null);

  // Xác nhận xóa
  const [confirmDel, setConfirmDel] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);

  // Portal modal ra <body> (tránh bị containing-block của .animate-fade-in-up cắt overlay)
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Chấm lại cả đề (poll tiến độ)
  const [regrade, setRegrade] = useState<RegradeState | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const stopPoll = () => { if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; } };

  const load = useCallback(async () => {
    setLoadErr(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/list`);
      const d = await res.json().catch(() => []);
      if (!res.ok) throw appError(d, res.status);
      setExams(Array.isArray(d) ? d : []);
    } catch (e) {
      // Tải danh sách hỏng = trang không còn gì để hiện → màn lỗi, không phải banner.
      setLoadErr(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); return stopPoll; }, [load]);

  const doRegrade = async (examId: string) => {
    setErr(null); setMsg(null);
    try {
      const d = await api(`/batch/regrade-exam/${encodeURIComponent(examId)}`, "POST");
      const skipped = Array.isArray(d.skipped) ? d.skipped.length : 0;
      setRegrade({ examId, batchId: d.batchId, total: d.queued || 0, done: 0, error: 0, running: true });
      if (skipped) setMsg(`Bỏ qua ${skipped} bài (mất file bài nộp/testcase).`);
      startPoll(d.batchId, examId);
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  const startPoll = (batchId: string, examId: string) => {
    stopPoll();
    pollRef.current = setInterval(async () => {
      try {
        const s = await fetch(`${API_BASE}/batch/progress/${batchId}`).then((r) => r.json());
        const done = s.done || 0, error = s.error || 0, total = s.total || 0;
        const running = (s.queued || 0) + (s.grading || 0) > 0;
        setRegrade({ examId, batchId, total, done, error, running });
        if (!running) { stopPoll(); load(); }
      } catch { /* giữ trạng thái */ }
    }, 3000);
  };

  // Tải file của đề (exam_test .zip / starter .zip / solution .zip) — backend gắn sẵn tên file.
  const doDownload = async (path: string, filename: string) => {
    setErr(null); setMsg(null);
    try {
      const res = await fetch(`${API_BASE}${path}`);
      if (!res.ok) {
        const d = await res.json().catch(() => ({}));
        setErr(d.error || "Không tải được file.");
        return;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = filename; a.click();
      URL.revokeObjectURL(url);
    } catch {
      setErr("Không tải được file.");
    }
  };

  const doDelete = async (examId: string) => {
    setErr(null); setMsg(null); setDeleting(examId);
    try {
      await api(`/exam-setup/${encodeURIComponent(examId)}`, "DELETE");
      setConfirmDel(null);
      setMsg(`Đã xóa bộ testcase ${examId} (gỡ testcase + ảnh Docker + bài nộp).`);
      load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setDeleting(null);
    }
  };

  return (
    <SidebarLayout
      title="Kho bộ testcase"
      subtitle="Lưu trữ bộ testcase + exam_test để chấm lại khi cần; xóa bộ testcase cũ để giải phóng dung lượng"
      activePath="/teacher/archive"
    >
      {err && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-rose-100 bg-rose-50 p-3 text-sm text-rose-600">
          <AlertTriangle size={15} /> {err}
        </div>
      )}
      {msg && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-emerald-100 bg-emerald-50 p-3 text-sm text-emerald-700">
          <CheckCircle2 size={15} /> {msg}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>
      ) : loadErr ? (
        <ErrorScreen kind={kindOf(loadErr)} detail={messageOf(loadErr)} onRetry={() => { setLoading(true); load(); }} />
      ) : exams.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
          <Archive size={36} className="mb-3 text-slate-300" />
          <h3 className="mb-1 text-base font-bold text-slate-700">Chưa có bộ testcase nào</h3>
          <p className="mb-4 max-w-sm text-sm text-slate-500">Tạo bộ mới từ thư viện testcase, hoặc tải gói ZIP lên ở trang <b>Cấu hình bộ testcase</b>.</p>
          <Link href="/teacher/testcases" className={newBtnCls}>
            <Plus size={15} /> Tạo testcase
          </Link>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-3.5">
            <Database size={16} className="text-indigo-500" />
            <h3 className="text-sm font-bold text-slate-700">Danh sách bộ testcase ({exams.length})</h3>
            <Link href="/teacher/testcases" className={`ml-auto ${newBtnCls}`}>
              <Plus size={15} /> Tạo testcase
            </Link>
          </div>
          {/* overflow-x-auto: hàng nút nằm trên một dòng nên bảng có thể rộng hơn khung ở màn hẹp */}
          <div className="custom-scrollbar overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
                <th className="px-5 py-2.5">Mã bộ testcase</th>
                <th className="px-5 py-2.5">Tên bộ testcase</th>
                <th className="px-5 py-2.5 text-center">Testcase</th>
                <th className="px-5 py-2.5 text-center">Số bài</th>
                <th className="px-5 py-2.5 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {exams.map((e) => {
                const busy = regrade?.examId === e.examId && regrade.running;
                return (
                  <tr key={e.examId} className="hover:bg-slate-50/60">
                    <td className="px-5 py-3 font-mono font-medium text-slate-700">{e.examId}</td>
                    <td className="px-5 py-3 text-slate-600">{e.examName || "—"}</td>
                    <td className="px-5 py-3 text-center">
                      {e.hasTestcase ? (
                        <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700"><CheckCircle2 size={10} /> có</span>
                      ) : (
                        <span className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-400">thiếu</span>
                      )}
                    </td>
                    <td className="px-5 py-3 text-center">
                      <span className="font-mono text-xs text-slate-600">{e.resultCount ?? 0}</span>
                    </td>
                    <td className="px-5 py-3">
                      {/* Mọi nút cùng một kiểu, nằm ngang hàng trên một dòng (không bọc nhóm) */}
                      <div className="flex items-center justify-end gap-1.5 whitespace-nowrap">
                        <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/exam-test`, `${e.examId}_exam_test.zip`)}
                          disabled={!e.hasTestcase} title="Tải testcase: exam_test.dart + grader.dart + skills_matrix.json"
                          className={btnCls("hover:text-indigo-600")}>
                          <FileArchive size={13} /> Testcase
                        </button>
                        {/* Chỉ bộ dựng từ template mới mở lại builder được; bộ upload ZIP không có config. */}
                        {e.editable !== false ? (
                          <Link href={`/teacher/testcases?exam=${encodeURIComponent(e.examId)}`}
                            title="Mở lại builder để thêm/bớt/xóa testcase trong bộ này"
                            className={btnCls("hover:text-indigo-600")}>
                            <Pencil size={13} /> Sửa
                          </Link>
                        ) : (
                          <button disabled title="Bộ này tải lên bằng ZIP nên không mở lại được — upload gói mới ở trang Cấu hình bộ testcase"
                            className={btnCls("")}>
                            <Pencil size={13} /> Sửa
                          </button>
                        )}
                        <button onClick={() => doRegrade(e.examId)} title="Chấm lại toàn bộ bài đã nộp"
                          disabled={busy || (e.resultCount ?? 0) === 0} className={btnCls("hover:text-blue-600")}>
                          {busy ? <Loader2 size={13} className="animate-spin" /> : <RotateCcw size={13} />} Chấm lại
                        </button>
                        <button onClick={() => setConfirmDel(e.examId)} title="Xóa bộ testcase (giải phóng dung lượng)"
                          disabled={busy} className={btnCls("text-rose-500 hover:bg-rose-50 hover:text-rose-600")}>
                          <Trash2 size={13} /> Xóa
                        </button>
                      </div>
                      {regrade?.examId === e.examId && (
                        <p className="mt-1 text-right text-[11px] text-blue-600">
                          {regrade.running
                            ? `Đang chấm lại ${regrade.done + regrade.error}/${regrade.total}...`
                            : `✓ Xong ${regrade.done}/${regrade.total}${regrade.error ? `, ${regrade.error} lỗi` : ""}`}
                        </p>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
        </div>
      )}

      {/* Xác nhận xóa — portal ra <body> */}
      {mounted && confirmDel && createPortal(
        <div className="animate-modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-sm" onClick={() => setConfirmDel(null)}>
          <div className="animate-modal-pop w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl ring-1 ring-black/5" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 flex items-center gap-2.5 text-rose-600">
              <AlertTriangle size={20} /> <h3 className="text-base font-bold">Xóa bộ testcase {confirmDel}?</h3>
            </div>
            <p className="mb-5 text-sm text-slate-600">
              Sẽ gỡ <b>testcase + ảnh Docker + toàn bộ bài nộp (submissions)</b> của bộ testcase để giải phóng dung lượng. <b>Sau khi xóa KHÔNG chấm lại / xem mã nguồn bài nộp được nữa.</b> Điểm đã chấm vẫn lưu ở Lịch sử &amp; Thống kê.
            </p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setConfirmDel(null)} className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">Hủy</button>
              <button onClick={() => doDelete(confirmDel)} disabled={deleting === confirmDel}
                className="flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 disabled:opacity-50">
                {deleting === confirmDel ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />} Xóa bộ testcase
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </SidebarLayout>
  );
}
