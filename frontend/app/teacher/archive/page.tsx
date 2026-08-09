"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { useAuth } from "@/components/auth/AuthProvider";
import { API_BASE } from "@/lib/config";
import { getToken } from "@/lib/auth";
import {
  Archive, Eye, RotateCcw, Trash2, Loader2, AlertTriangle, CheckCircle2,
  Info, FileCode, X, Database, Download, FileText, FileArchive, Lightbulb, Printer,
} from "lucide-react";
import { openExamPdf } from "@/lib/exam-pdf";

interface ExamRow {
  examId: string;
  examName?: string;
  status?: string;
  hasTestcase?: boolean;
  hasDeBai?: boolean;
  hasStarter?: boolean;
  hasSolution?: boolean;
  resultCount?: number;
}
interface CodeFile { name: string; content: string; }
interface RegradeState { examId: string; batchId: string; total: number; done: number; error: number; running: boolean; }

function authHeaders(init?: HeadersInit) {
  const headers = new Headers(init);
  const token = getToken();
  if (token && !headers.has("Authorization")) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}

async function api(path: string, method: string, body?: unknown) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

export default function ArchivePage() {
  const { teacher, loading: authLoading } = useAuth();
  const [exams, setExams] = useState<ExamRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  // Modal xem đề: mặc định hiện ĐỀ BÀI; tab phụ hiển thị ba file testcase.
  const [viewExam, setViewExam] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<"debai" | "files">("debai");
  const [deBai, setDeBai] = useState<string | null>(null);
  const [deBaiMissing, setDeBaiMissing] = useState(false);
  const [files, setFiles] = useState<CodeFile[]>([]);
  const [activeFile, setActiveFile] = useState(0);
  const [viewLoading, setViewLoading] = useState(false);

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
    if (authLoading) return;
    if (!teacher) { setLoading(false); return; }
    try {
      const res = await fetch(`${API_BASE}/exam-setup/list`, { headers: authHeaders() });
      const d = await res.json().catch(() => []);
      if (!res.ok) throw new Error(d.error || "Không tải được danh sách đề.");
      setExams(Array.isArray(d) ? d : []);
    } catch (e) {
      setErr((e as Error).message || "Không tải được danh sách đề.");
    } finally {
      setLoading(false);
    }
  }, [authLoading, teacher]);

  useEffect(() => { load(); return stopPoll; }, [load]);

  // Khóa cuộn nền khi mở modal
  useEffect(() => {
    if (!viewExam) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, [viewExam]);

  // Mở modal xem đề: ưu tiên ĐỀ BÀI; đề upload ở Cấu hình Đề thi thì mở thẳng testcase.
  const openView = (examId: string, hasDeBai?: boolean, hasTestcase?: boolean) => {
    setViewExam(examId); setDeBai(null); setDeBaiMissing(false);
    setFiles([]); setActiveFile(0);
    if (hasDeBai) { setViewMode("debai"); loadDeBai(examId); }
    else if (hasTestcase) { setViewMode("files"); loadFiles(examId); }
    else { setViewMode("files"); }
  };

  // Đọc ĐỀ BÀI (de_bai.md) để hiển thị — tái dùng endpoint tải, bỏ BOM nếu có.
  const loadDeBai = async (examId: string) => {
    setViewLoading(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId)}/download/de-bai`, {
        headers: authHeaders(),
      });
      if (res.ok) {
        const txt = await res.text();
        setDeBai(txt.charCodeAt(0) === 0xFEFF ? txt.slice(1) : txt);   // bỏ BOM (U+FEFF) nếu có
      } else setDeBaiMissing(true);
    } catch { setDeBaiMissing(true); }
    finally { setViewLoading(false); }
  };

  // Đọc ba file testcase công khai — tải khi cần.
  const loadFiles = async (examId: string) => {
    setViewLoading(true);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId)}/testcase`, {
        headers: authHeaders(),
      });
      const d = await res.json().catch(() => []);
      if (!res.ok) throw new Error();
      setFiles(Array.isArray(d) ? d : []);
    } catch { setFiles([]); }
    finally { setViewLoading(false); }
  };

  const showDeBai = () => { setViewMode("debai"); if (deBai === null && !deBaiMissing) loadDeBai(viewExam!); };
  const showFiles = () => { setViewMode("files"); if (files.length === 0) loadFiles(viewExam!); };

  const doRegrade = async (examId: string) => {
    setErr(null); setMsg(null);
    if (!teacher) { setErr("Cần đăng nhập để chấm lại."); return; }
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

  // Tải file của đề (đề bài .md / exam_test .zip / starter .zip) — backend gắn sẵn tên file.
  const doDownload = async (path: string, filename: string) => {
    setErr(null); setMsg(null);
    try {
      const res = await fetch(`${API_BASE}${path}`, { headers: authHeaders() });
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

  // Xuất ĐỀ BÀI ra PDF: tải nội dung de_bai (bỏ BOM nếu có) rồi mở hộp thoại in của trình duyệt (Lưu thành PDF).
  const exportPdf = async (examId: string, examName?: string) => {
    setErr(null); setMsg(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId)}/download/de-bai`, {
        headers: authHeaders(),
      });
      if (!res.ok) { setErr("Không tải được đề bài để xuất PDF."); return; }
      let txt = await res.text();
      if (txt.charCodeAt(0) === 0xFEFF) txt = txt.slice(1);   // bỏ BOM (U+FEFF) nếu có
      openExamPdf({ examId, examName, markdown: txt });
    } catch { setErr("Không xuất được PDF."); }
  };

  const doDelete = async (examId: string) => {
    setErr(null); setMsg(null); setDeleting(examId);
    try {
      await api(`/exam-setup/${encodeURIComponent(examId)}`, "DELETE");
      setConfirmDel(null);
      setMsg(`Đã xóa đề ${examId} (gỡ testcase + ảnh Docker + bài nộp).`);
      load();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setDeleting(null);
    }
  };

  return (
    <SidebarLayout
      title="Kho đề thi"
      subtitle="Lưu trữ đề + exam_test để chấm lại khi cần; xóa đề cũ để giải phóng dung lượng"
      activePath="/teacher/archive"
    >
      <div className="mb-5 flex items-start gap-2.5 rounded-xl border border-indigo-100 bg-indigo-50/60 p-4 text-xs text-indigo-700">
        <Info size={16} className="mt-0.5 shrink-0" />
        <div className="space-y-1">
          <p>Mỗi đề lưu đúng ba file <span className="font-mono">exam_test.dart</span>, <span className="font-mono">skills_matrix.json</span> và <span className="font-mono">grader.dart</span> — bấm <b>Xem</b> để đối chiếu.</p>
          <p className="text-indigo-600/80">Nhóm nút <b>Tải</b> cho phép tải <b>Testcase</b> (ZIP ba file), <b>Đề bài</b>, <b>starter</b> và <b>lời giải</b> nếu đề có lưu kèm.</p>
          <p className="text-indigo-600/80"><b>Xóa đề</b> sẽ gỡ testcase + ảnh Docker để giải phóng dung lượng — sau khi xóa sẽ KHÔNG chấm lại được đề đó nữa.</p>
        </div>
      </div>

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
      ) : exams.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
          <Archive size={36} className="mb-3 text-slate-300" />
          <h3 className="mb-1 text-base font-bold text-slate-700">Chưa có đề nào</h3>
          <p className="max-w-sm text-sm text-slate-500">Tải testcase ở trang <b>Cấu hình Đề thi</b> để bắt đầu.</p>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-3.5">
            <Database size={16} className="text-indigo-500" />
            <h3 className="text-sm font-bold text-slate-700">Danh sách đề ({exams.length})</h3>
          </div>
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
                <th className="px-5 py-2.5">Mã đề</th>
                <th className="px-5 py-2.5">Tên đề</th>
                <th className="px-5 py-2.5 text-center">Testcase</th>
                <th className="px-5 py-2.5 text-center">Số bài</th>
                <th className="px-5 py-2.5 text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {exams.map((e) => {
                const busy = regrade?.examId === e.examId && regrade.running;
                const canView = Boolean(e.hasDeBai || e.hasTestcase);
                const viewLabel = e.hasDeBai ? "Xem đề" : "Xem testcase";
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
                      <div className="flex flex-wrap items-center justify-end gap-1.5">
                        {/* Tải về: đề bài · ZIP ba file testcase · starter · lời giải */}
                        <div className="inline-flex items-center overflow-hidden rounded-lg border border-slate-200 bg-white text-[11px] font-semibold">
                          <span className="px-1.5 py-1.5 text-slate-400" title="Tải về"><Download size={12} /></span>
                          <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/de-bai`, `${e.examId}_de_bai.md`)}
                            disabled={!e.hasDeBai} title="Tải đề bài (de_bai.md) phát cho sinh viên"
                            className="flex items-center gap-1 border-l border-slate-200 px-2 py-1.5 text-slate-600 transition-colors hover:bg-indigo-50 hover:text-indigo-600 disabled:opacity-30 disabled:hover:bg-white disabled:hover:text-slate-600">
                            <FileText size={12} /> Đề bài
                          </button>
                          <button onClick={() => exportPdf(e.examId, e.examName)}
                            disabled={!e.hasDeBai} title="Xuất đề bài ra PDF (mở hộp thoại in → chọn Lưu thành PDF)"
                            className="flex items-center gap-1 border-l border-slate-200 px-2 py-1.5 text-slate-600 transition-colors hover:bg-indigo-50 hover:text-indigo-600 disabled:opacity-30 disabled:hover:bg-white disabled:hover:text-slate-600">
                            <Printer size={12} /> PDF
                          </button>
                          <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/exam-test`, `${e.examId}_exam_test.zip`)}
                            disabled={!e.hasTestcase} title="Tải exam_test.dart + grader.dart + skills_matrix.json"
                            className="flex items-center gap-1 border-l border-slate-200 px-2 py-1.5 text-slate-600 transition-colors hover:bg-indigo-50 hover:text-indigo-600 disabled:opacity-30 disabled:hover:bg-white disabled:hover:text-slate-600">
                            <FileArchive size={12} /> Testcase
                          </button>
                          <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/starter`, `${e.examId}_starter.zip`)}
                            disabled={!e.hasStarter} title="Tải khung code (lib/) phát cho sinh viên làm"
                            className="flex items-center gap-1 border-l border-slate-200 px-2 py-1.5 text-slate-600 transition-colors hover:bg-emerald-50 hover:text-emerald-600 disabled:opacity-30 disabled:hover:bg-white disabled:hover:text-slate-600">
                            <FileArchive size={12} /> starter
                          </button>
                          <button onClick={() => doDownload(`/exam-setup/${encodeURIComponent(e.examId)}/download/solution`, `${e.examId}_solution.zip`)}
                            disabled={!e.hasSolution} title="Tải lời giải mẫu (lib/) — chỉ GV tham khảo, KHÔNG phát cho SV"
                            className="flex items-center gap-1 border-l border-slate-200 px-2 py-1.5 text-slate-600 transition-colors hover:bg-amber-50 hover:text-amber-600 disabled:opacity-30 disabled:hover:bg-white disabled:hover:text-slate-600">
                            <Lightbulb size={12} /> lời giải
                          </button>
                        </div>
                        <button onClick={() => openView(e.examId, e.hasDeBai, e.hasTestcase)} title={e.hasDeBai ? "Xem đề bài và testcase" : "Xem testcase của đề"}
                          disabled={!canView}
                          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:text-indigo-600 disabled:opacity-40">
                          <Eye size={13} /> {viewLabel}
                        </button>
                        <button onClick={() => doRegrade(e.examId)} title="Chấm lại toàn bộ bài đã nộp"
                          disabled={!teacher || busy || (e.resultCount ?? 0) === 0}
                          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:text-blue-600 disabled:opacity-40">
                          {busy ? <Loader2 size={13} className="animate-spin" /> : <RotateCcw size={13} />} Chấm lại
                        </button>
                        <button onClick={() => setConfirmDel(e.examId)} title="Xóa đề (giải phóng dung lượng)"
                          disabled={!teacher || busy}
                          className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-rose-50 hover:text-rose-600 disabled:opacity-40">
                          <Trash2 size={14} />
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
      )}

      {/* Modal xem đề — mặc định ĐỀ BÀI; đổi tab để xem ba file testcase. */}
      {mounted && viewExam && createPortal(
        <div className="animate-modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-sm" onClick={() => setViewExam(null)}>
          <div className="animate-modal-pop flex max-h-[85vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl ring-1 ring-black/5" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3.5">
              <h3 className="flex items-center gap-2 text-sm font-bold text-slate-700">
                <FileText size={16} className="text-indigo-500" /> Đề <span className="font-mono">{viewExam}</span>
              </h3>
              <button onClick={() => setViewExam(null)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"><X size={18} /></button>
            </div>

            {/* Tab: Đề bài | ba file testcase */}
            <div className="flex shrink-0 items-center gap-1 border-b border-slate-100 bg-slate-50/60 px-3 py-2">
              <button onClick={showDeBai}
                className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${viewMode === "debai" ? "bg-indigo-50 text-indigo-700" : "text-slate-500 hover:bg-slate-100"}`}>
                <FileText size={13} /> Đề bài
              </button>
              <button onClick={showFiles}
                className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${viewMode === "files" ? "bg-indigo-50 text-indigo-700" : "text-slate-500 hover:bg-slate-100"}`}>
                <FileCode size={13} /> Testcase (3 file)
              </button>
            </div>

            {viewLoading ? (
              <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={22} className="animate-spin" /></div>
            ) : viewMode === "debai" ? (
              deBai ? (
                <pre className="custom-scrollbar flex-1 overflow-auto whitespace-pre-wrap p-5 text-sm leading-relaxed text-slate-700">{deBai}</pre>
              ) : (
                <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 py-16 text-center">
                  <FileText size={30} className="text-slate-300" />
                  <p className="max-w-sm text-sm text-slate-500">Đề này chưa có <b>đề bài</b> (de_bai.md). Bạn vẫn có thể xem ba file testcase của đề.</p>
                  <button onClick={showFiles} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:text-indigo-600">
                    <FileCode size={13} /> Xem 3 file testcase
                  </button>
                </div>
              )
            ) : files.length === 0 ? (
              <div className="py-16 text-center text-sm text-slate-500">Không đọc được file testcase của đề này.</div>
            ) : (
              <>
                <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-slate-100 bg-slate-50/60 px-3 py-2">
                  {files.map((f, i) => (
                    <button key={f.name} onClick={() => setActiveFile(i)}
                      className={`shrink-0 rounded-md px-2.5 py-1 font-mono text-xs transition-colors ${i === activeFile ? "bg-indigo-50 text-indigo-700" : "text-slate-500 hover:bg-slate-100"}`}>
                      {f.name}
                    </button>
                  ))}
                </div>
                <pre className="custom-scrollbar flex-1 overflow-auto whitespace-pre-wrap bg-slate-900 p-4 text-[11px] leading-relaxed text-slate-100">
                  {files[activeFile]?.content}
                </pre>
              </>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* Xác nhận xóa — portal ra <body> */}
      {mounted && confirmDel && createPortal(
        <div className="animate-modal-overlay fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-sm" onClick={() => setConfirmDel(null)}>
          <div className="animate-modal-pop w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl ring-1 ring-black/5" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 flex items-center gap-2.5 text-rose-600">
              <AlertTriangle size={20} /> <h3 className="text-base font-bold">Xóa đề {confirmDel}?</h3>
            </div>
            <p className="mb-5 text-sm text-slate-600">
              Sẽ gỡ <b>testcase + ảnh Docker + toàn bộ bài nộp (submissions)</b> của đề để giải phóng dung lượng. <b>Sau khi xóa KHÔNG chấm lại / xem mã nguồn bài nộp được nữa.</b> Điểm đã chấm vẫn lưu ở Lịch sử &amp; Thống kê.
            </p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setConfirmDel(null)} className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">Hủy</button>
              <button onClick={() => doDelete(confirmDel)} disabled={deleting === confirmDel}
                className="flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 disabled:opacity-50">
                {deleting === confirmDel ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />} Xóa đề
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </SidebarLayout>
  );
}
