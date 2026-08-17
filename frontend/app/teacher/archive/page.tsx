"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import { useRouter } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Archive, RotateCcw, Trash2, Loader2, AlertTriangle, CheckCircle2,
  Database, FileArchive, Pencil, Plus, X, UploadCloud, Package, ArrowLeft,
  Copy, ChevronLeft, ChevronRight, PenLine, Pause, Play, FileText, FileCode2,
} from "lucide-react";
import ErrorScreen from "@/components/ui/ErrorScreen";
import Banner from "@/components/ui/Banner";
import { appError, kindOf, messageOf } from "@/lib/errors";

interface ExamRow {
  examId: string;
  examName?: string;
  /** DRAFT = mới tạo, chưa bấm Lưu · PUBLISHED = đã lưu chính thức, dùng chấm được. */
  testcaseStatus?: string;
  hasTestcase?: boolean;
  /** true = đã có đề bài trong bộ phát cho sinh viên → mở được trang Xem đề. */
  hasDeBai?: boolean;
  /** true = bộ đã kèm khung code starter (handout/starter) để phát cho sinh viên. */
  hasStarter?: boolean;
  resultCount?: number;
  teacherNote?: string;
  /** true = sửa/clone được (có cấu hình builder, hoặc dựng lại được từ file testcase). */
  editable?: boolean;
  /** true = cấu hình được dựng lại từ skills_matrix.json vì bộ này nhập bằng ZIP. */
  configRecovered?: boolean;
  /** true = testcase viết tay: sửa bằng trình sửa file thay vì builder. */
  fileEditable?: boolean;
}
interface TestcaseFile { name: string; content: string }
interface RegradeState {
  examId: string;
  batchId: string;
  total: number;
  done: number;
  error: number;
  manualReview: number;
  grading: number;
  queued: number;
  status: "IN_PROGRESS" | "PAUSED";
}
/** Popup báo kết quả một thao tác nặng (nhập ZIP, đổi tên...) — nổi giữa màn, không phải banner. */
interface Notice { type: "ok" | "error"; title: string; text: string; }
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

// Kiểu chung cho mọi nút trong cột Thao tác: icon + CHỮ để không phải đoán nghĩa biểu tượng.
// Chữ luôn hiện; nhãn giữ ngắn và whitespace-nowrap để cả hàng nút nằm gọn một dòng.
const btnCls = (accent: string) =>
  `inline-flex h-8 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-semibold text-slate-600 transition-colors disabled:opacity-40 disabled:hover:bg-white disabled:hover:text-slate-600 ${accent}`;

// Cột thao tác dùng icon vuông và tooltip để năm hành động luôn nằm trên một dòng.
const actBtnCls = (accent: string) =>
  `${btnCls(accent)} h-9 w-9 !gap-0 !px-0`;

/**
 * Trạng thái bộ testcase. DRAFT sinh ra ngay lúc tạo bộ (kể cả khi người dùng chưa bấm Lưu
 * hoặc app tắt giữa chừng); chỉ khi bấm Lưu backend mới chuyển sang PUBLISHED và cho đem chấm.
 */
function StatusBadge({ row }: { row: ExamRow }) {
  if (!row.hasTestcase)
    return <span className="inline-flex items-center gap-1 rounded bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-400">Chưa có testcase</span>;
  if ((row.testcaseStatus || "DRAFT").toUpperCase() === "PUBLISHED")
    return <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-0.5 text-[10px] font-medium text-emerald-700"><CheckCircle2 size={10} /> Hoàn tất</span>;
  return <span className="inline-flex items-center gap-1 rounded bg-amber-100 px-2 py-0.5 text-[10px] font-medium text-amber-700"><PenLine size={10} /> Nháp</span>;
}

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
  const [notice, setNotice] = useState<Notice | null>(null);
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
  // Trình sửa file cho testcase viết tay (không dựng từ template nên không mở builder được).
  const [fileEditor, setFileEditor] = useState<ExamRow | null>(null);
  const [editorFiles, setEditorFiles] = useState<TestcaseFile[]>([]);
  const [editorActive, setEditorActive] = useState(0);
  const [editorDirty, setEditorDirty] = useState(false);
  const [editorBusy, setEditorBusy] = useState<"load" | "save" | null>(null);
  const [editorError, setEditorError] = useState<string | null>(null);
  const [editorExamId, setEditorExamId] = useState("");
  const [editorExamName, setEditorExamName] = useState("");

  // Portal modal ra <body> (tránh bị containing-block của .animate-fade-in-up cắt overlay)
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  // Trạng thái lấy lại từ backend, không phụ thuộc vòng đời của trang React.
  const [regrades, setRegrades] = useState<Record<string, RegradeState>>({});

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

  const loadActiveRegrades = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/batch/active`);
      const data = await res.json().catch(() => []);
      if (!res.ok || !Array.isArray(data)) return;
      const next: Record<string, RegradeState> = {};
      for (const item of data) {
        const examId = String(item?.examId || "");
        if (!examId || next[examId]) continue; // endpoint trả mới nhất trước
        next[examId] = {
          examId,
          batchId: String(item.batchId || ""),
          total: Number(item.total || 0),
          done: Number(item.done || 0),
          error: Number(item.error || 0),
          manualReview: Number(item.manualReview || 0),
          grading: Number(item.grading || 0),
          queued: Number(item.queued || 0),
          status: item.status === "PAUSED" ? "PAUSED" : "IN_PROGRESS",
        };
      }
      setRegrades(next);
    } catch {
      // Giữ trạng thái gần nhất nếu backend tạm thời không phản hồi.
    }
  }, []);

  useEffect(() => {
    load();
    loadActiveRegrades();
    const poll = setInterval(loadActiveRegrades, 3000);
    return () => clearInterval(poll);
  }, [load, loadActiveRegrades]);

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
      setRegrades((current) => ({
        ...current,
        [examId]: {
          examId,
          batchId: d.batchId,
          total: d.queued || 0,
          done: 0,
          error: 0,
          manualReview: 0,
          grading: 0,
          queued: d.queued || 0,
          status: "IN_PROGRESS",
        },
      }));
      if (skipped) setMsg(`Bỏ qua ${skipped} bài (mất file bài nộp/testcase).`);
      await loadActiveRegrades();
    } catch (e) {
      setErr((e as Error).message);
    }
  };

  const togglePause = async (state: RegradeState) => {
    setErr(null); setMsg(null);
    const action = state.status === "PAUSED" ? "resume" : "pause";
    try {
      await api(`/batch/${encodeURIComponent(state.batchId)}/${action}`, "POST");
      setMsg(action === "pause"
        ? "Đã tạm dừng. Các bài đang chạy sẽ hoàn tất, bài đang chờ chưa được khởi động."
        : "Đã tiếp tục phiên chấm.");
      await loadActiveRegrades();
    } catch (e) {
      setErr((e as Error).message);
    }
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
      const data = await api(`/exam-setup/${encodeURIComponent(examId)}`, "DELETE");
      setConfirmDel(null);
      setMsg(`Đã xóa bộ testcase ${examId}: gỡ testcase + ảnh Docker + bài nộp, `
           + `xóa ${data.resultsRemoved ?? 0} kết quả chấm và ${data.batchesRemoved ?? 0} phiên chấm.`);
      load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setDeleting(null);
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
      setNotice({
        type: "ok",
        title: "Tạo bộ testcase thành công",
        text: `Đã giải nén bộ ${data.examId} thành thư mục testcase và chuẩn bị sẵn môi trường chấm.`,
      });
      await load();
    } catch (e) {
      setManualError((e as Error).message);
    } finally {
      setManualUploading(false);
    }
  };

  // Nhân bản chạy cho CẢ HAI loại bộ: bộ builder clone cấu hình, bộ upload clone nguyên file.
  const openClone = (source: ExamRow) => {
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
      // Bản sao luôn dừng ở trạng thái Nháp — mở thẳng màn sửa để người dùng chỉnh tiếp,
      // bấm Lưu ở đó mới thành Hoàn tất và đem chấm được.
      const newExamId = String(data.exam_id || normalizedId);
      const newExamName = cloneExamName.trim();
      // Bản sao của testcase VIẾT TAY không có cấu hình builder → mở trình sửa file
      // thay vì builder (builder sẽ ra màn trống), nhưng vẫn là "vào thẳng màn sửa".
      if (data.editable === false) {
        setCloneSource(null);
        setCloning(false);
        setMsg(`Đã nhân bản ${cloneSource.examId} → ${newExamId}. Bản sao đang là Nháp — bấm Lưu trong trình sửa để chuyển sang Hoàn tất.`);
        await load();
        await openFileEditor({ examId: newExamId, examName: newExamName, editable: false });
        return;
      }
      setCloneRedirect(newExamId);
      router.push(`/teacher/testcases?exam=${encodeURIComponent(newExamId)}`);
      // KHÔNG tắt cloning / đóng modal ở đây: giữ nguyên màn "đang mở" cho tới lúc
      // trang builder hiện ra, tránh nháy về danh sách cũ giữa chừng.
    } catch (e) {
      setCloneError((e as Error).message);
      setCloning(false);
    }
  };

  // ── Sửa file testcase (bộ viết tay) ─────────────────────────────
  const openFileEditor = async (row: ExamRow) => {
    setFileEditor(row);
    setEditorFiles([]);
    setEditorActive(0);
    setEditorDirty(false);
    setEditorError(null);
    // Đổi tên nằm NGAY TRONG trình sửa: sửa nội dung và sửa mã/tên là cùng một việc
    // "sửa bộ testcase", tách ra hai nút chỉ làm người dùng phải nhớ nút nào làm gì.
    setEditorExamId(row.examId);
    setEditorExamName(row.examName || row.examId);
    setEditorBusy("load");
    try {
      // edit=true: backend trả NGUYÊN VẸN. Bản đọc-để-xem bị cắt ở 200.000 ký tự, lưu lại là mất đuôi file.
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(row.examId)}/testcase?edit=true`);
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không đọc được file testcase.");
      const editable = Array.isArray(data) ? (data as TestcaseFile[]) : [];
      if (!editable.length) throw new Error("Bộ này không có file testcase nào sửa được.");
      setEditorFiles(editable);
    } catch (e) {
      setEditorError((e as Error).message);
    } finally {
      setEditorBusy(null);
    }
  };

  const closeFileEditor = () => {
    if (editorBusy) return;
    if (editorDirty && !confirm("Đóng mà không lưu? Các thay đổi trong file sẽ mất.")) return;
    setFileEditor(null);
    setEditorFiles([]);
    setEditorDirty(false);
    setEditorError(null);
  };

  const saveFileEditor = async () => {
    if (!fileEditor) return;
    const nextId = editorExamId.trim().toUpperCase();
    const nextName = editorExamName.trim();
    if (!/^[A-Z0-9_-]{1,50}$/.test(nextId)) {
      setEditorError("Mã bộ chỉ gồm chữ in hoa, số, _ hoặc - và tối đa 50 ký tự.");
      return;
    }
    if (!nextName) {
      setEditorError("Tên bộ testcase không được để trống.");
      return;
    }
    const renaming = nextId !== fileEditor.examId || nextName !== (fileEditor.examName || fileEditor.examId);

    setEditorBusy("save");
    setEditorError(null);
    try {
      // Ghi nội dung TRƯỚC rồi mới đổi mã: đổi mã làm thư mục đổi tên theo, ghi file sau đó
      // sẽ trỏ vào đường dẫn cũ đã biến mất.
      // Luôn ghi, kể cả khi chưa sửa chữ nào: bấm Lưu là chốt bản chính thức — bộ vừa clone
      // đang ở Nháp phải chuyển sang Hoàn tất và được dựng sandbox thì mới chấm được.
      const data = await api(`/exam-setup/${encodeURIComponent(fileEditor.examId)}/testcase`, "POST", {
        files: editorFiles,
      });
      const savedCount = (data.files as string[] | undefined)?.length ?? editorFiles.length;
      const saveWarning = typeof data.warning === "string" ? data.warning : "";
      if (renaming) {
        await api(`/exam-setup/${encodeURIComponent(fileEditor.examId)}/rename`, "POST", {
          new_exam_id: nextId,
          exam_name: nextName,
        });
      }
      setEditorDirty(false);
      setFileEditor(null);
      const done = renaming
        ? `Đã lưu bộ testcase và đổi thành ${nextId} — ${nextName}. Trạng thái: Hoàn tất.`
        : `Đã lưu ${savedCount} file của bộ ${fileEditor.examId} — trạng thái Hoàn tất. Bản cũ được giữ trong testcase-archive để đối chiếu.`;
      setMsg(saveWarning ? `${done} ${saveWarning}` : done);
      await load();
    } catch (e) {
      setEditorError((e as Error).message);
    } finally {
      setEditorBusy(null);
    }
  };

  const manualExamName = manualNameFromFile(manualFile);
  const manualExamId = manualIdFromName(manualExamName);

  return (
    <SidebarLayout
      title="Kho bộ testcase"
      activePath="/teacher/archive"
      // Rộng hơn max-w-6xl mặc định để bảng 5 nút vừa khít, nhưng vẫn có trần: bỏ trần hẳn
      // thì màn lớn kéo cột Tên dài lê thê và hàng nút trôi xa khỏi phần dữ liệu.
      contentClassName="max-w-7xl"
    >
      {err && (
        <Banner tone="error" onClose={() => setErr(null)}>{err}</Banner>
      )}
      {msg && (
        <Banner tone="ok" onClose={() => setMsg(null)}>{msg}</Banner>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>
      ) : loadErr ? (
        <ErrorScreen kind={kindOf(loadErr)} detail={messageOf(loadErr)} onRetry={() => { setLoading(true); load(); }} />
      ) : exams.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
          <Archive size={36} className="mb-3 text-slate-300" />
          <h3 className="mb-1 text-base font-bold text-slate-700">Chưa có bộ testcase nào</h3>
          <p className="mb-4 max-w-sm text-sm text-slate-500">Tạo bộ mới từ thư viện testcase hoặc nhập ZIP có sẵn — môi trường chấm được chuẩn bị tự động.</p>
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
          {/* Cố định tỷ lệ cột để dữ liệu dài không làm thay đổi bố cục giữa các trang. */}
          <div>
          {/* Các cột trạng thái/thao tác có độ rộng cố định; tên bộ nhận toàn bộ phần còn lại. */}
          <table className="w-full table-fixed text-left text-sm">
            <colgroup>
              <col className="w-[22%]" />
              <col />
              <col className="w-[190px]" />
              <col className="w-[72px]" />
              <col className="w-[260px]" />
            </colgroup>
            <thead>
              <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
                <th className="px-5 py-2.5">Mã bộ testcase</th>
                <th className="px-5 py-2.5">Tên bộ testcase</th>
                <th className="px-5 py-2.5 text-center">Trạng thái bộ testcase</th>
                <th className="px-5 py-2.5 text-center">Số bài</th>
                <th className="px-5 py-2.5 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {pageExams.map((e) => {
                const regrade = regrades[e.examId];
                const busy = Boolean(regrade);
                return (
                  <tr key={e.examId} className="hover:bg-slate-50/60">
                    <td className="px-5 py-3 align-middle">
                      <div title={e.examId} className="truncate font-mono font-medium text-slate-700">{e.examId}</div>
                    </td>
                    <td className="px-5 py-3 align-middle">
                      <div title={e.examName || "—"} className="truncate text-slate-600">{e.examName || "—"}</div>
                    </td>
                    <td className="px-5 py-3 text-center align-middle">
                      <StatusBadge row={e} />
                    </td>
                    <td className="px-5 py-3 text-center align-middle">
                      <span className="font-mono text-xs text-slate-600">{e.resultCount ?? 0}</span>
                    </td>
                    <td className="px-5 py-3 align-middle">
                      {/* Icon + tooltip; đổi tên không còn nút riêng vì đã nằm trong màn Sửa. */}
                      <div className="flex items-center justify-end gap-1.5">
                        <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/exam-test`, `${e.examId}_exam_test.zip`)}
                          disabled={!e.hasTestcase}
                          title="Tải testcase: exam_test.dart + grader.dart + skills_matrix.json + contract.json"
                          className={actBtnCls("hover:text-indigo-600")}>
                          <FileArchive size={16} /><span className="sr-only">Tải testcase</span>
                        </button>
                        {/* Khung starter phát cho SV — bộ nào chưa kèm thì nút mờ đi, bấm vào cũng
                            chỉ nhận 404, nói trước bằng tooltip đỡ mất công. */}
                        <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/starter`, `${e.examId}_starter.zip`)}
                          disabled={!e.hasStarter}
                          title={e.hasStarter
                            ? "Tải khung code starter (lib/…) phát cho sinh viên"
                            : "Tải khung starter — bộ này chưa kèm khung starter"}
                          className={actBtnCls("hover:text-violet-600")}>
                          <FileCode2 size={16} /><span className="sr-only">Tải khung starter</span>
                        </button>
                        {/* Xem đề: đề bài + hình minh họa gộp một tài liệu, tải được .docx. */}
                        <Link href={`/teacher/exam-view?exam=${encodeURIComponent(e.examId)}`}
                          title={e.hasDeBai
                            ? "Xem đề — đề bài kèm hình minh họa, tải được bản .docx"
                            : "Xem đề — bộ này chưa có đề bài"}
                          className={actBtnCls(e.hasDeBai ? "hover:text-emerald-600" : "opacity-40")}>
                          <FileText size={16} /><span className="sr-only">Xem đề</span>
                        </Link>
                        {/* MỘT nút Sửa duy nhất mỗi hàng. Bộ dựng từ template mở builder (kéo thêm
                            testcase từ thư viện); bộ testcase viết tay không có cấu hình builder để
                            mở nên nút đó phải là trình sửa file, nếu không chúng thành không sửa được. */}
                        {e.editable !== false ? (
                          <Link href={`/teacher/testcases?exam=${encodeURIComponent(e.examId)}`}
                            title={e.configRecovered
                              ? "Sửa — cấu hình được dựng lại từ file testcase đã nhập"
                              : "Sửa — đổi mã/tên, kéo thêm testcase từ thư viện, sửa tham số và hợp đồng"}
                            className={actBtnCls("hover:text-indigo-600")}>
                            <Pencil size={16} /><span className="sr-only">Sửa</span>
                          </Link>
                        ) : (
                          <button type="button" onClick={() => openFileEditor(e)} disabled={busy}
                            title="Sửa — testcase viết tay: đổi mã/tên và sửa thẳng nội dung file"
                            className={actBtnCls("hover:text-indigo-600")}>
                            <PenLine size={16} /><span className="sr-only">Sửa</span>
                          </button>
                        )}
                        <button
                          onClick={() => regrade ? togglePause(regrade) : doRegrade(e.examId)}
                          title={regrade?.status === "PAUSED"
                            ? "Tiếp tục đưa các bài đang chờ vào máy chấm"
                            : regrade
                              ? "Tạm dừng sau khi các bài đang chạy hoàn tất"
                              : "Chấm lại toàn bộ bài đã nộp"}
                          disabled={!regrade && (e.resultCount ?? 0) === 0}
                          className={actBtnCls("hover:text-blue-600")}
                        >
                          {regrade?.status === "PAUSED"
                            ? <Play size={16} />
                            : regrade
                              ? <Pause size={16} />
                              : <RotateCcw size={16} />}
                          <span className="sr-only">{regrade?.status === "PAUSED" ? "Tiếp tục" : regrade ? "Tạm dừng" : "Chấm lại"}</span>
                        </button>
                        <button onClick={() => setConfirmDel(e.examId)}
                          title="Xóa bộ testcase (giải phóng dung lượng)"
                          disabled={busy} className={actBtnCls("text-rose-500 hover:bg-rose-50 hover:text-rose-600")}>
                          <Trash2 size={16} /><span className="sr-only">Xóa</span>
                        </button>
                        {/* Nhân bản luôn là thao tác cuối; chạy được cho cả hai loại bộ. */}
                        <button onClick={() => openClone(e)} disabled={busy}
                          title="Clone — tạo bộ mới chứa toàn bộ testcase và tài liệu của bộ này"
                          className={actBtnCls("hover:text-violet-600")}>
                          <Copy size={16} /><span className="sr-only">Clone</span>
                        </button>
                      </div>
                      {regrade && (
                        <p className="mt-1 text-right text-[11px] text-blue-600">
                          {regrade.status === "PAUSED"
                            ? `Đã tạm dừng · ${regrade.done + regrade.error + regrade.manualReview}/${regrade.total} đã xử lý`
                            : `Đang chấm ${regrade.done + regrade.error + regrade.manualReview}/${regrade.total} · ${regrade.grading} đang chạy`}
                        </p>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
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
                  ZIP phải chứa trực tiếp exam_test.dart, grader.dart và skills_matrix.json. contract.json là tùy chọn; nếu thiếu, hệ thống mặc định không bắt buộc Widget Key. ZIP chỉ dùng để import và không được lưu lại.
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

      {/* Trình sửa file: chạy cho MỌI bộ. Bộ dựng từ template vẫn sửa tay được, chỉ kèm cảnh báo
          vì lần Lưu kế tiếp bên builder sẽ sinh lại file. */}
      {mounted && fileEditor && createPortal(
        <div className="animate-modal-overlay fixed inset-0 z-[58] flex items-center justify-center bg-slate-900/65 p-4 backdrop-blur-sm" onClick={closeFileEditor}>
          <div role="dialog" aria-modal="true" aria-labelledby="file-editor-title"
            className="animate-modal-pop relative flex h-[85vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white text-slate-800 shadow-2xl ring-1 ring-black/5"
            onClick={(event) => event.stopPropagation()}>
            <div className="flex items-start gap-3 border-b border-slate-100 px-6 py-4">
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-100 text-indigo-600"><PenLine size={22} /></span>
              <div className="min-w-0 flex-1">
                <h3 id="file-editor-title" className="text-xl font-bold">Sửa file testcase</h3>
                <p className="mt-1 text-sm leading-6 text-slate-500">
                  Sửa thẳng nội dung file; bấm Lưu là bộ chuyển sang <b>Hoàn tất</b> và được dựng lại sandbox.
                  Bản cũ được lưu vào <span className="font-mono text-xs">testcase-archive/</span> trước khi ghi đè.
                </p>
                {/* Bộ mở được bằng builder vẫn sửa tay được, nhưng phải biết trước là sẽ bị sinh đè. */}
                {fileEditor.editable !== false && (
                  <p className="mt-1.5 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800">
                    Bộ này cũng mở được bằng builder. Nếu sau này bấm Lưu trong màn “Tạo bộ testcase”, toàn bộ file sẽ được sinh lại và phần sửa tay ở đây sẽ mất.
                  </p>
                )}
              </div>
              <button type="button" onClick={closeFileEditor} disabled={editorBusy !== null} aria-label="Đóng trình sửa file"
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700 disabled:opacity-40"><X size={18} /></button>
            </div>

            {/* Đổi mã/tên nằm ngay trong trình sửa — không cần nút riêng ngoài danh sách. */}
            <div className="grid gap-4 border-b border-slate-100 px-6 py-4 md:grid-cols-2">
              <label className="text-xs font-bold uppercase tracking-wider text-slate-500">Mã bộ testcase
                <input value={editorExamId} maxLength={50} disabled={editorBusy !== null}
                  onChange={(event) => { setEditorExamId(event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "")); setEditorError(null); }}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 px-3.5 py-2.5 font-mono text-sm normal-case tracking-normal outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
              </label>
              <label className="text-xs font-bold uppercase tracking-wider text-slate-500">Tên bộ testcase
                <input value={editorExamName} maxLength={200} disabled={editorBusy !== null}
                  onChange={(event) => { setEditorExamName(event.target.value); setEditorError(null); }}
                  className="mt-1.5 w-full rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-normal normal-case tracking-normal outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
              </label>
            </div>

            {editorBusy === "load" ? (
              <div className="flex flex-1 items-center justify-center gap-2 text-sm text-slate-500">
                <Loader2 size={18} className="animate-spin" /> Đang đọc file testcase…
              </div>
            ) : editorFiles.length === 0 ? (
              <div className="flex flex-1 items-center justify-center px-6 text-center text-sm text-slate-500">
                {editorError || "Không có file nào để sửa."}
              </div>
            ) : (
              <>
                <div className="flex gap-1 overflow-x-auto border-b border-slate-100 px-4 py-2">
                  {editorFiles.map((f, i) => (
                    <button key={f.name} type="button" onClick={() => setEditorActive(i)}
                      className={`shrink-0 rounded-lg px-3 py-1.5 font-mono text-xs transition-colors ${
                        i === editorActive ? "bg-indigo-100 text-indigo-700" : "text-slate-500 hover:bg-slate-100"}`}>
                      {f.name}
                    </button>
                  ))}
                </div>
                <textarea
                  value={editorFiles[editorActive]?.content ?? ""}
                  onChange={(event) => {
                    const content = event.target.value;
                    setEditorFiles((cur) => cur.map((f, i) => (i === editorActive ? { ...f, content } : f)));
                    setEditorDirty(true);
                    setEditorError(null);
                  }}
                  spellCheck={false}
                  className="min-h-0 flex-1 resize-none bg-slate-900 p-4 font-mono text-xs leading-relaxed text-slate-100 outline-none"
                />
              </>
            )}

            {editorError && editorFiles.length > 0 && (
              <div className="flex items-start gap-2 border-t border-rose-100 bg-rose-50 px-6 py-3 text-sm text-rose-700">
                <AlertTriangle size={17} className="mt-0.5 shrink-0" /> {editorError}
              </div>
            )}
            <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-6 py-4">
              {editorDirty && <span className="mr-auto text-xs font-semibold text-amber-600">Có thay đổi chưa lưu</span>}
              <button type="button" onClick={closeFileEditor} disabled={editorBusy !== null}
                className="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-40">Đóng</button>
              <button type="button" onClick={saveFileEditor}
                disabled={editorBusy !== null || editorFiles.length === 0}
                className="flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-40">
                {editorBusy === "save" ? <Loader2 size={16} className="animate-spin" /> : <PenLine size={16} />} Lưu thay đổi
              </button>
            </div>
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
                <p className="mt-1 text-sm leading-6 text-slate-500">Sao chép toàn bộ testcase, contract, code sinh và tài liệu từ <strong className="font-mono text-slate-700">{cloneSource.examId}</strong>. Kết quả là một bộ mới độc lập, tạo xong sẽ mở luôn trình sửa của bản sao. Bản sao ở trạng thái <b>Nháp</b> cho tới khi bấm Lưu.</p>
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

      {/* Kết quả thao tác — popup giữa viewport, không phụ thuộc vị trí cuộn. */}
      {mounted && notice && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-sm"
          onClick={() => setNotice(null)}
        >
          <div
            role={notice.type === "error" ? "alertdialog" : "dialog"}
            aria-modal="true"
            aria-labelledby="notice-title"
            className="animate-modal-pop relative w-full max-w-md rounded-2xl bg-white p-6 text-slate-800 shadow-2xl ring-1 ring-black/5"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              type="button"
              onClick={() => setNotice(null)}
              aria-label="Đóng thông báo"
              className="absolute right-4 top-4 rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
            >
              <X size={18} />
            </button>
            <div className={`mb-4 flex h-12 w-12 items-center justify-center rounded-full ${
              notice.type === "ok"
                ? "bg-emerald-100 text-emerald-600"
                : "bg-rose-100 text-rose-600"
            }`}>
              {notice.type === "ok"
                ? <CheckCircle2 size={26} />
                : <AlertTriangle size={26} />}
            </div>
            <h3 id="notice-title" className="mb-2 text-lg font-bold">{notice.title}</h3>
            <p className="mb-6 text-sm leading-6 text-slate-600">{notice.text}</p>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => setNotice(null)}
                className={`rounded-lg px-5 py-2.5 text-sm font-semibold text-white ${
                  notice.type === "ok"
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
              Sẽ gỡ <b>testcase + ảnh Docker + toàn bộ bài nộp + toàn bộ kết quả đã chấm</b> của bộ testcase này. <b>Không hoàn tác được:</b> điểm sẽ biến mất khỏi Lịch sử &amp; Thống kê, không chấm lại hay xem mã nguồn bài nộp được nữa. Cần lưu lại thì xuất hồ sơ/Excel ở trang Lịch sử chấm TRƯỚC khi xóa.
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
