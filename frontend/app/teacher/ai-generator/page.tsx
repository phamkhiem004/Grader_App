"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { useAuth } from "@/components/auth/AuthProvider";
import { API_BASE } from "@/lib/config";
import { getToken } from "@/lib/auth";
import {
  Sparkles, Wand2, Loader2, AlertTriangle, CheckCircle2, Info, FileText,
  RotateCcw, Save, KeyRound, ChevronRight, XCircle, Clock, Hammer, Bug, Download, FileArchive, StopCircle, Send, X, Printer,
} from "lucide-react";
import { openExamPdf } from "@/lib/exam-pdf";

// ── Kiểu dữ liệu ──────────────────────────────────────────────
interface AiStatus { enabled: boolean; provider: string; model?: string; ready: boolean; configured?: boolean; }
interface Attempt {
  round: number; phase: string; ok?: boolean; summary?: string;
  compiled?: boolean; allPassed?: boolean; passed?: number; total?: number; errorsExcerpt?: string;
}
interface CodeFile { name: string; content: string; }
interface JobResult { assumptions?: string; de_bai?: string; files?: CodeFile[]; starter?: CodeFile[]; solution?: CodeFile[]; }
interface TabFile { label: string; content: string; kind: "test" | "starter" | "sol"; }
interface Job {
  jobId: string; status: string; message?: string; provider?: string; model?: string;
  attempts?: Attempt[]; result?: JobResult;
}

const TERMINAL = ["SUCCESS", "NEEDS_REVIEW", "FAILED", "ERROR", "NO_API_KEY", "CANCELLED"];

// Khoá localStorage lưu jobId đang chạy: rời trang rồi quay lại vẫn khôi phục được tiến trình
// (việc tạo đề chạy SERVER-SIDE theo jobId, không phụ thuộc trang có đang mở hay không).
const ACTIVE_JOB_KEY = "ai_gen_active_job";

async function api(path: string, method: string, body?: unknown) {
  const token = getToken();
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

const DIFFICULTIES = [
  { v: "mixed", label: "Đa dạng (khuyến nghị)" },
  { v: "basic", label: "Chủ yếu Cơ bản" },
  { v: "intermediate", label: "Chủ yếu Trung bình" },
  { v: "advanced", label: "Nhiều Nâng cao" },
];

function downloadText(name: string, content: string) {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = name; a.click();
  URL.revokeObjectURL(url);
}
function copyText(content: string) {
  try { navigator.clipboard?.writeText(content); } catch { /* ignore */ }
}
async function downloadJobZip(jobId: string, kind: string, filename: string) {
  try {
    const res = await fetch(`${API_BASE}/ai-generator/job/${encodeURIComponent(jobId)}/zip?kind=${kind}`);
    if (!res.ok) return;
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = filename; a.click();
    URL.revokeObjectURL(url);
  } catch { /* ignore */ }
}
function baseName(p: string) { const s = p.split("/"); return s[s.length - 1] || p; }
function kindActive(kind: string) {
  if (kind === "starter") return "bg-emerald-100 text-emerald-700";
  if (kind === "sol") return "bg-slate-200 text-slate-600";
  return "bg-indigo-100 text-indigo-700";
}

// Badge trạng thái job
function statusBadge(status: string) {
  switch (status) {
    case "SUCCESS":      return { tone: "bg-emerald-100 text-emerald-700", Icon: CheckCircle2, text: "Đạt — biên dịch sạch, lời giải PASS hết" };
    case "NEEDS_REVIEW": return { tone: "bg-amber-100 text-amber-700", Icon: AlertTriangle, text: "Cần xem lại — chưa hội tụ sau số lần tối đa" };
    case "NO_API_KEY":   return { tone: "bg-slate-200 text-slate-600", Icon: KeyRound, text: "Chưa cắm API key" };
    case "CANCELLED":    return { tone: "bg-slate-200 text-slate-600", Icon: StopCircle, text: "Đã dừng theo yêu cầu" };
    case "ERROR":        return { tone: "bg-rose-100 text-rose-700", Icon: XCircle, text: "Lỗi khi gọi AI" };
    case "FAILED":       return { tone: "bg-rose-100 text-rose-700", Icon: XCircle, text: "Thất bại — AI không trả đúng định dạng" };
    default:             return { tone: "bg-blue-100 text-blue-700", Icon: Clock, text: "Đang xử lý..." };
  }
}

export default function AiGeneratorPage() {
  const { teacher } = useAuth();
  const [aiStatus, setAiStatus] = useState<AiStatus | null>(null);
  const [step, setStep] = useState<"config" | "running" | "review">("config");

  // Form cấu hình
  const [examId, setExamId] = useState("");
  const [examName, setExamName] = useState("");
  const [topic, setTopic] = useState("");
  const [sizeMode, setSizeMode] = useState<"auto" | "40" | "60">("auto");
  const [difficulty, setDifficulty] = useState("mixed");
  const [extra, setExtra] = useState("");

  // Job
  const [job, setJob] = useState<Job | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const stopPoll = () => { if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; } };

  // Xem trước
  const [activeFile, setActiveFile] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [toast, setToast] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [cancelling, setCancelling] = useState(false);

  // Chỉnh sửa theo prompt (sau khi đã có kết quả)
  const [refinePrompt, setRefinePrompt] = useState("");
  const [refining, setRefining] = useState(false);

  const loadStatus = useCallback(async () => {
    try {
      const d = await fetch(`${API_BASE}/ai-generator/status`).then((r) => r.json());
      setAiStatus(d);
    } catch { setAiStatus(null); }
  }, []);

  useEffect(() => { loadStatus(); return stopPoll; }, [loadStatus]);

  // Tự ẩn toast sau 5 giây
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(t);
  }, [toast]);

  const startGenerate = async () => {
    setErr(null); setSaveMsg(null);
    if (!teacher) { setErr("Cần đăng nhập."); return; }
    if (!examId.trim() || !/^[A-Za-z0-9_-]{1,60}$/.test(examId.trim())) {
      setErr("Mã đề chỉ gồm a-z, A-Z, 0-9, _, - (vd PE_AI_01)."); return;
    }
    try {
      const d = await api("/ai-generator/generate", "POST", {
        examId: examId.trim(), examName: examName.trim(), topic,
        numTestcases: sizeMode === "auto" ? null : Number(sizeMode), difficulty, extra,
      });
      setJob({ jobId: d.jobId, status: "RUNNING" });
      setStep("running");
      startPoll(d.jobId);
      // Nhớ jobId (+ thông tin để Lưu/đặt tên) để rời trang rồi quay lại vẫn thấy đang tạo
      try { localStorage.setItem(ACTIVE_JOB_KEY, JSON.stringify({ jobId: d.jobId, examId: examId.trim(), examName: examName.trim(), topic })); } catch { /* ignore */ }
    } catch (e) { setErr((e as Error).message); }
  };

  const startPoll = (jobId: string) => {
    stopPoll();
    pollRef.current = setInterval(async () => {
      try {
        const s: Job = await fetch(`${API_BASE}/ai-generator/job/${jobId}`).then((r) => r.json());
        setJob(s);
        if (TERMINAL.includes(s.status)) {
          stopPoll();
          setActiveFile(0);
          setStep("review");
        }
      } catch { /* giữ trạng thái, thử lại nhịp sau */ }
    }, 1500);
  };

  // ── Khôi phục job khi quay lại trang ─────────────────────────────
  // Tạo đề chạy SERVER-SIDE (theo jobId). Rời trang chỉ làm mất state React + dừng polling,
  // KHÔNG dừng việc tạo. Đọc lại jobId đã lưu để hiện tiếp tiến trình (đang chạy hoặc đã xong).
  useEffect(() => {
    let saved: { jobId?: string; examId?: string; examName?: string; topic?: string } | null = null;
    try { saved = JSON.parse(localStorage.getItem(ACTIVE_JOB_KEY) || "null"); } catch { saved = null; }
    if (!saved?.jobId) return;
    const jobId = saved.jobId;
    // Phục hồi form để nút "Lưu thành đề" / đặt tên vẫn dùng được sau khi quay lại
    if (saved.examId) setExamId(saved.examId);
    if (saved.examName) setExamName(saved.examName);
    if (saved.topic) setTopic(saved.topic);
    (async () => {
      try {
        const r = await fetch(`${API_BASE}/ai-generator/job/${jobId}`);
        const s: Job = await r.json().catch(() => null as unknown as Job);
        if (!r.ok || !s || !s.status) { try { localStorage.removeItem(ACTIVE_JOB_KEY); } catch { /* ignore */ } return; }
        setJob(s);
        if (TERMINAL.includes(s.status)) { setActiveFile(0); setStep("review"); }
        else { setStep("running"); startPoll(jobId); }
      } catch { /* để nguyên, lần mở sau thử lại */ }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doCancel = async () => {
    if (!job) return;
    setCancelling(true);
    try { await api(`/ai-generator/job/${job.jobId}/cancel`, "POST"); } catch { /* ignore */ }
  };

  const doRefine = async () => {
    if (!job || !refinePrompt.trim()) return;
    setRefining(true); setErr(null); setSaveMsg(null);
    try {
      await api(`/ai-generator/job/${job.jobId}/refine`, "POST", { instruction: refinePrompt.trim() });
      setStep("running");
      startPoll(job.jobId);
      setRefinePrompt("");
    } catch (e) { setErr((e as Error).message); }
    finally { setRefining(false); }
  };

  const resetAll = () => {
    stopPoll(); setJob(null); setErr(null); setSaveMsg(null); setCancelling(false);
    setExamId(""); setExamName(""); setTopic(""); setExtra("");
    setSizeMode("auto"); setDifficulty("mixed"); setActiveFile(0);
    setToast(null); setRefinePrompt(""); setRefining(false); setStep("config");
    try { localStorage.removeItem(ACTIVE_JOB_KEY); } catch { /* ignore */ }
  };

  const doSave = async () => {
    if (!job?.result?.files) return;
    setSaving(true); setErr(null); setSaveMsg(null);
    const get = (n: string) => job.result!.files!.find((f) => f.name === n)?.content || "";
    try {
      const d = await api("/ai-generator/save", "POST", {
        examId: examId.trim(),
        examName: examName.trim(),
        teacherNote: topic.slice(0, 500),
        examTest: get("exam_test.dart"),
        graderDart: get("grader.dart"),
        skillsMatrix: get("skills_matrix.json"),
        // Lưu kèm bộ phát SV để Kho đề tải về sau (đề bài + khung starter) + lời giải mẫu (GV tham khảo).
        deBai: job.result.de_bai || "",
        starter: job.result.starter || [],
        solution: job.result.solution || [],
      });
      setSaveMsg(`Đã lưu đề ${d.examId} (trạng thái ${d.status}) kèm đề bài + starter. Vào Kho đề thi để tải/chấm.`);
      setToast({ type: "success", text: `Đã lưu thành đề ${d.examId} thành công!` });
      if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (e) {
      const msg = (e as Error).message;
      setErr(msg);
      setToast({ type: "error", text: `Lưu đề thất bại: ${msg}` });
    }
    finally { setSaving(false); }
  };

  const notReady = aiStatus && !aiStatus.ready;
  const allFiles: TabFile[] = job?.result
    ? [
        ...(job.result.files || []).map((f) => ({ label: f.name, content: f.content, kind: "test" as const })),
        ...(job.result.starter || []).map((f) => ({ label: `starter/${f.name}`, content: f.content, kind: "starter" as const })),
        ...(job.result.solution || []).map((f) => ({ label: `solution/${f.name}`, content: f.content, kind: "sol" as const })),
      ]
    : [];

  return (
    <SidebarLayout
      title="Tạo đề bằng AI"
      subtitle="AI sinh exam_test + skills_matrix + lời giải mẫu, tự biên dịch & sửa cho tới khi PASS"
      activePath="/teacher/ai-generator"
    >
      {/* Banner trạng thái provider / API key */}
      <div className={`mb-5 flex items-start gap-2.5 rounded-xl border p-4 text-xs ${
        notReady ? "border-amber-200 bg-amber-50/70 text-amber-700" : "border-indigo-100 bg-indigo-50/60 text-indigo-700"}`}>
        {notReady ? <KeyRound size={16} className="mt-0.5 shrink-0" /> : <Sparkles size={16} className="mt-0.5 shrink-0" />}
        <div className="space-y-1">
          {aiStatus ? (
            notReady ? (
              <>
                <p><b>Chưa cắm API key.</b> Tính năng hiện đang là KHUNG — điền key để bật.</p>
                <p className="text-amber-600/90">Mở <span className="font-mono">grader/src/main/resources/application.properties</span> →
                  điền <span className="font-mono">grader.ai.{aiStatus.provider}.api-key</span> (provider hiện tại: <b>{aiStatus.provider}</b>) → khởi động lại backend.</p>
              </>
            ) : (
              <p>Provider: <b>{aiStatus.provider}</b> · model <span className="font-mono">{aiStatus.model}</span> · đã sẵn sàng.
                AI sẽ <b>tự biên dịch trong Docker và sửa</b> tối đa vài vòng để testcase chạy được.</p>
            )
          ) : <p>Đang kiểm tra cấu hình AI...</p>}
        </div>
      </div>

      {/* Thanh bước */}
      <div className="mb-6 flex items-center gap-2 text-xs font-semibold">
        {[["config", "1. Cấu hình"], ["running", "2. Sinh & sửa"], ["review", "3. Xem & lưu"]].map(([k, label], i) => (
          <React.Fragment key={k}>
            {i > 0 && <ChevronRight size={14} className="text-slate-300" />}
            <span className={`rounded-full px-3 py-1 ${step === k ? "bg-indigo-600 text-white" : "bg-slate-100 text-slate-400"}`}>{label}</span>
          </React.Fragment>
        ))}
      </div>

      {err && (
        <div className="mb-4 flex items-center gap-2 rounded-lg border border-rose-100 bg-rose-50 p-3 text-sm text-rose-600">
          <AlertTriangle size={15} /> {err}
        </div>
      )}
      {saveMsg && (
        <div className="mb-4 flex items-center justify-between gap-2 rounded-lg border border-emerald-100 bg-emerald-50 p-3 text-sm text-emerald-700">
          <span className="flex items-center gap-2"><CheckCircle2 size={15} /> {saveMsg}</span>
          <Link href="/teacher/archive" className="font-semibold underline hover:text-emerald-800">Mở Kho đề →</Link>
        </div>
      )}

      {/* BƯỚC 1 — CẤU HÌNH */}
      {step === "config" && (
        <div className="card p-6">
          <div className="grid gap-4 md:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-slate-600">Mã đề *</span>
              <input value={examId} onChange={(e) => setExamId(e.target.value)} placeholder="PE_AI_01"
                className="w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-slate-600">Tên đề</span>
              <input value={examName} onChange={(e) => setExamName(e.target.value)} placeholder="Quản lý thu chi"
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
            </label>
          </div>

          <label className="mt-4 block">
            <span className="mb-1 block text-xs font-semibold text-slate-600">Đề bài / ý tưởng *</span>
            <textarea value={topic} onChange={(e) => setTopic(e.target.value)} rows={6}
              placeholder={"Mô tả NGẮN cũng được — AI tự thiết kế chi tiết & ánh xạ sang kỹ năng trong syllabus.\nVD: \"Viết app quản lý thu chi 2 màn hình, kiểm tra Dart essentials, form validate, ListView.\"\nMuốn sát ý hơn thì nêu thêm tên file/class/hàm và text UI mong muốn."}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm leading-relaxed outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <span className="text-[11px] text-slate-400">Ví dụ nhanh:</span>
              {[
                "Viết app quản lý thu chi 2 màn hình, kiểm tra Dart essentials, form validate, ListView.",
                "App To-do 1 màn hình: thêm/xoá/đánh dấu xong, kiểm tra state và UI list.",
                "App quản lý sinh viên 2 màn hình: danh sách + form thêm, validate dữ liệu, điều hướng giữa 2 màn hình.",
              ].map((ex) => (
                <button key={ex} type="button" onClick={() => setTopic(ex)}
                  className="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] text-slate-500 hover:border-indigo-300 hover:text-indigo-600">
                  {ex.length > 46 ? ex.slice(0, 46) + "…" : ex}
                </button>
              ))}
            </div>
            <span className="mt-1 block text-[11px] text-slate-400">AI sẽ tự chọn tên file/class/hàm và text UI, ghi rõ trong phần &quot;Giả định &amp; hợp đồng API&quot;. Càng nêu rõ thì càng sát ý.</span>
          </label>

          <div className="mt-4 grid gap-4 md:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-slate-600">Số lượng testcase</span>
              <select value={sizeMode} onChange={(e) => setSizeMode(e.target.value as "auto" | "40" | "60")}
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100">
                <option value="auto">Tự động — AI tạo nhiều nhất (tối thiểu 40)</option>
                <option value="40">Khoảng 40 (nhanh hơn)</option>
                <option value="60">Khoảng 60</option>
              </select>
              <span className="mt-1 block text-[11px] text-slate-400">Càng nhiều testcase càng phủ rộng nhưng tạo lâu hơn. Chọn ~40 nếu cần nhanh.</span>
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold text-slate-600">Phân bố độ khó</span>
              <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)}
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100">
                {DIFFICULTIES.map((d) => <option key={d.v} value={d.v}>{d.label}</option>)}
              </select>
            </label>
          </div>

          <label className="mt-4 block">
            <span className="mb-1 block text-xs font-semibold text-slate-600">Yêu cầu thêm (tùy chọn)</span>
            <input value={extra} onChange={(e) => setExtra(e.target.value)}
              placeholder="VD: tập trung mảng Validate; thêm 1 testcase edge case số âm..."
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
          </label>

          <div className="mt-6 flex items-center justify-end gap-3">
            <button onClick={startGenerate} disabled={!topic.trim() || !examId.trim()}
              className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700 disabled:opacity-40">
              <Wand2 size={16} /> Tạo đề
            </button>
          </div>
        </div>
      )}

      {/* BƯỚC 2 — SINH & SỬA (vòng compile-fix live) */}
      {step === "running" && job && (
        <div className="card p-6">
          <div className="mb-4 flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <Loader2 size={20} className="animate-spin text-indigo-500" />
              <div>
                <p className="text-sm font-semibold text-slate-700">{job.message || "Đang xử lý..."}</p>
                <p className="text-[11px] text-slate-400">AI sinh → biên dịch trong Docker → nếu lỗi thì tự sửa → lặp lại</p>
              </div>
            </div>
            <button onClick={doCancel} disabled={cancelling}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-rose-200 bg-rose-50 px-3 py-1.5 text-xs font-semibold text-rose-600 transition-colors hover:bg-rose-100 disabled:opacity-50">
              {cancelling ? <Loader2 size={13} className="animate-spin" /> : <StopCircle size={14} />} {cancelling ? "Đang dừng…" : "Dừng"}
            </button>
          </div>
          <AttemptTimeline attempts={job.attempts} />
          <p className="mt-4 text-center text-[11px] text-slate-400">Bước này có thể mất 1–vài phút (gọi AI + chạy Flutter test mỗi vòng). Bấm <b>Dừng</b> để thoát ở vòng/bước gần nhất.</p>
        </div>
      )}

      {/* BƯỚC 3 — XEM & LƯU */}
      {step === "review" && job && (
        <div className="space-y-5">
          {(() => {
            const b = statusBadge(job.status);
            return (
              <div className={`flex items-center gap-2 rounded-xl px-4 py-3 text-sm font-semibold ${b.tone}`}>
                <b.Icon size={16} /> {b.text}
                {job.message && <span className="font-normal opacity-80">— {job.message}</span>}
              </div>
            );
          })()}

          <AttemptTimeline attempts={job.attempts} />

          {/* Tải về ZIP */}
          {job.result && (
            <div className="card flex flex-wrap items-center gap-2 p-3">
              <span className="mr-1 flex items-center gap-1.5 text-xs font-bold text-slate-600"><Download size={14} /> Tải về:</span>
              <button onClick={() => downloadJobZip(job.jobId, "testcase", `${examId || "de"}_testcase.zip`)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-200 bg-indigo-50 px-3 py-1.5 text-xs font-semibold text-indigo-700 hover:bg-indigo-100">
                <FileArchive size={13} /> ZIP testcase
              </button>
              <button onClick={() => downloadJobZip(job.jobId, "handout", `${examId || "de"}_phat_sv.zip`)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-100">
                <FileArchive size={13} /> ZIP đề bài + khung code (phát SV)
              </button>
              <button onClick={() => downloadJobZip(job.jobId, "all", `${examId || "de"}_full.zip`)}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:text-indigo-600">
                <FileArchive size={13} /> ZIP tất cả
              </button>
            </div>
          )}

          {/* ĐỀ BÀI phát cho sinh viên */}
          {job.result?.de_bai && (
            <div className="card overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/60 px-4 py-2.5">
                <p className="flex items-center gap-1.5 text-xs font-bold text-slate-600"><FileText size={13} /> Đề bài phát cho sinh viên</p>
                <div className="flex gap-1.5">
                  <button onClick={() => openExamPdf({ examId: examId || "de", examName, markdown: job.result!.de_bai! })} className="inline-flex items-center gap-1 rounded border border-indigo-200 bg-indigo-50 px-2 py-1 text-[11px] font-semibold text-indigo-700 hover:bg-indigo-100"><Printer size={12} /> Xuất PDF</button>
                  <button onClick={() => downloadText(`${examId || "de"}_de_bai.md`, job.result!.de_bai!)} className="rounded border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-500 hover:text-indigo-600">Tải .md</button>
                  <button onClick={() => copyText(job.result!.de_bai!)} className="rounded border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-500 hover:text-indigo-600">Copy</button>
                </div>
              </div>
              <pre className="custom-scrollbar max-h-[45vh] overflow-auto whitespace-pre-wrap p-4 text-xs leading-relaxed text-slate-700">{job.result.de_bai}</pre>
            </div>
          )}

          {/* Chỉnh sửa theo yêu cầu (prompt tự do) */}
          {job.result?.files && job.status !== "NO_API_KEY" && (
            <div className="card border-indigo-100 p-4">
              <p className="mb-1 flex items-center gap-1.5 text-sm font-bold text-slate-700"><Wand2 size={14} className="text-indigo-500" /> Yêu cầu AI chỉnh sửa</p>
              <p className="mb-2 text-[11px] text-slate-400">Nhập yêu cầu bằng lời, AI sẽ cập nhật testcase + đề bài + khung code và biên dịch lại. VD: &quot;thêm 1 màn hình thống kê&quot;, &quot;tăng độ khó&quot;, &quot;viết đề rõ ràng hơn&quot;, &quot;đổi tên app thành Sổ Chi Tiêu&quot;.</p>
              <textarea value={refinePrompt} onChange={(e) => setRefinePrompt(e.target.value)} rows={3}
                placeholder="VD: Thêm màn hình thống kê tổng thu/chi theo tháng; thêm 3 testcase cho phần này; làm đề bài rõ ràng hơn."
                className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm leading-relaxed outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                {["Tăng độ khó hơn", "Thêm vài testcase edge case", "Viết đề bài rõ ràng, dễ hiểu hơn", "Thêm 1 màn hình mới"].map((ex) => (
                  <button key={ex} type="button" onClick={() => setRefinePrompt(ex)}
                    className="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[11px] text-slate-500 hover:border-indigo-300 hover:text-indigo-600">
                    {ex}
                  </button>
                ))}
                <button onClick={doRefine} disabled={refining || !refinePrompt.trim()}
                  className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 disabled:opacity-40">
                  {refining ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />} Gửi yêu cầu chỉnh
                </button>
              </div>
            </div>
          )}

          {job.result?.assumptions && (
            <div className="rounded-xl border border-slate-200 bg-slate-50/60 p-4">
              <p className="mb-1 flex items-center gap-1.5 text-xs font-bold text-slate-600"><Info size={13} /> Giả định & hợp đồng API (để đối chiếu test ↔ starter)</p>
              <pre className="whitespace-pre-wrap text-[11px] leading-relaxed text-slate-600">{job.result.assumptions}</pre>
            </div>
          )}

          {/* File: testcase (đề) · starter (phát SV) · solution (kiểm thử) */}
          {allFiles.length > 0 && (
            <div className="card overflow-hidden">
              <div className="flex shrink-0 items-center gap-1 overflow-x-auto border-b border-slate-100 bg-slate-50/60 px-3 py-2">
                {allFiles.map((f, i) => (
                  <button key={f.label} onClick={() => setActiveFile(i)}
                    className={`shrink-0 rounded-md px-2.5 py-1 font-mono text-xs transition-colors ${i === activeFile ? kindActive(f.kind) : "text-slate-500 hover:bg-slate-100"}`}>
                    {f.label}
                  </button>
                ))}
                <div className="ml-auto flex shrink-0 gap-1.5 pl-2">
                  <button onClick={() => copyText(allFiles[activeFile]?.content || "")} className="rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-500 hover:text-indigo-600">Copy</button>
                  <button onClick={() => downloadText(baseName(allFiles[activeFile]?.label || "file.txt"), allFiles[activeFile]?.content || "")} className="rounded border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-500 hover:text-indigo-600">Tải</button>
                </div>
              </div>
              <pre className="custom-scrollbar max-h-[55vh] overflow-auto whitespace-pre-wrap bg-slate-900 p-4 text-[11px] leading-relaxed text-slate-100">
                {allFiles[activeFile]?.content}
              </pre>
              <p className="border-t border-slate-100 bg-slate-50/60 px-4 py-2 text-[11px] text-slate-400">
                <b className="text-emerald-600">starter/</b> = khung code phát SV (tải về, nén <span className="font-mono">lib/</span> gửi SV) · <b className="text-slate-500">solution/</b> = lời giải mẫu để kiểm thử (KHÔNG phát, không lưu vào đề). Lưu đề chỉ gồm <span className="font-mono">exam_test.dart + grader.dart + skills_matrix.json</span>.
              </p>
            </div>
          )}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <button onClick={resetAll}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-600 hover:text-indigo-600">
              <RotateCcw size={15} /> Tạo lại
            </button>
            <button onClick={doSave} disabled={saving || !job.result?.files || job.status === "NO_API_KEY"}
              className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-emerald-700 disabled:opacity-40">
              {saving ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Lưu thành đề
            </button>
          </div>
          {job.status === "NEEDS_REVIEW" && (
            <p className="text-center text-[11px] text-amber-600">⚠ Testcase chưa chắc nhất quán — nên xem kỹ exam_test.dart & skills_matrix.json trước khi lưu.</p>
          )}
        </div>
      )}

      {/* Toast nổi — báo kết quả khi bấm "Lưu thành đề" (luôn thấy dù đang ở cuối trang) */}
      {toast && (
        <div
          role="status"
          aria-live="polite"
          className={`fixed right-5 top-5 z-[60] flex max-w-sm items-start gap-2.5 rounded-xl border px-4 py-3 text-sm font-semibold shadow-lg ${
            toast.type === "success"
              ? "border-emerald-200 bg-emerald-50 text-emerald-700"
              : "border-rose-200 bg-rose-50 text-rose-700"
          }`}
        >
          {toast.type === "success"
            ? <CheckCircle2 size={17} className="mt-0.5 shrink-0" />
            : <XCircle size={17} className="mt-0.5 shrink-0" />}
          <span className="flex-1">{toast.text}</span>
          {toast.type === "success" && (
            <Link href="/teacher/archive" className="shrink-0 underline hover:opacity-80">Kho đề →</Link>
          )}
          <button onClick={() => setToast(null)} className="shrink-0 text-slate-400 hover:text-slate-600" aria-label="Đóng">
            <X size={15} />
          </button>
        </div>
      )}
    </SidebarLayout>
  );
}

// ── Dòng thời gian các vòng (Pha A: testcase · Pha B: đề bài + khung) ──
const PHASE_LABELS: Record<string, string> = {
  generate: "AI sinh testcase", parse: "đọc JSON", compile: "chạy thử lời giải", prune: "bỏ test fail, giữ phần PASS",
  "starter-gen": "AI sinh đề bài + khung", "starter-parse": "đọc JSON", starter: "biên dịch khung code",
};

function AttemptTimeline({ attempts }: { attempts?: Attempt[] }) {
  if (!attempts || attempts.length === 0)
    return <p className="text-center text-xs text-slate-400">Chưa có vòng nào — đang chờ AI...</p>;
  return (
    <ol className="space-y-2">
      {attempts.map((a, i) => {
        const isCompile = a.phase === "compile" || a.phase === "starter";
        const isParse = a.phase === "parse" || a.phase === "starter-parse";
        const isPrune = a.phase === "prune";
        const ok = isCompile || isPrune ? !!a.ok : false;
        const Icon = isPrune ? CheckCircle2 : isCompile ? (ok ? CheckCircle2 : Hammer) : (isParse ? Bug : Wand2);
        const tone = ok ? "text-emerald-600" : isCompile ? "text-amber-600" : "text-rose-500";
        return (
          <li key={i} className="rounded-lg border border-slate-100 bg-white p-3">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Icon size={15} className={tone} />
              <span className="font-semibold text-slate-700">Vòng {a.round}</span>
              <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">{PHASE_LABELS[a.phase] || a.phase}</span>
              {a.phase === "compile" && (
                <span className="text-xs text-slate-500">
                  {a.compiled ? "biên dịch OK" : "lỗi biên dịch"} · PASS {a.passed ?? 0}/{a.total ?? 0}
                </span>
              )}
              {a.phase === "starter" && (
                <span className="text-xs text-slate-500">{a.compiled ? "khung code biên dịch OK" : "khung code lỗi biên dịch"}</span>
              )}
            </div>
            {a.summary && <p className="mt-1 pl-6 text-[11px] text-slate-500">{a.summary}</p>}
            {a.errorsExcerpt && (
              <pre className="custom-scrollbar mt-2 ml-6 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-slate-900 p-2 text-[10px] leading-relaxed text-rose-200">
                {a.errorsExcerpt}
              </pre>
            )}
          </li>
        );
      })}
    </ol>
  );
}
