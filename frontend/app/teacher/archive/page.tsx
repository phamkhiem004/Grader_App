"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import { useRouter } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Archive, RotateCcw, Trash2, Loader2, AlertTriangle, CheckCircle2,
  Database, FileArchive, Pencil, Plus, Hammer, X, UploadCloud, Package, ArrowLeft,
  Copy, ChevronLeft, ChevronRight,
} from "lucide-react";
import ErrorScreen from "@/components/ui/ErrorScreen";
import { appError, kindOf, messageOf } from "@/lib/errors";

interface ExamRow {
  examId: string;
  examName?: string;
  status?: string;
  testcaseStatus?: string;
  hasTestcase?: boolean;
  resultCount?: number;
  teacherNote?: string;
  /** true = bộ dựng từ template nên mở lại sửa được; false = bộ upload ZIP, không có config. */
  editable?: boolean;
}
interface RegradeState { examId: string; batchId: string; total: number; done: number; error: number; running: boolean; }
interface SandboxNotice { type: "ok" | "error"; title: string; text: string; }
/** "manual" = màn nhập ZIP có sẵn (trên UI gọi là "Tạo bộ testcase sẵn có", khớp tên API import-manual-testcase). */
type CreatePanel = "choose" | "manual" | null;
const PAGE_SIZES = [10, 20, 50] as const;

function manualNameFromFile(file: File | null) {
  return file ? file.name.replace(/\.zip$/i, "").trim() : "";
}

function manualIdFromName(name: string) {
  return name.normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[đĐ]/g, (value) => value === "đ" ? "d" : "D")
    .toUpperCase()
    .replace(/[^A-Z0-9_-]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^[_-]+|[_-]+$/g, "")
    .slice(0, 60)
    .replace(/[_-]+$/g, "");
}

// Nút hành động chính của trang: tạo bộ testcase mới.
const newBtnCls = "inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-colors hover:bg-emerald-700";

// Kiểu chung cho mọi nút trong cột Thao tác: ô vuông chỉ chứa icon → 6 nút vừa một hàng.
const btnCls = (accent: string) =>
  `inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600 transition-colors disabled:opacity-40 disabled:hover:bg-white disabled:hover:text-slate-600 ${accent}`;

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
  const router = useRouter();
  const [exams, setExams] = useState<ExamRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);          // lỗi của một thao tác → banner
  const [loadErr, setLoadErr] = useState<unknown>(null);        // lỗi tải danh sách → màn lỗi
  const [msg, setMsg] = useState<string | null>(null);

  // Xác nhận xóa
  const [confirmDel, setConfirmDel] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [buildingSandbox, setBuildingSandbox] = useState<string | null>(null);
  const [sandboxNotice, setSandboxNotice] = useState<SandboxNotice | null>(null);
  const [createPanel, setCreatePanel] = useState<CreatePanel>(null);
  const [manualFile, setManualFile] = useState<File | null>(null);
  const [manualDescription, setManualDescription] = useState("");
  const [manualError, setManualError] = useState<string | null>(null);
  const [manualUploading, setManualUploading] = useState(false);
  const [manualDragging, setManualDragging] = useState(false);
  const manualFileRef = useRef<HTMLInputElement | null>(null);
  const [pageSize, setPageSize] = useState<(typeof PAGE_SIZES)[number]>(10);
  const [page, setPage] = useState(1);
  const [cloneSource, setCloneSource] = useState<ExamRow | null>(null);
  const [cloneExamId, setCloneExamId] = useState("");
  const [cloneExamName, setCloneExamName] = useState("");
  const [cloneNote, setCloneNote] = useState("");
  const [cloneError, setCloneError] = useState<string | null>(null);
  const [cloning, setCloning] = useState(false);
  /** Mã bộ vừa clone xong, đang điều hướng sang builder — chỉ để đổi chữ trên nút. */
  const [cloneRedirect, setCloneRedirect] = useState<string | null>(null);

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

  const totalPages = Math.max(1, Math.ceil(exams.length / pageSize));
  const pageStart = exams.length ? (page - 1) * pageSize : 0;
  const pageExams = exams.slice(pageStart, pageStart + pageSize);
  useEffect(() => {
    setPage((current) => Math.min(Math.max(1, current), totalPages));
  }, [totalPages]);

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

  const buildSandbox = async (examId: string) => {
    setErr(null); setMsg(null); setBuildingSandbox(examId);
    try {
      const data = await api(`/exam-setup/${encodeURIComponent(examId)}/sandbox`, "POST");
      setSandboxNotice({
        type: "ok",
        title: "Build Sandbox thành công",
        text: `Build Sandbox cho ${examId} thành công — trạng thái ${data.status || "READY"}.`,
      });
      await load();
    } catch (e) {
      setSandboxNotice({
        type: "error",
        title: "Build Sandbox thất bại",
        text: (e as Error).message,
      });
    } finally {
      setBuildingSandbox(null);
    }
  };

  const resetManualForm = () => {
    setManualFile(null);
    setManualDescription("");
    setManualError(null);
    setManualUploading(false);
    setManualDragging(false);
    if (manualFileRef.current) manualFileRef.current.value = "";
  };

  const closeCreatePanel = () => {
    if (manualUploading) return;
    setCreatePanel(null);
    resetManualForm();
  };

  const acceptManualFile = (files: FileList | null) => {
    const file = files?.[0] || null;
    if (!file || !file.name.toLowerCase().endsWith(".zip")) {
      setManualFile(null);
      setManualError("Vui lòng chọn đúng một file ZIP testcase.");
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      setManualFile(null);
      setManualError("File ZIP testcase vượt quá giới hạn 20 MB.");
      return;
    }
    setManualFile(file);
    setManualError(null);
  };

  const importManualTestcase = async () => {
    if (!manualFile) {
      setManualError("Vui lòng chọn file ZIP testcase.");
      return;
    }
    setManualUploading(true);
    setManualError(null);
    const form = new FormData();
    form.append("testcase", manualFile);
    form.append("teacherNote", manualDescription.trim());
    try {
      const res = await fetch(`${API_BASE}/exam-setup/import-manual-testcase`, {
        method: "POST",
        body: form,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không nhập được bộ testcase từ file ZIP.");
      setCreatePanel(null);
      resetManualForm();
      setSandboxNotice({
        type: "ok",
        title: "Tạo bộ testcase thành công",
        text: `Đã giải nén bộ ${data.examId} thành thư mục testcase. Hãy bấm Build Sandbox trước khi chấm.`,
      });
      await load();
    } catch (e) {
      setManualError((e as Error).message);
    } finally {
      setManualUploading(false);
    }
  };

  const openClone = (source: ExamRow) => {
    if (source.editable === false) return;
    const suffix = "_COPY";
    const root = `${source.examId.slice(0, 50 - suffix.length)}${suffix}`;
    let candidate = root;
    let number = 2;
    const used = new Set(exams.map((exam) => exam.examId.toUpperCase()));
    while (used.has(candidate.toUpperCase())) {
      const numberedSuffix = `_COPY_${number++}`;
      candidate = `${source.examId.slice(0, 50 - numberedSuffix.length)}${numberedSuffix}`;
    }
    setCloneSource(source);
    setCloneExamId(candidate);
    setCloneExamName(`${source.examName || source.examId} (Bản sao)`);
    setCloneNote(source.teacherNote || "");
    setCloneError(null);
    setCloneRedirect(null);
  };

  const closeClone = () => {
    if (cloning) return;
    setCloneSource(null);
    setCloneError(null);
  };

  const cloneTestcaseSet = async () => {
    if (!cloneSource) return;
    const normalizedId = cloneExamId.trim().toUpperCase();
    if (!/^[A-Z0-9_-]{1,50}$/.test(normalizedId)) {
      setCloneError("Mã bộ mới chỉ gồm chữ in hoa, số, _ hoặc - và tối đa 50 ký tự.");
      return;
    }
    if (!cloneExamName.trim()) {
      setCloneError("Vui lòng nhập tên bộ testcase bản sao.");
      return;
    }
    setCloning(true);
    setCloneError(null);
    try {
      const data = await api(`/exam-setup/${encodeURIComponent(cloneSource.examId)}/clone`, "POST", {
        exam_id: normalizedId,
        exam_name: cloneExamName.trim(),
        teacher_note: cloneNote.trim(),
      });
      // Bản sao được lưu chính thức ngay để có thể Build Sandbox. Đồng thời mở
      // thẳng builder của bộ mới để người dùng chỉnh tiếp khi cần.
      const newExamId = String(data.exam_id || normalizedId);
      setCloneRedirect(newExamId);
      router.push(`/teacher/testcases?exam=${encodeURIComponent(newExamId)}`);
      // KHÔNG tắt cloning / đóng modal ở đây: giữ nguyên màn "đang mở" cho tới lúc
      // trang builder hiện ra, tránh nháy về danh sách cũ giữa chừng.
    } catch (e) {
      setCloneError((e as Error).message);
      setCloning(false);
    }
  };

  const manualExamName = manualNameFromFile(manualFile);
  const manualExamId = manualIdFromName(manualExamName);

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
          <p className="mb-4 max-w-sm text-sm text-slate-500">Tạo bộ mới từ thư viện testcase, sau đó Build Sandbox trực tiếp tại trang này.</p>
          <button type="button" onClick={() => setCreatePanel("choose")} className={newBtnCls}>
            <Plus size={15} /> Tạo bộ testcase
          </button>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-3.5">
            <Database size={16} className="text-indigo-500" />
            <h3 className="text-sm font-bold text-slate-700">Danh sách bộ testcase ({exams.length})</h3>
            <button type="button" onClick={() => setCreatePanel("choose")} className={`ml-auto ${newBtnCls}`}>
              <Plus size={15} /> Tạo bộ testcase
            </button>
          </div>
          {/* KHÔNG cuộn ngang: cột Thao tác tự xuống dòng, mã dài tự ngắt → bảng luôn vừa khung. */}
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
              {pageExams.map((e) => {
                const busy = regrade?.examId === e.examId && regrade.running;
                return (
                  <tr key={e.examId} className="hover:bg-slate-50/60">
                    {/* break-all chứ không phải break-words: mã là một chuỗi liền, chỉ break-all mới
                        hạ được min-width của cột — nếu không bảng vẫn đòi rộng và bị cắt (card overflow-hidden). */}
                    <td className="break-all px-5 py-3 font-mono font-medium text-slate-700">{e.examId}</td>
                    <td className="break-words px-5 py-3 text-slate-600">{e.examName || "—"}</td>
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
                      {/* Nút CHỈ CÒN ICON để cả 6 thao tác nằm gọn trên MỘT hàng, không đẩy bảng
                          rộng quá khung. Tên thao tác nằm ở title (tooltip) + aria-label. */}
                      <div className="flex items-center justify-end gap-1.5">
                        <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/exam-test`, `${e.examId}_exam_test.zip`)}
                          disabled={!e.hasTestcase} aria-label="Tải testcase"
                          title="Tải testcase: exam_test.dart + grader.dart + skills_matrix.json"
                          className={btnCls("hover:text-indigo-600")}>
                          <FileArchive size={15} />
                        </button>
                        {/* Chỉ bộ dựng từ template mới mở lại builder được; bộ upload ZIP không có config. */}
                        {e.editable !== false ? (
                          <Link href={`/teacher/testcases?exam=${encodeURIComponent(e.examId)}`}
                            aria-label="Sửa bộ testcase"
                            title="Sửa — mở lại builder để thêm/bớt/xóa testcase trong bộ này"
                            className={btnCls("hover:text-indigo-600")}>
                            <Pencil size={15} />
                          </Link>
                        ) : (
                          <button disabled aria-label="Sửa bộ testcase"
                            title="Sửa — bộ này được tải lên bằng ZIP nên không có cấu hình để mở lại; hãy tạo một bộ mới nếu cần thay đổi"
                            className={btnCls("")}>
                            <Pencil size={15} />
                          </button>
                        )}
                        <button
                          onClick={() => buildSandbox(e.examId)}
                          disabled={!e.hasTestcase || e.testcaseStatus !== "PUBLISHED" || buildingSandbox === e.examId || busy}
                          aria-label="Build Sandbox"
                          title={e.testcaseStatus !== "PUBLISHED"
                            ? "Build Sandbox — hãy mở bộ testcase và bấm Lưu trước"
                            : "Build Sandbox — kiểm tra bộ chấm và chuẩn bị ảnh nền Docker trực tiếp từ thư mục testcase"}
                          className={btnCls("hover:text-emerald-600")}
                        >
                          {buildingSandbox === e.examId
                            ? <Loader2 size={15} className="animate-spin" />
                            : <Hammer size={15} />}
                        </button>
                        <button onClick={() => doRegrade(e.examId)} aria-label="Chấm lại" title="Chấm lại toàn bộ bài đã nộp"
                          disabled={busy || e.status !== "READY" || (e.resultCount ?? 0) === 0} className={btnCls("hover:text-blue-600")}>
                          {busy ? <Loader2 size={15} className="animate-spin" /> : <RotateCcw size={15} />}
                        </button>
                        <button onClick={() => setConfirmDel(e.examId)} aria-label="Xóa bộ testcase"
                          title="Xóa bộ testcase (giải phóng dung lượng)"
                          disabled={busy} className={btnCls("text-rose-500 hover:bg-rose-50 hover:text-rose-600")}>
                          <Trash2 size={15} />
                        </button>
                        {/* Clone luôn là thao tác cuối; backend cũng kiểm tra editable để không thể clone bộ ZIP. */}
                        <button
                          onClick={() => openClone(e)}
                          disabled={busy || e.editable === false}
                          aria-label="Clone đề"
                          title={e.editable === false
                            ? "Clone đề — bộ upload ZIP không có cấu hình builder nên không thể clone"
                            : "Clone đề — tạo bộ mới chứa toàn bộ testcase, contract và khung code của bộ này"}
                          className={btnCls("hover:text-violet-600")}
                        >
                          <Copy size={15} />
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
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
            <div className="flex items-center gap-2 text-xs text-slate-500">
              <span>Hiển thị</span>
              <select
                value={pageSize}
                onChange={(event) => { setPageSize(Number(event.target.value) as (typeof PAGE_SIZES)[number]); setPage(1); }}
                className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 font-semibold text-slate-700 outline-none focus:border-indigo-400"
                aria-label="Số bộ testcase trên một trang"
              >
                {PAGE_SIZES.map((size) => <option key={size} value={size}>{size}</option>)}
              </select>
              <span>bộ/trang · {exams.length ? `${pageStart + 1}–${Math.min(pageStart + pageSize, exams.length)}` : "0"}/{exams.length}</span>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setPage((current) => Math.max(1, current - 1))}
                disabled={page <= 1}
                className={btnCls("hover:text-indigo-600")}
                aria-label="Trang trước"
              ><ChevronLeft size={14} /> Trước</button>
              <span className="min-w-24 text-center text-xs font-semibold text-slate-600">Trang {page}/{totalPages}</span>
              <button
                type="button"
                onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
                disabled={page >= totalPages}
                className={btnCls("hover:text-indigo-600")}
                aria-label="Trang sau"
              >Sau <ChevronRight size={14} /></button>
            </div>
          </div>
        </div>
      )}

      {/* Tạo bộ testcase — chọn nhập ZIP có sẵn hoặc tự dựng bằng builder trên web. */}
      {mounted && createPanel && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-slate-900/65 p-4 backdrop-blur-sm"
          onClick={closeCreatePanel}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-testcase-title"
            className="animate-modal-pop relative w-full max-w-2xl rounded-2xl bg-white p-6 text-slate-800 shadow-2xl ring-1 ring-black/5"
            onClick={(event) => event.stopPropagation()}
          >
            <button
              type="button"
              onClick={closeCreatePanel}
              disabled={manualUploading}
              aria-label="Đóng cửa sổ tạo testcase"
              className="absolute right-4 top-4 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"
            >
              <X size={18} />
            </button>

            {createPanel === "choose" ? (
              <>
                <h3 id="create-testcase-title" className="mb-6 text-xl font-bold">Chọn cách tạo bộ testcase</h3>
                <div className="grid gap-4 md:grid-cols-2">
                  {/* "Sẵn có" = mang ZIP từ máy lên (state/API vẫn mang tên manual — đừng đổi tên endpoint). */}
                  <button
                    type="button"
                    onClick={() => setCreatePanel("manual")}
                    className="group rounded-2xl border border-slate-200 p-5 text-left transition hover:border-indigo-300 hover:bg-indigo-50/60 hover:shadow-md"
                  >
                    <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-xl bg-indigo-100 text-indigo-600">
                      <UploadCloud size={23} />
                    </span>
                    <span className="block text-base font-bold text-slate-800">Tạo bộ testcase sẵn có</span>
                  </button>

                  {/* "Thủ công" = tự dựng từng testcase trong builder trên web. */}
                  <Link
                    href="/teacher/testcases"
                    onClick={closeCreatePanel}
                    className="group rounded-2xl border border-slate-200 p-5 text-left transition hover:border-emerald-300 hover:bg-emerald-50/60 hover:shadow-md"
                  >
                    <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-xl bg-emerald-100 text-emerald-600">
                      <Database size={23} />
                    </span>
                    <span className="block text-base font-bold text-slate-800">Tạo bộ testcase thủ công</span>
                  </Link>
                </div>
              </>
            ) : (
              <>
                <button
                  type="button"
                  onClick={() => { resetManualForm(); setCreatePanel("choose"); }}
                  disabled={manualUploading}
                  className="mb-4 inline-flex items-center gap-1.5 text-sm font-semibold text-slate-500 hover:text-indigo-600 disabled:opacity-40"
                >
                  <ArrowLeft size={16} /> Quay lại chọn cách tạo
                </button>
                <h3 id="create-testcase-title" className="mb-2 text-xl font-bold">Tạo bộ testcase sẵn có</h3>
                <p className="mb-5 text-sm text-slate-500">
                  ZIP phải chứa trực tiếp exam_test.dart, grader.dart và skills_matrix.json. ZIP chỉ dùng để import và không được lưu lại.
                </p>

                <div
                  onClick={() => !manualUploading && manualFileRef.current?.click()}
                  onDragOver={(event) => { event.preventDefault(); if (!manualUploading) setManualDragging(true); }}
                  onDragLeave={() => setManualDragging(false)}
                  onDrop={(event) => {
                    event.preventDefault();
                    setManualDragging(false);
                    if (!manualUploading) acceptManualFile(event.dataTransfer.files);
                  }}
                  className={`mb-5 cursor-pointer rounded-2xl border-2 border-dashed p-6 text-center transition ${
                    manualDragging
                      ? "border-indigo-500 bg-indigo-50"
                      : "border-slate-200 bg-slate-50 hover:border-indigo-300 hover:bg-indigo-50/40"
                  } ${manualUploading ? "pointer-events-none opacity-60" : ""}`}
                >
                  <span className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-white text-indigo-500 shadow-sm">
                    {manualFile ? <Package size={24} /> : <UploadCloud size={24} />}
                  </span>
                  <p className="text-sm font-bold text-slate-700">
                    {manualFile ? manualFile.name : "Kéo thả hoặc bấm để chọn ZIP testcase"}
                  </p>
                  <p className="mt-1 text-xs text-slate-400">Tối đa 20 MB</p>
                  <input
                    ref={manualFileRef}
                    type="file"
                    accept=".zip,application/zip"
                    className="hidden"
                    onChange={(event) => acceptManualFile(event.target.files)}
                  />
                </div>

                <div className="mb-4 grid gap-4 md:grid-cols-2">
                  <label className="text-xs font-bold uppercase tracking-wider text-slate-500">
                    Tên bộ testcase — tự động
                    <input
                      value={manualExamName}
                      readOnly
                      placeholder="Chọn ZIP để hệ thống lấy tên"
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-100 px-3.5 py-3 text-sm font-medium normal-case tracking-normal text-slate-700 outline-none"
                    />
                  </label>
                  <label className="text-xs font-bold uppercase tracking-wider text-slate-500">
                    Mã bộ testcase — tự động
                    <input
                      value={manualExamId}
                      readOnly
                      placeholder="Tự sinh từ tên ZIP"
                      className="mt-1.5 w-full rounded-xl border border-slate-200 bg-slate-100 px-3.5 py-3 font-mono text-sm normal-case tracking-normal text-slate-700 outline-none"
                    />
                  </label>
                </div>

                <label className="mb-4 block text-xs font-bold uppercase tracking-wider text-slate-500">
                  Mô tả bộ testcase <span className="font-normal normal-case text-slate-400">(tùy chọn)</span>
                  <textarea
                    value={manualDescription}
                    onChange={(event) => setManualDescription(event.target.value)}
                    disabled={manualUploading}
                    maxLength={2000}
                    rows={4}
                    placeholder="Nhập mô tả ngắn về yêu cầu hoặc phạm vi chấm của bộ testcase..."
                    className="mt-1.5 w-full resize-none rounded-xl border border-slate-200 bg-white px-3.5 py-3 text-sm font-normal normal-case tracking-normal text-slate-700 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:opacity-60"
                  />
                </label>

                {manualError && (
                  <div className="mb-4 flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
                    <AlertTriangle size={17} className="mt-0.5 shrink-0" /> {manualError}
                  </div>
                )}

                <div className="flex justify-end gap-2">
                  <button
                    type="button"
                    onClick={closeCreatePanel}
                    disabled={manualUploading}
                    className="rounded-lg px-4 py-2.5 text-sm font-semibold text-slate-500 hover:bg-slate-100 disabled:opacity-40"
                  >
                    Hủy
                  </button>
                  <button
                    type="button"
                    onClick={importManualTestcase}
                    disabled={!manualFile || !manualExamId || manualUploading}
                    className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {manualUploading ? <Loader2 size={16} className="animate-spin" /> : <UploadCloud size={16} />}
                    {manualUploading ? "Đang giải nén..." : "Tạo bộ testcase"}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* Clone chỉ dùng cho bộ có config builder; mã và tên mới được nhập độc lập với thao tác Sửa. */}
      {mounted && cloneSource && createPortal(
        <div className="animate-modal-overlay fixed inset-0 z-[58] flex items-center justify-center bg-slate-900/65 p-4 backdrop-blur-sm" onClick={closeClone}>
          <div role="dialog" aria-modal="true" aria-labelledby="clone-title" className="animate-modal-pop relative w-full max-w-xl rounded-2xl bg-white p-6 text-slate-800 shadow-2xl ring-1 ring-black/5" onClick={(event) => event.stopPropagation()}>
            <button type="button" onClick={closeClone} disabled={cloning} aria-label="Đóng cửa sổ clone" className="absolute right-4 top-4 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"><X size={18} /></button>
            <div className="mb-5 flex items-start gap-3">
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-violet-100 text-violet-600"><Copy size={22} /></span>
              <div>
                <h3 id="clone-title" className="text-xl font-bold">Clone bộ testcase</h3>
                <p className="mt-1 text-sm leading-6 text-slate-500">Sao chép toàn bộ testcase, contract, code sinh và tài liệu từ <strong className="font-mono text-slate-700">{cloneSource.examId}</strong>. Kết quả là một bộ mới độc lập, tạo xong sẽ mở luôn trình sửa của bản sao.</p>
              </div>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <label className="text-xs font-bold uppercase tracking-wider text-slate-500">Mã bộ testcase mới
                <input value={cloneExamId} maxLength={50} disabled={cloning} onChange={(event) => { setCloneExamId(event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "")); setCloneError(null); }} className="mt-1.5 w-full rounded-xl border border-slate-200 px-3.5 py-3 font-mono text-sm normal-case tracking-normal outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100" />
              </label>
              <label className="text-xs font-bold uppercase tracking-wider text-slate-500">Tên bộ testcase mới
                <input value={cloneExamName} maxLength={200} disabled={cloning} onChange={(event) => { setCloneExamName(event.target.value); setCloneError(null); }} className="mt-1.5 w-full rounded-xl border border-slate-200 px-3.5 py-3 text-sm font-normal normal-case tracking-normal outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100" />
              </label>
            </div>
            <label className="mt-4 block text-xs font-bold uppercase tracking-wider text-slate-500">Mô tả <span className="font-normal normal-case text-slate-400">(có thể sửa cho bộ mới)</span>
              <textarea value={cloneNote} maxLength={2000} rows={4} disabled={cloning} onChange={(event) => setCloneNote(event.target.value)} className="mt-1.5 w-full resize-none rounded-xl border border-slate-200 px-3.5 py-3 text-sm font-normal normal-case tracking-normal outline-none focus:border-violet-400 focus:ring-2 focus:ring-violet-100" />
            </label>
            {cloneError && <div className="mt-4 flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700"><AlertTriangle size={17} className="mt-0.5 shrink-0" /> {cloneError}</div>}
            <div className="mt-5 flex justify-end gap-2">
              <button type="button" onClick={closeClone} disabled={cloning} className="rounded-lg px-4 py-2.5 text-sm font-semibold text-slate-500 hover:bg-slate-100 disabled:opacity-40">Hủy</button>
              <button type="button" onClick={cloneTestcaseSet} disabled={cloning || !cloneExamId.trim() || !cloneExamName.trim()} className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-50">
                {cloning ? <Loader2 size={16} className="animate-spin" /> : <Copy size={16} />}
                {cloneRedirect ? "Đang mở trình sửa..." : cloning ? "Đang clone..." : "Tạo bản sao"}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Kết quả Build Sandbox — popup giữa viewport, không phụ thuộc vị trí cuộn. */}
      {mounted && sandboxNotice && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-sm"
          onClick={() => setSandboxNotice(null)}
        >
          <div
            role={sandboxNotice.type === "error" ? "alertdialog" : "dialog"}
            aria-modal="true"
            aria-labelledby="sandbox-result-title"
            className="animate-modal-pop relative w-full max-w-md rounded-2xl bg-white p-6 text-slate-800 shadow-2xl ring-1 ring-black/5"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setSandboxNotice(null)}
              aria-label="Đóng thông báo"
              className="absolute right-4 top-4 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
            >
              <X size={18} />
            </button>
            <div className={`mb-4 flex h-12 w-12 items-center justify-center rounded-full ${
              sandboxNotice.type === "ok"
                ? "bg-emerald-100 text-emerald-600"
                : "bg-rose-100 text-rose-600"
            }`}>
              {sandboxNotice.type === "ok"
                ? <CheckCircle2 size={26} />
                : <AlertTriangle size={26} />}
            </div>
            <h3 id="sandbox-result-title" className="mb-2 text-lg font-bold">{sandboxNotice.title}</h3>
            <p className="mb-6 text-sm leading-6 text-slate-600">{sandboxNotice.text}</p>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => setSandboxNotice(null)}
                className={`rounded-lg px-5 py-2.5 text-sm font-semibold text-white ${
                  sandboxNotice.type === "ok"
                    ? "bg-emerald-600 hover:bg-emerald-700"
                    : "bg-rose-600 hover:bg-rose-700"
                }`}
              >
                Đóng
              </button>
            </div>
          </div>
        </div>,
        document.body
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
