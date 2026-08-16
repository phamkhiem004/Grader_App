"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tooltip } from "@/components/ui/Tooltip";
import CompetencyPanel, { CompetencyItem } from "@/components/grading/CompetencyPanel";
import { gradingStatusLabel, gradingStatusTone, type GradingOutcome } from "@/lib/gradingStatus";
import { csvRow, downloadCsv, formatGradingTime } from "@/lib/csv";
import {
  FileJson, DownloadCloud, Search, ChevronRight,
  CheckCircle, AlertCircle, Clock, Users, FileText, FileArchive,
  BarChart3, X, Loader2, FileCode2, RotateCcw,
} from "lucide-react";

interface ExamOption { examId: string; examName: string; }
interface TestCaseItem {
  test_id?: string; name?: string; status?: string; executed?: boolean; weight?: number;
  skill_code?: string; skill_name?: string; category_label?: string;
  difficulty?: string; skill?: string;
  expected?: string; actual?: string; error_log?: string;
  // `actual_source === "observation"` = `actual` đã là câu tiếng Việt sạch, hiển thị thẳng.
  // Vắng cờ = dữ liệu chấm trước P5, `actual` có thể còn là log thô → phải parse.
  actual_source?: string;
  error_code?: string;
}

const DIFF_BADGE: Record<string, string> = {
  basic: "bg-slate-100 text-slate-500",
  intermediate: "bg-amber-100 text-amber-700",
  advanced: "bg-rose-100 text-rose-700",
};
const DIFF_VI: Record<string, string> = { basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao" };
interface DetailData {
  student?: { id?: string; name?: string };
  grading_result?: {
    score?: number;
    passed_tests?: number;
    failed_tests?: number;
    total_tests?: number;
    total_scenarios?: number;
    total_criteria?: number;
  };
  competency_assessment?: CompetencyItem[];
  test_cases?: TestCaseItem[];
}
interface ResultRow {
  id: number;
  studentId: string;
  studentName: string | null;
  score: number | null;
  status: "DONE" | "ERROR" | "MANUAL_REVIEW" | "GRADING" | "QUEUED" | "CANCELLED";
  /** Kết luận backend phát hành; vắng mặt ở dữ liệu cũ nên nhãn vẫn suy được từ status. */
  outcome?: GradingOutcome | null;
  batchId: string | null;
  submittedAt: string | null;
  updatedAt: string | null;
  /** Máy bắt đầu / kết thúc chấm bài này; null với dữ liệu chấm trước khi có hai cột này. */
  gradingStartedAt: string | null;
  gradingFinishedAt: string | null;
  details: string | null;
  errorLog: string | null;
  diagnosticCode: string | null;
  diagnosticOrigin: "STUDENT" | "TESTCASE" | "ENVIRONMENT" | "UNDETERMINED" | null;
  diagnosticStage: string | null;
  requiresManualReview: boolean;
  hasJson: boolean;
}
interface CodeFile { name: string; content: string; }

// Màu badge theo nhóm: sai giá trị (rose) · widget/UI (amber/orange) · exception runtime (red) · khác (slate).
const ERR_BADGE: Record<string, string> = {
  VALUE_MISMATCH: "bg-rose-100 text-rose-700",
  ASSERTION_FAILED: "bg-slate-100 text-slate-600",
  WIDGET_NOT_FOUND: "bg-amber-100 text-amber-700",
  WIDGET_UNEXPECTED: "bg-amber-100 text-amber-700",
  WIDGET_COUNT: "bg-amber-100 text-amber-700",
  // Năm mã của đường quan sát (A1 + A2b) — tách riêng vì cách sửa khác nhau, không dồn chung.
  SIZE_MISMATCH: "bg-rose-100 text-rose-700",
  TEXT_STYLE_MISMATCH: "bg-rose-100 text-rose-700",
  SEMANTICS_MISMATCH: "bg-amber-100 text-amber-700",
  ENABLED_MISMATCH: "bg-amber-100 text-amber-700",
  // Cùng màu hổ phách với nhóm "thành phần chưa đúng", KHÔNG cùng màu WIDGET_NOT_FOUND để
  // giáo viên nhìn badge là phân biệt được "thiếu hẳn" với "có nhưng sai loại".
  WIDGET_TYPE_MISMATCH: "bg-yellow-100 text-yellow-800",
  LAYOUT_OVERFLOW: "bg-orange-100 text-orange-700",
  TIMEOUT: "bg-orange-100 text-orange-700",
  NULL_ERROR: "bg-red-100 text-red-700",
  TYPE_ERROR: "bg-red-100 text-red-700",
  RANGE_ERROR: "bg-red-100 text-red-700",
  NO_SUCH_METHOD: "bg-red-100 text-red-700",
  FORMAT_ERROR: "bg-red-100 text-red-700",
  STATE_ERROR: "bg-red-100 text-red-700",
  BUILD_ERROR: "bg-red-100 text-red-700",
  COMPILE_ERROR: "bg-red-100 text-red-700",
  EXCEPTION_THROWN: "bg-red-100 text-red-700",
};

/**
 * Khối ĐỎ chi tiết lỗi 1 testcase fail.
 *
 * Chọn nhánh theo **CỜ ĐỜI DỮ LIỆU** `actual_source`, không theo sự có mặt của field nào:
 *  - `"observation"` → `actual` đã là câu tiếng Việt do engine quan sát được, in thẳng.
 *  - vắng cờ → dữ liệu chấm trước P5, `actual` có thể còn là log thô của flutter test → parse.
 *
 * Nhánh parse log CỐ Ý GIỮ LẠI: kết quả cũ trong DB không được migrate nên còn đó vĩnh viễn.
 * Gỡ nó "vì format mới đã sạch" là làm trang Lịch sử của bài cũ hiện log thô ra cho giáo viên.
 *
 * P2b đã bỏ `error.message` và `student_safe_summary` — hai câu tra bảng theo mã lỗi, không phải
 * điều quan sát được. Chỉ còn `error_code` làm nhãn phân loại.
 */
function FailureDetail({ requirement, actual, errorCode, actualSource }:
  { requirement?: string; actual?: string; errorCode?: string; actualSource?: string }) {
  if (actualSource === "observation") {
    return (
      <div className="mt-1 space-y-0.5 pl-3.5 text-[11px] text-rose-500">
        {requirement && <p><span className="font-semibold">Đề yêu cầu:</span> {requirement}</p>}
        {actual && <p><span className="font-semibold">Quan sát được:</span> {actual}</p>}
        {errorCode && (
          <p className="flex flex-wrap items-baseline gap-1.5">
            <span className="font-semibold">Loại lỗi:</span>
            <span className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${ERR_BADGE[errorCode] || "bg-slate-100 text-slate-600"}`}>
              {errorCode}
            </span>
          </p>
        )}
      </div>
    );
  }

  const t = (actual || "").trim();
  const isWidget = /Test failed\.?\s*See exception logs above/i.test(t);
  const tcId = t.match(/TC_[A-Z0-9_]+/)?.[0];
  const exp = t.match(/Expected:\s*([^\n]+)/i)?.[1]?.trim();
  const act = t.match(/Actual:\s*([^\n]+)/i)?.[1]?.trim();
  const reason = t.split("\n").map((s) => s.trim())
    .filter((s) => s && !/^(Expected:|Actual:|Which:)/i.test(s)).pop();

  // Lý do: ưu tiên diễn giải exception widget; rồi tới reason; rồi tới actual thô (khi không parse được).
  const lyDo = isWidget
    ? `Test ném exception khi chạy (thiếu/sai widget, nhãn UI hoặc luồng mà test cần)${tcId ? ` [${tcId}]` : ""}.`
    : (reason && reason !== exp && reason !== act ? reason
      : (!exp && !act && t ? (t.length > 300 ? t.slice(0, 300) + "…" : t) : ""));

  const Row = ({ label, value }: { label: string; value?: string }) =>
    value ? <p><span className="font-semibold">{label}:</span> <span className="font-mono">{value}</span></p> : null;

  return (
    <div className="mt-1 space-y-0.5 pl-3.5 text-[11px] text-rose-500">
      {requirement && <p><span className="font-semibold">Yêu cầu:</span> {requirement}</p>}
      <Row label="Expected" value={exp} />
      <Row label="Actual (thực tế)" value={act} />
      {lyDo && <p><span className="font-semibold">Lý do:</span> {lyDo}</p>}
    </div>
  );
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

// Nhãn dùng chung với trang Chấm tự động — xem lib/gradingStatus.
function statusVi(row: Pick<ResultRow, "status" | "outcome">): string {
  return gradingStatusLabel(row.status, row.outcome);
}

/**
 * Trạng thái một testcase cho file xuất ra.
 *
 * <p>`not_run` được giữ riêng chứ không gộp vào fail: nó nghĩa là testcase CHƯA CÓ CƠ HỘI chạy
 * (bài không build được…), ghi thành "fail" sẽ đọc ra thành "sai 12 chỗ khác nhau" trong khi chỉ
 * có đúng một nguyên nhân gốc — cùng lý do modal chi tiết tô nó màu xám.
 */
function testcaseStatus(tc: TestCaseItem): "success" | "fail" | "not_run" {
  const status = String(tc.status || "").toLowerCase();
  if (status === "passed" || status === "pass") return "success";
  return status === "not_run" ? "not_run" : "fail";
}

export default function HistoryPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [rows, setRows] = useState<ResultRow[]>([]);
  const [loadingExams, setLoadingExams] = useState(true);
  const [loadingRows, setLoadingRows] = useState(false);
  const [q, setQ] = useState("");

  // Modal chi tiết năng lực 1 bài
  const [detailRow, setDetailRow] = useState<ResultRow | null>(null);
  const [detail, setDetail] = useState<DetailData | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // Modal xem bài làm + testcase (đối chiếu khi 0/0) & trạng thái chấm lại
  const [viewRow, setViewRow] = useState<ResultRow | null>(null);
  const [subFiles, setSubFiles] = useState<CodeFile[]>([]);
  const [tcFiles, setTcFiles] = useState<CodeFile[]>([]);
  /** Lý do không có testcase để xem (bài còn trong hàng đợi / đang chấm) — hiện thay khung code. */
  const [tcNotice, setTcNotice] = useState<string | null>(null);
  const [viewLoading, setViewLoading] = useState(false);
  const [activeSub, setActiveSub] = useState(0);
  const [activeTc, setActiveTc] = useState(0);
  const [regradingId, setRegradingId] = useState<string | null>(null);
  const [exportingId, setExportingId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // Khóa cuộn trang nền khi mở modal
  useEffect(() => {
    if (!detailRow && !viewRow) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, [detailRow, viewRow]);

  // Đọc query param từ thanh search header (?exam=...&q=...) — ưu tiên trước khi chọn mặc định
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const ex = params.get("exam");
    const query = params.get("q");
    if (query) setQ(query);
    if (ex) setSelected(ex);
  }, []);

  // Nạp danh sách bộ testcase đã chấm
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then((r) => r.json())
      .then((data: ExamOption[]) => {
        setExams(Array.isArray(data) ? data : []);
        // Chỉ tự chọn đề đầu khi CHƯA có bộ testcase nào được chọn từ URL
        if (Array.isArray(data) && data.length) {
          setSelected((prev) => prev || data[0].examId);
        }
      })
      .catch(() => setExams([]))
      .finally(() => setLoadingExams(false));
  }, []);

  // Nạp danh sách bài đã chấm của đề được chọn (tách hàm để chấm lại xong refetch không chớp spinner)
  const loadRows = useCallback(async (showSpinner = true) => {
    if (!selected) return;
    if (showSpinner) { setLoadingRows(true); setRows([]); }
    try {
      const data = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}`).then((r) => r.json());
      setRows(Array.isArray(data) ? data : []);
    } catch {
      setRows([]);
    } finally {
      if (showSpinner) setLoadingRows(false);
    }
  }, [selected]);

  useEffect(() => { loadRows(); }, [loadRows]);

  // Có bài đang GRADING/QUEUED (vd vừa chấm lại rồi rời trang quay lại) → tự poll tới khi xong.
  const hasPending = rows.some((r) => r.status === "GRADING" || r.status === "QUEUED");
  useEffect(() => {
    if (!hasPending) return;
    const id = setInterval(() => loadRows(false), 4000);
    return () => clearInterval(id);
  }, [hasPending, loadRows]);

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
    const manual = rows.filter((r) => r.status === "MANUAL_REVIEW").length;
    const avg = done.length
      ? done.reduce((s, r) => s + (r.score || 0), 0) / done.length
      : 0;
    return { total: rows.length, done: done.length, error, manual, avg };
  }, [rows]);

  // Mở modal chi tiết: tải result_json đầy đủ (có competency_assessment + test_cases)
  const openDetail = async (r: ResultRow) => {
    if (!selected || !r.hasJson) return;
    setDetailRow(r);
    setDetail(null);
    setDetailLoading(true);
    try {
      const res = await fetch(
        `${API_BASE}/results/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`
      );
      if (res.ok) {
        const text = await res.text();
        setDetail(JSON.parse(text) as DetailData);
      }
    } catch {
      /* bỏ qua */
    } finally {
      setDetailLoading(false);
    }
  };
  const closeDetail = () => { setDetailRow(null); setDetail(null); };

  /** Bài chưa xong một lượt chấm nào: chưa có testcase đã dùng, cũng chưa chấm lại được. */
  const inFlight = (r: ResultRow) => r.status === "QUEUED" || r.status === "GRADING";

  // Mở modal đối chiếu: tải song song mã nguồn SV + testcase đã dùng để chấm
  const openView = async (r: ResultRow) => {
    if (!selected) return;
    setViewRow(r); setSubFiles([]); setTcFiles([]); setActiveSub(0); setActiveTc(0);
    setTcNotice(null); setViewLoading(true);
    try {
      const [sf, tf] = await Promise.all([
        fetch(`${API_BASE}/batch/submission/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}/files`).then((x) => (x.ok ? x.json() : [])),
        // Bài chưa chấm xong → backend trả 409 kèm lý do; hiện thẳng lý do đó thay vì khung trống.
        fetch(`${API_BASE}/batch/testcase/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}/files`)
          .then(async (x) => (x.ok ? x.json() : { error: (await x.json().catch(() => ({}))).error })),
      ]);
      setSubFiles(Array.isArray(sf) ? sf : []);
      if (Array.isArray(tf)) setTcFiles(tf);
      else setTcNotice(tf?.error || "Không đọc được testcase đã lưu.");
    } catch {
      /* bỏ qua */
    } finally {
      setViewLoading(false);
    }
  };
  const closeView = () => { setViewRow(null); setSubFiles([]); setTcFiles([]); setTcNotice(null); };

  // Chấm lại 1 bài từ zip đã lưu, rồi poll tiến độ đến khi xong → refetch danh sách
  const regrade = async (r: ResultRow) => {
    if (!selected || regradingId) return;
    setRegradingId(r.studentId);
    setRows((list) => list.map((x) => (x.studentId === r.studentId ? { ...x, status: "GRADING", score: null, errorLog: null } : x)));
    try {
      const res = await fetch(`${API_BASE}/batch/regrade/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Chấm lại thất bại");
      const batchId = data.batchId as string;
      for (let i = 0; i < 90; i++) {
        await new Promise((rs) => setTimeout(rs, 2000));
        const pr = await fetch(`${API_BASE}/batch/progress/${encodeURIComponent(batchId)}`).then((x) => (x.ok ? x.json() : null)).catch(() => null);
        if (!pr) continue;
        const me = (pr.results || []).find((x: { studentId: string; status: string }) => x.studentId === r.studentId);
        if ((me && me.status !== "GRADING" && me.status !== "QUEUED") || pr.done + pr.error >= pr.total) break;
      }
      await loadRows(false);
    } catch (e) {
      await loadRows(false);
      alert((e as Error).message);
    } finally {
      setRegradingId(null);
    }
  };

  // Bỏ chọn khi đổi đề
  useEffect(() => { setSelectedIds(new Set()); }, [selected]);

  const toggleSel = (id: string) =>
    setSelectedIds((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  // Chấm lại NHIỀU bài đã chọn (gộp 1 batch); auto-poll tự cập nhật tới khi xong.
  const regradeSelected = async () => {
    if (!selected || selectedIds.size === 0) return;
    const ids = [...selectedIds];
    setRows((list) => list.map((r) => (ids.includes(r.studentId) ? { ...r, status: "GRADING", score: null, errorLog: null } : r)));
    setSelectedIds(new Set());
    try {
      const res = await fetch(`${API_BASE}/batch/regrade-batch/${encodeURIComponent(selected)}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ studentIds: ids }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Chấm lại thất bại");
      if (Array.isArray(data.skipped) && data.skipped.length)
        alert(`Đã bỏ qua ${data.skipped.length} bài (mất file/bộ testcase): ${data.skipped.join(", ")}`);
    } catch (e) {
      await loadRows(false);
      alert((e as Error).message);
    }
  };

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

  /**
   * Tải về thư mục kết quả: mỗi bài đã chấm xong một file JSON riêng, giải nén ra `Json/<MSSV>.json`
   * — thay cho bản JSON GỘP trước đây (một file khổng lồ phải tự tách mới dùng được).
   *
   * Không dùng `showDirectoryPicker` — xem lý do ở trang Chấm tự động.
   */
  const downloadResultsFolder = async () => {
    if (!selected) return;
    try {
      const res = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(selected)}/archive`);
      if (res.status === 404) throw new Error("Chưa có bài nào chấm xong để xuất.");
      if (!res.ok) throw new Error("Không tạo được thư mục kết quả.");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "Json.zip";
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (error: any) {
      alert(error?.message || "Không xuất được thư mục JSON.");
    }
  };

  // Xuất CSV toàn bộ đề
  const exportCSV = () => {
    if (!rows.length) return;
    const header = csvRow([
      "STT",
      "Tên người dùng",
      "Điểm",
      "Trạng thái",
      "Số câu đúng",
      "Tổng số câu",
      "Bắt đầu chấm",
      "Chấm xong",
    ]);
    const body = rows.map((r, idx) => {
      const { pass, total } = passInfo(r.details);
      return csvRow([
        idx + 1,
        r.studentName || r.studentId,          // tên thư mục bài nộp
        r.score != null ? r.score.toFixed(1) : "",
        statusVi(r),
        pass,
        total,
        formatGradingTime(r.gradingStartedAt),
        formatGradingTime(r.gradingFinishedAt),
      ]);
    });
    const dateStr = new Date().toISOString().split("T")[0];
    downloadCsv([header, ...body].join("\n"), `${selected}_lichsu_${dateStr}.csv`);
  };

  /**
   * CSV của MỘT bài: một dòng tổng hợp, rồi bảng liệt kê từng testcase đạt/không.
   *
   * <p>Phải tải result_json về mới có danh sách testcase (bảng lịch sử chỉ giữ số đếm cho nhẹ),
   * nên nút này gọi mạng chứ không xuất được ngay từ dữ liệu đang hiển thị.
   */
  const exportRowCSV = async (r: ResultRow) => {
    if (!selected) return;
    setExportingId(r.studentId);
    try {
      let tests: TestCaseItem[] = [];
      let passed: number | null = null;
      let failed: number | null = null;
      if (r.hasJson) {
        const res = await fetch(
          `${API_BASE}/results/${encodeURIComponent(selected)}/${encodeURIComponent(r.studentId)}`);
        if (res.ok) {
          const data = JSON.parse(await res.text()) as DetailData;
          tests = data.test_cases || [];
          passed = data.grading_result?.passed_tests ?? null;
          failed = data.grading_result?.failed_tests ?? null;
        }
      }
      // Bài chưa có JSON (đang chờ, lỗi hệ thống) vẫn xuất được phần tổng hợp — đếm từ `details`.
      const counted = passInfo(r.details);
      const statuses = tests.map(testcaseStatus);
      const pass = passed ?? (tests.length
        ? statuses.filter((s) => s === "success").length : counted.pass);
      const fail = failed ?? (tests.length
        ? statuses.filter((s) => s === "fail").length
        : Math.max(0, counted.total - counted.pass));

      const lines = [
        csvRow(["Tên người dùng", "Điểm", "Trạng thái", "Số câu đúng", "Số câu sai",
          "Bắt đầu chấm", "Chấm xong"]),
        csvRow([
          r.studentName || r.studentId,
          r.score != null ? r.score.toFixed(1) : "",
          statusVi(r),
          pass,
          fail,
          formatGradingTime(r.gradingStartedAt),
          formatGradingTime(r.gradingFinishedAt),
        ]),
        "",
        csvRow(["Testcase", "Trạng thái"]),
        ...tests.map((tc, i) => csvRow([
          tc.test_id || tc.name || `testcase-${String(i + 1).padStart(2, "0")}`,
          statuses[i],
        ])),
      ];
      if (!tests.length) lines.push(csvRow(["(chưa có kết quả testcase)", ""]));

      const name = (r.studentName || r.studentId).replace(/[\\/:*?"<>|]+/g, "_");
      downloadCsv(lines.join("\n"), `${selected}_${name}.csv`);
    } catch (e) {
      alert((e as Error).message || "Không xuất được CSV của bài này.");
    } finally {
      setExportingId(null);
    }
  };

  return (
    <SidebarLayout
      title="Lịch sử chấm"
      subtitle="Xem lại kết quả các bài đã chấm theo bộ testcase"
      activePath="/history"
      /* Cùng khuôn với trang Chấm tự động: nới trần bề ngang và chốt cột trái 320px. Danh sách
         bộ testcase không dài ra thì cũng không dễ đọc hơn — chỗ dôi ra dồn cho bảng kết quả. */
      contentClassName="max-w-[1600px]"
    >
      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[320px_minmax(0,1fr)]">
        {/* Cột trái: danh sách bộ testcase đã chấm */}
        <div className="min-w-0">
          <div className="card overflow-hidden">
            <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-5 py-4">
              <FileText size={16} className="text-indigo-500" />
              <h2 className="text-sm font-bold uppercase tracking-wider text-slate-600">
                Bộ testcase đã chấm ({exams.length})
              </h2>
            </div>
            <div className="custom-scrollbar max-h-[70vh] overflow-y-auto p-2">
              {loadingExams ? (
                Array.from({ length: 6 }).map((_, i) => (
                  <div key={i} className="px-2 py-1"><Skeleton className="h-12 w-full rounded-lg" /></div>
                ))
              ) : exams.length === 0 ? (
                <div className="p-6 text-center text-sm text-slate-400">
                  Chưa có bộ testcase nào được chấm.
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
        <div className="min-w-0 space-y-6">
          {/* Thống kê nhanh */}
          <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
            <MiniStat label="Tổng bài" value={stats.total} icon={Users} tone="slate" />
            <MiniStat label="Đã xong" value={stats.done} icon={CheckCircle} tone="emerald" />
            {/* Gộp ERROR + MANUAL_REVIEW: cả hai đều là "máy chưa cho ra điểm", người chấm xử lý
                y như nhau. Tách hai ô chỉ bắt họ tự cộng lại. */}
            <MiniStat label="Lỗi hệ thống" value={stats.manual + stats.error} icon={AlertCircle} tone="amber" />
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
                  onClick={downloadResultsFolder}
                  disabled={!rows.some((r) => r.hasJson && r.outcome === "SCORED")}
                  title="Xuất thư mục gồm một JSON cho mỗi bài đã chấm xong"
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <FileArchive size={15} /> Xuất JSON
                </button>
                <button
                  onClick={exportCSV}
                  disabled={!rows.length}
                  className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95 disabled:opacity-50"
                >
                  <DownloadCloud size={15} /> Xuất CSV
                </button>
                {selectedIds.size > 0 && (
                  <button
                    onClick={regradeSelected}
                    title="Chấm lại các bài đã chọn (gộp 1 đợt)"
                    className="flex items-center gap-2 rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-all hover:bg-amber-600 active:scale-95"
                  >
                    <RotateCcw size={15} /> Chấm lại ({selectedIds.size})
                  </button>
                )}
              </div>
            </div>

            {/* table-fixed + colgroup như trang Chấm tự động: cột không tự co giãn theo nội dung
                nên bảng vừa một màn, không phải kéo ngang. */}
            <div className="overflow-x-auto">
              <table className="w-full min-w-[680px] table-fixed border-collapse text-left">
                <colgroup>
                  <col className="w-[20%]" />
                  <col className="w-[13%]" />
                  <col className="w-[20%]" />
                  <col className="w-[9%]" />
                  <col className="w-[20%]" />
                  {/* Cột cuối chứa 2 nút 28px + padding: hẹp hơn 14% là nút tràn ra ngoài bảng
                      (table-fixed không nới cột theo nội dung, nó chỉ cho tràn). */}
                  <col className="w-[18%]" />
                </colgroup>
                <thead>
                  <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                    <th className="px-6 py-3.5 text-center">
                      <div className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={filtered.length > 0 && filtered.every((r) => selectedIds.has(r.studentId))}
                          onChange={() =>
                            setSelectedIds((s) => {
                              const n = new Set(s);
                              const all = filtered.every((r) => n.has(r.studentId));
                              filtered.forEach((r) => (all ? n.delete(r.studentId) : n.add(r.studentId)));
                              return n;
                            })
                          }
                          className="h-3.5 w-3.5 rounded border-slate-300 accent-indigo-600"
                        />
                        Sinh viên
                      </div>
                    </th>
                    <th className="px-6 py-3.5 text-center">Trạng thái</th>
                    <th className="px-6 py-3.5 text-center">Pass</th>
                    <th className="px-6 py-3.5 text-center">Điểm</th>
                    <th className="px-6 py-3.5 text-center">Chấm xong</th>
                    <th className="sticky right-0 z-20 border-l border-slate-100 bg-white px-4 py-3.5 text-center">Chi tiết</th>
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
                        <td className="sticky right-0 z-10 border-l border-slate-100 bg-white px-4 py-3.5"><Skeleton className="mx-auto h-7 w-7 rounded-lg" /></td>
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
                      const isManual = r.status === "MANUAL_REVIEW";
                      const statusTone = gradingStatusTone(r.status, r.outcome);
                      const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                      return (
                        <tr key={r.id} className="group transition-colors hover:bg-slate-50/70">
                          <td className="px-6 py-3.5 text-center">
                            <div className="flex items-center gap-3">
                              <input
                                type="checkbox"
                                checked={selectedIds.has(r.studentId)}
                                onChange={() => toggleSel(r.studentId)}
                                className="h-3.5 w-3.5 shrink-0 rounded border-slate-300 accent-indigo-600"
                              />
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
                              className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${statusTone.pill}`}
                            >
                              <span className={`h-1.5 w-1.5 rounded-full ${statusTone.dot}`}></span>
                              {gradingStatusLabel(r.status, r.outcome)}
                            </span>
                          </td>
                          <td className="px-6 py-3.5 text-center">
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
                            ) : (isError || isManual) && r.errorLog ? (
                              <span className={`line-clamp-3 max-w-xs text-xs ${isManual ? "text-amber-700" : "text-rose-500"}`} title={r.errorLog}>
                                {r.diagnosticCode && <strong>[{r.diagnosticCode}] </strong>}{r.errorLog}
                              </span>
                            ) : (
                              <span className="text-slate-300">—</span>
                            )}
                          </td>
                          <td className="px-6 py-3.5 text-center">
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
                          <td className="px-6 py-3.5 text-center text-xs text-slate-500">
                            {formatGradingTime(r.gradingFinishedAt || r.submittedAt || r.updatedAt) || "—"}
                          </td>
                          <td className="sticky right-0 z-10 border-l border-slate-100 bg-white px-4 py-3.5 transition-colors group-hover:bg-slate-50/70">
                            <div className="flex items-center justify-center gap-1">
                              <Tooltip label={inFlight(r)
                                ? "Xem bài làm (bài chưa chấm xong nên chưa có testcase đã dùng)"
                                : "Xem bài làm & testcase"} side="left">
                                <button
                                  onClick={() => openView(r)}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                >
                                  <FileCode2 size={15} />
                                </button>
                              </Tooltip>
                              {/* Bài còn trong hàng đợi mà bấm chấm lại là xếp thêm một lượt nữa
                                  cho chính nó — chặn cho tới khi lượt đang chạy kết thúc. */}
                              <Tooltip label={inFlight(r) ? "Bài đang chờ/đang chấm — chưa chấm lại được" : "Chấm lại bài này"} side="left">
                                <button
                                  onClick={() => regrade(r)}
                                  disabled={regradingId === r.studentId || inFlight(r)}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-amber-50 hover:text-amber-600 disabled:opacity-50"
                                >
                                  {regradingId === r.studentId ? <Loader2 size={15} className="animate-spin" /> : <RotateCcw size={15} />}
                                </button>
                              </Tooltip>
                              {/* CSV của riêng bài này: dòng tổng hợp + danh sách testcase đạt/không.
                                  Có cả ở bài chưa có JSON — khi đó chỉ thiếu phần liệt kê testcase. */}
                              <Tooltip label={`Tải CSV của ${r.studentName || r.studentId}`} side="left">
                                <button
                                  onClick={() => exportRowCSV(r)}
                                  disabled={exportingId === r.studentId}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-emerald-50 hover:text-emerald-600 disabled:opacity-50"
                                >
                                  {exportingId === r.studentId
                                    ? <Loader2 size={15} className="animate-spin" />
                                    : <DownloadCloud size={15} />}
                                </button>
                              </Tooltip>
                              {r.hasJson && (
                                <>
                                  <Tooltip label={`Xem năng lực ${r.studentId}`} side="left">
                                    <button
                                      onClick={() => openDetail(r)}
                                      className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                    >
                                      <BarChart3 size={15} />
                                    </button>
                                  </Tooltip>
                                  <Tooltip label={`Tải JSON của ${r.studentId}`} side="left">
                                    <button
                                      onClick={() => downloadStudentJson(r)}
                                      className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                    >
                                      <FileJson size={15} />
                                    </button>
                                  </Tooltip>
                                </>
                              )}
                            </div>
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
      {/* Modal chi tiết năng lực 1 bài — render qua Portal ra body để không bị
          giam trong khung max-w-6xl (animate-fade-in-up tạo containing block). */}
      {detailRow && typeof document !== "undefined" && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm"
          onClick={closeDetail}
        >
          <div
            className="animate-modal-pop flex max-h-[88vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">
                  {detailRow.studentName || detailRow.studentId}
                </h3>
                <p className="font-mono text-xs text-slate-400">
                  {detailRow.studentId} · {selected}
                </p>
              </div>
              <button
                onClick={closeDetail}
                className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
              >
                <X size={18} />
              </button>
            </div>

            <div className="custom-scrollbar flex-1 space-y-5 overflow-y-auto p-6">
              {detailLoading ? (
                <div className="flex items-center justify-center py-12 text-slate-400">
                  <Loader2 size={22} className="animate-spin" />
                </div>
              ) : detail ? (
                <>
                  {/* Tóm tắt điểm */}
                  <div className="flex items-center justify-between rounded-xl bg-slate-50 px-5 py-4">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Điểm tự động</p>
                      <p className="text-2xl font-bold text-slate-800">
                        {detail.grading_result?.score != null ? detail.grading_result.score.toFixed(1) : "—"}
                        <span className="text-sm font-medium text-slate-400">/10</span>
                      </p>
                    </div>
                    <div className="text-right text-xs text-slate-500">
                      <p>
                        Kịch bản pass:{" "}
                        <span className="font-bold text-emerald-600">
                          {detail.grading_result?.passed_tests ?? 0}
                        </span>{" "}
                        / {detail.grading_result?.total_scenarios ?? detail.grading_result?.total_tests ?? 0}
                      </p>
                      {detail.grading_result?.total_criteria != null && (
                        <p>{detail.grading_result.total_criteria} tiêu chí rubric</p>
                      )}
                    </div>
                  </div>

                  <CompetencyPanel items={detail.competency_assessment} />

                  {/* Danh sách testcase */}
                  {detail.test_cases && detail.test_cases.length > 0 && (
                    <div className="space-y-2">
                      <p className="text-sm font-bold text-slate-700">Chi tiết testcase</p>
                      <div className="space-y-1.5">
                        {detail.test_cases.map((tc, i) => {
                          const status = (tc.status || "").toLowerCase();
                          const passed = status === "passed";
                          // not_run = CHƯA CÓ CƠ HỘI CHẠY, không phải làm sai. Tô cùng màu đỏ
                          // với testcase hỏng thật sẽ khiến GV đọc thành "sai 12 chỗ khác nhau"
                          // trong khi chỉ có MỘT nguyên nhân gốc.
                          const notRun = status === "not_run";
                          return (
                            <div
                              key={tc.test_id || i}
                              className="rounded-lg border border-slate-100 bg-white px-3 py-2"
                            >
                              <div className="flex items-center gap-2">
                                <span
                                  className={`h-1.5 w-1.5 shrink-0 rounded-full ${passed ? "bg-emerald-500" : notRun ? "bg-slate-300" : "bg-rose-500"}`}
                                />
                                <span className="min-w-0 flex-1 truncate text-xs font-medium text-slate-700">
                                  {tc.name || tc.test_id}
                                </span>
                                {notRun && (
                                  <span className="shrink-0 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
                                    Chưa chạy
                                  </span>
                                )}
                                {(tc.skill_name || tc.skill_code) && (
                                  <span
                                    className="shrink-0 rounded bg-indigo-50 px-1.5 py-0.5 text-[10px] font-medium text-indigo-600"
                                    title={`${tc.category_label ? tc.category_label + " · " : ""}${tc.skill_code || ""}`}
                                  >
                                    {tc.skill_name || tc.skill_code}
                                  </span>
                                )}
                                {tc.difficulty && (
                                  <span className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${DIFF_BADGE[tc.difficulty] || DIFF_BADGE.basic}`}>
                                    {DIFF_VI[tc.difficulty] || tc.difficulty}
                                  </span>
                                )}
                              </div>
                              {(tc.category_label || tc.skill_code) && (
                                <p className="mt-0.5 pl-3.5 text-[10px] text-slate-400">
                                  {tc.category_label}
                                  {tc.category_label && tc.skill_code ? " · " : ""}
                                  {tc.skill_code}
                                </p>
                              )}
                              {!passed && (tc.expected || tc.actual || tc.error_log || tc.error_code) && (
                                <FailureDetail
                                  requirement={tc.expected}
                                  actual={tc.actual || tc.error_log}
                                  errorCode={tc.error_code}
                                  actualSource={tc.actual_source}
                                />
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </>
              ) : (
                <div className="py-12 text-center text-sm text-slate-400">
                  Không tải được dữ liệu bài này.
                </div>
              )}
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Modal ĐỐI CHIẾU: bài làm SV (trái) ↔ testcase đã dùng (phải) + lý do lỗi + Chấm lại */}
      {viewRow && typeof document !== "undefined" && createPortal(
        <div
          className="animate-modal-overlay fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm"
          onClick={closeView}
        >
          <div
            className="animate-modal-pop flex max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-6 py-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-800">{viewRow.studentName || viewRow.studentId}</h3>
                <p className="font-mono text-xs text-slate-400">{viewRow.studentId} · {selected}</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => regrade(viewRow)}
                  disabled={regradingId === viewRow.studentId || inFlight(viewRow)}
                  title={inFlight(viewRow) ? "Bài đang chờ/đang chấm — chưa chấm lại được" : undefined}
                  className="flex items-center gap-1.5 rounded-lg bg-amber-500 px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-amber-600 disabled:opacity-50"
                >
                  {regradingId === viewRow.studentId ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />} Chấm lại
                </button>
                <button
                  onClick={closeView}
                  className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                >
                  <X size={18} />
                </button>
              </div>
            </div>

            {(viewRow.status === "ERROR" || viewRow.status === "MANUAL_REVIEW") && viewRow.errorLog && (
              <div className={`shrink-0 border-b px-6 py-3 ${viewRow.status === "MANUAL_REVIEW" ? "border-amber-100 bg-amber-50" : "border-rose-100 bg-rose-50"}`}>
                <p className={`mb-1 flex items-center gap-1.5 text-xs font-bold ${viewRow.status === "MANUAL_REVIEW" ? "text-amber-800" : "text-rose-700"}`}>
                  <AlertCircle size={13} /> {viewRow.status === "MANUAL_REVIEW" ? "Lý do cần chấm tay" : "Lý do lỗi"}
                </p>
                <p className={`custom-scrollbar max-h-28 overflow-y-auto whitespace-pre-wrap text-[11px] leading-relaxed ${viewRow.status === "MANUAL_REVIEW" ? "text-amber-700" : "text-rose-600"}`}>
                  {viewRow.diagnosticOrigin && <strong>{viewRow.diagnosticOrigin === "STUDENT" ? "Bài sinh viên" : viewRow.diagnosticOrigin === "TESTCASE" ? "Bộ testcase" : viewRow.diagnosticOrigin === "ENVIRONMENT" ? "Môi trường chấm" : "Chưa xác định"} · </strong>}
                  {viewRow.diagnosticStage && <span>{viewRow.diagnosticStage} · </span>}
                  {viewRow.diagnosticCode && <strong>[{viewRow.diagnosticCode}] </strong>}
                  {viewRow.errorLog}
                </p>
              </div>
            )}

            <div className="grid min-h-0 flex-1 grid-cols-1 md:grid-cols-2">
              <div className="flex min-h-0 flex-col border-b border-slate-100 md:border-b-0 md:border-r">
                <div className="flex shrink-0 items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-4 py-2">
                  <FileCode2 size={14} className="text-indigo-500" />
                  <span className="text-xs font-bold text-slate-700">Bài làm của SV</span>
                </div>
                {viewLoading ? (
                  <div className="flex h-full items-center justify-center py-10 text-slate-400"><Loader2 size={18} className="animate-spin" /></div>
                ) : (
                  <FileViewer files={subFiles} active={activeSub} setActive={setActiveSub}
                    empty="Không đọc được bài nộp (có thể đã bị dọn hoặc không lưu)." />
                )}
              </div>
              <div className="flex min-h-0 flex-col">
                <div className="flex shrink-0 items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-4 py-2">
                  <FileText size={14} className="text-violet-500" />
                  <span className="text-xs font-bold text-slate-700">Testcase đã dùng để chấm</span>
                </div>
                {viewLoading ? (
                  <div className="flex h-full items-center justify-center py-10 text-slate-400"><Loader2 size={18} className="animate-spin" /></div>
                ) : tcNotice ? (
                  <div className="flex h-full items-center justify-center px-6 py-10 text-center">
                    <p className="flex items-start gap-2 text-xs leading-relaxed text-amber-700">
                      <AlertCircle size={14} className="mt-0.5 shrink-0" /> {tcNotice}
                    </p>
                  </div>
                ) : (
                  <FileViewer files={tcFiles} active={activeTc} setActive={setActiveTc}
                    empty="Không đọc được testcase đã lưu." />
                )}
              </div>
            </div>
          </div>
        </div>,
        document.body
      )}
    </SidebarLayout>
  );
}

// Khung xem mã nguồn: tab file + vùng code (dùng cho cả bài làm SV lẫn testcase)
function FileViewer({ files, active, setActive, empty }: {
  files: CodeFile[]; active: number; setActive: (i: number) => void; empty: string;
}) {
  if (files.length === 0)
    return <div className="flex h-full items-center justify-center p-6 text-center text-xs text-slate-400">{empty}</div>;
  const safe = Math.min(active, files.length - 1);
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="custom-scrollbar flex shrink-0 gap-1 overflow-x-auto border-b border-slate-100 px-2 py-2">
        {files.map((f, i) => (
          <button
            key={f.name}
            onClick={() => setActive(i)}
            className={`shrink-0 rounded-md px-2.5 py-1 font-mono text-[11px] transition-colors ${
              i === safe ? "bg-indigo-100 text-indigo-700" : "text-slate-500 hover:bg-slate-100"
            }`}
          >
            {f.name.split("/").pop()}
          </button>
        ))}
      </div>
      <pre className="custom-scrollbar min-h-0 flex-1 overflow-auto bg-slate-900 p-3 text-[11px] leading-relaxed text-slate-100">
        <code>{files[safe]?.content}</code>
      </pre>
    </div>
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
    amber: "bg-amber-100 text-amber-700",
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
