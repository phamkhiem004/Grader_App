"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import ExamCombobox from "@/components/ui/ExamCombobox";

// Khóa lưu phiên chấm đang/ vừa chạy → rời trang rồi quay lại KHÔNG mất kết quả
const ACTIVE_BATCH_KEY = "grader_active_batch";
import { UploadCloud, Play, FileArchive, X, CheckCircle, Clock, AlertCircle, DownloadCloud, Loader2, CheckSquare, BarChart2, Users, TrendingUp, FileJson, StopCircle, Trash2, Ban } from "lucide-react";

export default function AutomaticGradingPage() {
  const [examId, setExamId] = useState("");
  const [examOptions, setExamOptions] = useState([]);
  const [examOptionsLoading, setExamOptionsLoading] = useState(true);
  const [files, setFiles] = useState([]);
  const [dragging, setDragging] = useState(false);
  const [batchId, setBatchId] = useState(null);
  const [progress, setProgress] = useState(null);
  const [phase, setPhase] = useState("idle"); // idle | uploading | polling | done
  const [uploadErr, setUploadErr] = useState(null);
  const [parseErrors, setParseErrors] = useState([]);
  const [resultFilter, setResultFilter] = useState("all");
  const [diagnosticFilter, setDiagnosticFilter] = useState("all");
  const [batchAction, setBatchAction] = useState(null);   // "stop" | "cancel" khi đang gọi API
  const [stopNotice, setStopNotice] = useState(null);
  const fileRef = useRef();
  const pollRef = useRef(null);

  // Keep the diagnostic helpers in the component scope. Next.js Fast Refresh can
  // replace only the component body during development; module-level helpers added
  // in the same refresh may otherwise be missing from the temporary eval scope.
  const diagnosticCategoryLabels = {
    dependency: "Thư viện ngoài không được phép",
    testcase_timeout: "Timeout do bộ testcase",
    compile: "Lỗi biên dịch",
    structure: "Class/cấu trúc không được phép",
    crash: "Ứng dụng không thể khởi động",
    testcase: "Lỗi bộ testcase",
    environment: "Lỗi môi trường chấm",
    undetermined: "Chưa xác định nguồn lỗi",
  };

  const hasLayoutFailureEvidence = (row) => {
    const directEvidence = `${row?.errorLog || ""} ${row?.diagnosticMessage || ""}`;
    if (/RenderFlex|overflowed by|LAYOUT_OVERFLOW|LAYOUT_ERROR/i.test(directEvidence)) return true;
    try {
      const details = JSON.parse(row?.details || "{}");
      return (details?.test_cases || []).some((testcase) => {
        const evidence = `${testcase?.error_code || ""} ${testcase?.actual || ""} ${testcase?.observation?.kind || ""}`;
        return /RenderFlex|overflowed by|LAYOUT_OVERFLOW|LAYOUT_ERROR/i.test(evidence);
      });
    } catch (_) {
      return false;
    }
  };

  const hasSourcePolicyEvidence = (row) => {
    const directEvidence = `${row?.errorLog || ""} ${row?.diagnosticMessage || ""}`;
    if (/SOURCE_POLICY_VIOLATION|forbidden token|token bị cấm|class (?:is )?not allowed|class không được phép/i.test(directEvidence)) {
      return true;
    }
    try {
      const details = JSON.parse(row?.details || "{}");
      return (details?.test_cases || []).some((testcase) => {
        const evidence = `${testcase?.error_code || ""} ${testcase?.actual || ""} ${testcase?.observation?.kind || ""}`;
        return /SOURCE_POLICY_VIOLATION|forbidden token|token bị cấm|class (?:is )?not allowed|class không được phép/i.test(evidence);
      });
    } catch (_) {
      return false;
    }
  };

  const getDiagnosticInfo = (row) => {
    const code = String(row?.diagnosticCode || "").toUpperCase();
    const origin = String(row?.diagnosticOrigin || "").toUpperCase();
    const stage = String(row?.diagnosticStage || "").toUpperCase();
    const status = String(row?.status || "").toUpperCase();
    if (!code && status !== "ERROR" && status !== "MANUAL_REVIEW") return null;
    const sourcePolicyViolation = code.includes("SOURCE_POLICY")
      || code.includes("FORBIDDEN_CLASS")
      || code.includes("CLASS_NOT_ALLOWED")
      || code.includes("SUBMISSION_STRUCTURE")
      || hasSourcePolicyEvidence(row);

    // Đây là cột sự cố của pipeline chấm, không phải bản tóm tắt mọi testcase fail.
    // Render/validation/behavior sai vẫn được giữ đầy đủ trong JSON và điểm số.
    const isOrdinaryTestFailure = code.includes("REQUIREMENTS_NOT_MET")
      || code.includes("TESTS_FAILED")
      || (code === "CONTRACT_VIOLATION" && !sourcePolicyViolation)
      || code.includes("LAYOUT_OVERFLOW")
      || code.includes("BUILD_ERROR");
    if (isOrdinaryTestFailure || hasLayoutFailureEvidence(row)) return null;

    const runtimeAssertion = ["NULL_ERROR", "TYPE_ERROR", "RANGE_ERROR", "FORMAT_ERROR",
      "STATE_ERROR", "NO_SUCH_METHOD", "EXCEPTION_THROWN"].some((value) => code.includes(value));
    if (runtimeAssertion && stage !== "APP_BOOT") return null;

    let category;
    if (code.includes("EXTERNAL_PACKAGE") || code.includes("DEPENDENCY")) category = "dependency";
    else if (code.includes("TIMEOUT") || code.includes("WATCHDOG")) {
      // Timeout trong main(), hàm hoặc thao tác của sinh viên là kết quả bài làm và
      // đã nằm trong JSON. Cột này chỉ gọi là timeout testcase khi backend đã có
      // bằng chứng origin=TESTCASE; không suy đoán từ một chuỗi "timed out" chung.
      if (origin !== "TESTCASE") return null;
      category = "testcase_timeout";
    }
    else if (code.includes("COMPILE")) category = "compile";
    else if (sourcePolicyViolation) category = "structure";
    else if (runtimeAssertion && stage === "APP_BOOT") category = "crash";
    else if (code.includes("CRASH") || code.includes("APP_BOOT_ERROR")) category = "crash";
    else if (origin === "TESTCASE") category = "testcase";
    else if (origin === "ENVIRONMENT") category = "environment";
    else if (origin === "UNDETERMINED" || status === "MANUAL_REVIEW" || status === "ERROR") category = "undetermined";
    else return null;

    const scope = origin === "STUDENT" ? "student" : "system";
    const tone = status === "MANUAL_REVIEW" || row?.requiresManualReview
      ? "border-amber-200 bg-amber-50 text-amber-800"
      : origin === "TESTCASE" || origin === "ENVIRONMENT" || origin === "UNDETERMINED"
        ? "border-violet-200 bg-violet-50 text-violet-700"
        : "border-rose-200 bg-rose-50 text-rose-700";

    return { category, scope, label: diagnosticCategoryLabels[category], tone };
  };

  const formatDiagnosticStage = (stage) => {
    const labels = {
      DEPENDENCY_PREFLIGHT: "Kiểm tra thư viện",
      COMPILE: "Biên dịch",
      SOURCE_CONTRACT: "Contract mã nguồn",
      SOURCE_POLICY: "Chính sách class/mã nguồn",
      APP_BOOT: "Khởi động ứng dụng",
      STUDENT_UI_ACTION: "Thao tác giao diện",
      STUDENT_ASYNC_SETTLE: "Chờ xử lý bất đồng bộ",
      STUDENT_FUNCTION: "Hàm sinh viên",
      TESTCASE_EXECUTION: "Thực thi testcase",
      CONTAINER: "Docker sandbox",
    };
    return labels[stage] || stage || "";
  };

  // Nạp toàn bộ bộ testcase đã tạo để giáo viên chọn nhanh; không giới hạn ở bộ testcase đã chấm.
  useEffect(() => {
    let cancelled = false;
    fetch(`${API_BASE}/exam-setup/list`)
      .then((r) => (r.ok ? r.json() : []))
      .then((data) => {
        if (cancelled) return;
        const options = Array.isArray(data)
          ? data
              .filter((e) => e?.examId)
              .map((e) => ({ examId: String(e.examId), examName: e.examName || String(e.examId) }))
          : [];
        setExamOptions(options);
      })
      .catch(() => { if (!cancelled) setExamOptions([]); })
      .finally(() => { if (!cancelled) setExamOptionsLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // ── Khôi phục phiên chấm khi quay lại trang — CHỈ khi batch còn ĐANG chấm dở ──
  // Batch đã chấm XONG (hoặc rỗng/đã xóa) sẽ KHÔNG khôi phục → F5 cho ra trang nhập mới;
  // muốn xem lại kết quả thì vào trang Lịch sử.
  useEffect(() => {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(ACTIVE_BATCH_KEY) || "null"); } catch {}
    if (!saved?.batchId) return;

    fetch(`${API_BASE}/batch/progress/${saved.batchId}`)
      .then(r => r.json())
      .then(data => {
        const pending = (data?.queued || 0) + (data?.grading || 0);
        // Lỗi đọc tiến độ / batch rỗng-đã xóa / đã chấm xong → bỏ phiên lưu, giữ nguyên trang nhập mới.
        if (!data || data.total == null || pending === 0) {
          try { localStorage.removeItem(ACTIVE_BATCH_KEY); } catch {}
          return;
        }
        // Còn bài đang/chờ chấm → khôi phục để theo dõi tiếp tiến độ.
        setExamId(saved.examId || "");
        setParseErrors(saved.parseErrors || []);
        setBatchId(saved.batchId);
        setProgress(data);
        setPhase("polling");
        startPolling(saved.batchId);
      })
      .catch(() => {});
  }, []);

  // Dọn interval khi rời trang (tránh setState trên component đã unmount)
  useEffect(() => () => clearInterval(pollRef.current), []);

  // Helper function to make error messages human-readable
  const formatErrorMsg = (errStr) => {
    if (typeof errStr !== 'string') return errStr;
    const parts = errStr.split(': ');
    if (parts.length < 2) return errStr;

    const fileName = parts[0];
    const errMsg = parts.slice(1).join(': ');

    if (errMsg.includes('Duplicate entry')) {
      return `${fileName}: Đã có kết quả trên hệ thống (Lỗi trùng lặp bài thi).`;
    }

    if (errMsg.includes('could not execute statement') || errMsg.includes('SQL') || errMsg.includes('Constraint')) {
      return `${fileName}: Lỗi cơ sở dữ liệu khi lưu kết quả.`;
    }

    return errStr; // For things like "Sai format — cần: MaSV_Ten.zip"
  };

  // File handling
  const addFiles = useCallback((incoming) => {
    const zips = Array.from(incoming).filter(f => f.name.endsWith(".zip"));
    setFiles(prev => {
      const existing = new Set(prev.map(f => f.name));
      return [...prev, ...zips.filter(f => !existing.has(f.name))];
    });
    setUploadErr(null);
  }, []);

  const onDrop = useCallback((e) => {
    e.preventDefault(); setDragging(false);
    addFiles(e.dataTransfer.files);
  }, [addFiles]);

  const removeFile = (name) => setFiles(f => f.filter(x => x.name !== name));

  // Upload + poll
  const execute = async () => {
    if (phase === "uploading" || phase === "polling") return;   // chống bấm nhiều lần
    if (!files.length) { setUploadErr("Chưa có file nào để chấm."); return; }
    if (!examId.trim()) { setUploadErr("Vui lòng nhập mã bộ testcase."); return; }

    setPhase("uploading"); setUploadErr(null); setParseErrors([]); setStopNotice(null);
    setResultFilter("all"); setDiagnosticFilter("all");

    const form = new FormData();
    form.append("examId", examId.trim());
    files.forEach(f => form.append("files", f));

    try {
      const res = await fetch(`${API_BASE}/batch/upload`, { method: "POST", body: form });
      const data = await res.json();

      if (!res.ok) { setUploadErr(data.error || "Lỗi server."); setPhase("idle"); return; }

      setBatchId(data.batchId);
      if (data.parseErrors?.length) setParseErrors(data.parseErrors);

      // Lưu lại để khi rời trang → quay lại vẫn còn kết quả (đọc lại từ backend)
      try {
        localStorage.setItem(ACTIVE_BATCH_KEY, JSON.stringify({
          batchId: data.batchId, examId: examId.trim(), parseErrors: data.parseErrors || [],
        }));
      } catch (_) {}

      setPhase("polling");
      startPolling(data.batchId);
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
      setPhase("idle");
    }
  };

  const startPolling = (bid) => {
    clearInterval(pollRef.current);   // tránh chạy 2 interval song song
    pollRef.current = setInterval(async () => {
      try {
        const res = await fetch(`${API_BASE}/batch/progress/${bid}`);
        const data = await res.json();
        setProgress(data);
        const pending = data.queued + data.grading;
        if (pending === 0) {
          clearInterval(pollRef.current);
          setPhase("done");
          // Đã chấm xong → xóa phiên lưu để lần F5 sau ra trang mới (kết quả vẫn xem ở trang Lịch sử).
          try { localStorage.removeItem(ACTIVE_BATCH_KEY); } catch (_) {}
        }
      } catch (_) { }
    }, 3000);
  };

  const reset = () => {
    clearInterval(pollRef.current);
    try { localStorage.removeItem(ACTIVE_BATCH_KEY); } catch (_) {}
    setFiles([]); setBatchId(null); setProgress(null);
    setPhase("idle"); setUploadErr(null); setParseErrors([]); setStopNotice(null);
    setResultFilter("all"); setDiagnosticFilter("all");
  };

  // ── Dừng / hủy phiên chấm đang chạy ────────────────────────────
  // Dừng  = bỏ các bài chưa chấm + giết container đang chạy, GIỮ kết quả đã có.
  // Hủy   = dừng rồi XÓA sạch kết quả và file bài nộp của phiên này (không hoàn tác được).
  const stopGrading = async () => {
    if (!batchId || batchAction) return;
    setBatchAction("stop");
    try {
      const res = await fetch(`${API_BASE}/batch/${batchId}/stop`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setUploadErr(data.error || "Không dừng được phiên chấm."); return; }
      const skipped = (data.dequeued || 0) + (data.cancelled || 0);
      setStopNotice(`Đã dừng phiên chấm: ${skipped} bài chưa chấm bị bỏ qua`
        + (data.killedContainers ? `, ${data.killedContainers} bài đang chấm bị ngắt` : "")
        + ". Kết quả của các bài đã chấm xong vẫn được giữ nguyên.");
      try { localStorage.removeItem(ACTIVE_BATCH_KEY); } catch (_) {}
      // KHÔNG tự chuyển sang "done" ở đây: vòng poll sẵn có sẽ tự kết thúc khi container cuối
      // cùng thực sự thoát — nếu giết hụt thì người dùng phải thấy nó vẫn đang chạy.
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
    } finally {
      setBatchAction(null);
    }
  };

  const cancelGrading = async () => {
    if (!batchId || batchAction) return;
    if (!confirm("Hủy phiên chấm này?\n\nToàn bộ kết quả đã chấm và file bài nộp của phiên sẽ bị XÓA "
      + "(bài đã có điểm chấm tay được giữ lại). Thao tác này không hoàn tác được.")) return;
    setBatchAction("cancel");
    try {
      const res = await fetch(`${API_BASE}/batch/${batchId}/cancel`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setUploadErr(data.error || "Không hủy được phiên chấm."); return; }
      reset();
      setStopNotice(`Đã hủy phiên chấm và xóa ${data.deleted || 0} bản ghi kết quả`
        + (data.keptManual ? `, giữ lại ${data.keptManual} bài đã chấm tay` : "") + ".");
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
    } finally {
      setBatchAction(null);
    }
  };

  const downloadCSV = () => {
    if (!progress?.results?.length && !parseErrors.length) return;

    const header = "Mã SV,Họ tên,Điểm,Trạng thái,Ghi chú\n";

    // 1. Các bài hợp lệ đã nạp vào server
    const validRows = (progress?.results || []).map(r => {
      let note = "";
      if (r.status === "ERROR") note = "Lỗi khi chấm (thường do lỗi compile hoặc crash)";
      if (r.status === "CANCELLED") note = "Chưa chấm — phiên chấm đã bị dừng";
      if (r.status === "MANUAL_REVIEW") {
        note = `Cần chấm tay: ${r.diagnosticCode || "UNCLASSIFIED"} · ${r.diagnosticOrigin || "UNDETERMINED"} · ${r.errorLog || ""}`;
      }
      if (r.status === "DONE") {
        try {
          const d = JSON.parse(r.details || "{}");
          note = `Pass: ${d.soTestPass ?? 0}/${d.tongSoTest ?? 0}`;
        } catch (_) {}
      }
      return `${r.studentId},"${r.studentName || ""}",${r.score != null ? r.score.toFixed(1) : ""},${r.status},"${note}"`;
    });

    // 2. Các file lỗi/từ chối ngay từ đầu (parseErrors)
    const errorRows = parseErrors.map(errStr => {
      if (typeof errStr !== 'string') return "";
      const parts = errStr.split(': ');
      const filename = parts[0] || errStr;

      const fileParts = filename.replace('.zip', '').split('_');
      const studentId = fileParts[0] || filename;
      const studentName = fileParts.slice(1).join(' ') || "";

      const cleanedMsg = formatErrorMsg(errStr).replace(/"/g, '""');
      return `${studentId},"${studentName}","",BỊ LOẠI,"${cleanedMsg}"`;
    }).filter(row => row !== "");

    const allRows = [...validRows, ...errorRows].join("\n");

    const blob = new Blob(["﻿" + header + allRows], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);

    // Tạo tên file định dạng: Mã bộ testcase_YYYY-MM-DD.csv
    const dateStr = new Date().toISOString().split('T')[0];
    a.download = `${examId}_${dateStr}.csv`;

    a.click();
  };

  // Tải JSON đầy đủ của cả batch để lưu trữ/đối chiếu.
  const downloadResultsJson = async () => {
    if (!batchId) return;
    try {
      const res = await fetch(`${API_BASE}/results/batch/${batchId}`);
      const text = await res.text();
      const blob = new Blob([text], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      const dateStr = new Date().toISOString().split("T")[0];
      a.download = `${examId}_results_${dateStr}.json`;
      a.click();
    } catch (_) {}
  };

  // Tải JSON riêng của 1 sinh viên → MaSV.json
  const downloadStudentJson = async (r) => {
    const exId = r.examId || examId;
    try {
      const res = await fetch(`${API_BASE}/results/${encodeURIComponent(exId)}/${encodeURIComponent(r.studentId)}`);
      if (!res.ok) return;
      const text = await res.text();
      const blob = new Blob([text], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${r.studentId}.json`;
      a.click();
    } catch (_) {}
  };

  const isRunning = phase === "uploading" || phase === "polling";
  const p = progress;

  const totalItems = (p?.total || 0) + parseErrors.length;
  const errorItems = (p?.error || 0) + parseErrors.length;
  const manualItems = p?.manualReview || 0;
  const doneItems = p?.done || 0;
  const gradingItems = p?.grading || 0;
  const cancelledItems = p?.cancelled || 0;   // bài bị bỏ do người dùng dừng phiên

  // Bài bị dừng cũng là "đã xử lý xong": không tính vào đây thì thanh tiến độ đứng mãi dưới 100%.
  const processedItems = doneItems + errorItems + manualItems + cancelledItems;
  const pct = totalItems > 0 ? Math.round((processedItems / totalItems) * 100) : 0;
  const donePct = totalItems > 0 ? Math.round((doneItems / totalItems) * 100) : 0;
  const errPct = totalItems > 0 ? Math.round((errorItems / totalItems) * 100) : 0;
  const reviewPct = totalItems > 0 ? Math.round((manualItems / totalItems) * 100) : 0;
  const cancelPct = totalItems > 0 ? Math.round((cancelledItems / totalItems) * 100) : 0;

  const totalSize = files.reduce((s, f) => s + f.size, 0);

  const allResultRows = p?.results || [];
  // Luôn hiển thị đủ danh mục, kể cả lô hiện tại có 0 bài ở loại đó. Trước đây
  // "Thư viện ngoài" biến mất khi không có ca vi phạm, khiến người dùng tưởng
  // hệ thống chưa hỗ trợ nhận diện dependency.
  const diagnosticCategoryCounts = allResultRows.reduce((counts, row) => {
    const category = getDiagnosticInfo(row)?.category;
    if (category) counts[category] = (counts[category] || 0) + 1;
    return counts;
  }, {});
  const availableDiagnosticCategories = Object.keys(diagnosticCategoryLabels);
  const filteredResultRows = allResultRows.filter((row) => {
    const diagnostic = getDiagnosticInfo(row);
    const hasIncident = Boolean(diagnostic);
    const matchesResult = resultFilter === "all"
      || (resultFilter === "incidents" && hasIncident)
      || (resultFilter === "student" && diagnostic?.scope === "student")
      || (resultFilter === "system" && diagnostic?.scope === "system")
      || (resultFilter === "manual" && row.status === "MANUAL_REVIEW")
      || (resultFilter === "error" && row.status === "ERROR");
    const matchesDiagnostic = diagnosticFilter === "all"
      || diagnostic?.category === diagnosticFilter;
    return matchesResult && matchesDiagnostic;
  });

  return (
    <SidebarLayout title="Chấm bài tự động" subtitle="Chấm tự động bài thi Flutter trong môi trường Docker cô lập" activePath="/teacher/grading">
      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[minmax(300px,0.75fr)_minmax(0,2.25fr)]">

        {/* Cột trái: Form cấu hình & Upload */}
        <div className="min-w-0 space-y-6">
          <div className="card overflow-hidden">
            {/* Header gradient */}
            <div className="flex items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-blue-50 px-6 py-4">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-sm">
                <CheckSquare size={18} />
              </div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">Thiết lập phiên chấm bài</h2>
                <p className="text-xs text-slate-500">Chọn bộ testcase và tải bài nộp</p>
              </div>
            </div>

            <div className="space-y-4 p-6">
              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Mã Bộ Testcase</label>
                <ExamCombobox
                  options={examOptions}
                  value={examId}
                  onChange={setExamId}
                  disabled={isRunning}
                  ariaLabel="Mã bộ testcase"
                  placeholder={examOptionsLoading ? "Đang tải danh sách bộ testcase..." : "Nhập hoặc chọn mã bộ testcase..."}
                />
              </div>

              {phase === "idle" && (
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Upload Bài Nộp</label>
                  <div
                    onDrop={onDrop}
                    onDragOver={e => { e.preventDefault(); setDragging(true); }}
                    onDragLeave={() => setDragging(false)}
                    onClick={() => fileRef.current.click()}
                    className={`cursor-pointer rounded-xl border-2 border-dashed p-8 text-center transition-all ${
                      dragging ? "border-indigo-500 bg-indigo-50 scale-[1.01]" : "border-slate-200 bg-slate-50 hover:border-indigo-300 hover:bg-slate-100"
                    }`}
                  >
                    <div className={`mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full border border-slate-100 bg-white shadow-sm transition-transform ${dragging ? "scale-110" : ""}`}>
                      <UploadCloud size={24} className={dragging ? "text-indigo-500" : "text-slate-400"} />
                    </div>
                    <p className="mb-1 text-sm font-semibold text-slate-700">Kéo thả file ZIP vào đây</p>
                    <p className="text-xs text-slate-500">Hoặc click để duyệt. Định dạng: <span className="font-mono text-slate-600">MaSV_HoTen.zip</span></p>
                    <input ref={fileRef} type="file" multiple accept=".zip" className="hidden" onChange={e => addFiles(e.target.files)} />
                  </div>
                </div>
              )}

              {/* Lỗi Upload */}
              {uploadErr && (
                <div className="flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-600">
                  <AlertCircle size={18} className="mt-0.5 shrink-0" />
                  <p className="text-sm font-medium">{uploadErr}</p>
                </div>
              )}

              {/* File bị bỏ qua — phân loại rõ từng loại lỗi */}
              {parseErrors.length > 0 && (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
                  <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-amber-800">
                    <AlertCircle size={16} /> {parseErrors.length} file bị bỏ qua
                  </h3>
                  <ul className="space-y-2">
                    {parseErrors.map((err, i) => {
                      const c = categorizeError(err);
                      const badge = ERROR_TONES[c.tone] || ERROR_TONES.slate;
                      return (
                        <li key={i} className="rounded-lg border border-amber-100 bg-white/70 p-2.5">
                          <div className="mb-1 flex items-center gap-2">
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide ${badge}`}>{c.type}</span>
                            {c.file && <span className="truncate font-mono text-xs text-slate-500">{c.file}</span>}
                          </div>
                          <p className="break-words text-xs text-slate-600">{c.detail}</p>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              )}

              {/* Nút Execute */}
              {phase === "idle" && (
                <button
                  onClick={execute}
                  disabled={files.length === 0}
                  className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-600 to-blue-600 px-4 py-3.5 font-semibold text-white shadow-sm shadow-indigo-600/20 transition-all hover:from-indigo-700 hover:to-blue-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300 disabled:shadow-none"
                >
                  <Play size={18} />
                  Bắt đầu chấm ({files.length} bài)
                </button>
              )}

              {/* Loading State */}
              {phase === "uploading" && (
                <div className="rounded-xl border border-indigo-100 bg-indigo-50 p-6 text-center">
                  <Loader2 size={28} className="mx-auto mb-3 animate-spin text-indigo-600" />
                  <h3 className="mb-1 text-sm font-bold text-indigo-900">Đang tải dữ liệu lên...</h3>
                  <p className="text-xs text-indigo-700/80">Đang upload {files.length} bài thi lên server</p>
                </div>
              )}
            </div>
          </div>

          {/* Danh sách file đang chọn */}
          {files.length > 0 && phase === "idle" && (
            <div className="card flex max-h-[420px] flex-col overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-5 py-4">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold uppercase tracking-wider text-slate-500">File đã chọn ({files.length})</span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-500">{(totalSize / 1024 / 1024).toFixed(1)} MB</span>
                </div>
                <button onClick={() => setFiles([])} className="text-xs font-semibold text-rose-500 transition-colors hover:text-rose-700">Xóa hết</button>
              </div>
              <div className="custom-scrollbar overflow-y-auto p-2">
                {files.map((f) => (
                  <div key={f.name} className="group flex items-center gap-3 rounded-lg p-3 transition-colors hover:bg-slate-50">
                    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-500">
                      <FileArchive size={15} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-slate-700">{f.name}</p>
                      <p className="text-xs text-slate-400">{(f.size / 1024).toFixed(0)} KB</p>
                    </div>
                    <button onClick={() => removeFile(f.name)} className="p-1 text-slate-300 opacity-0 transition-opacity hover:text-rose-500 group-hover:opacity-100">
                      <X size={16} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Cột phải: Tiến độ & Kết quả */}
        <div className="min-w-0 space-y-6">
          {/* Đặt NGOÀI thẻ tiến độ: sau khi Hủy, phase quay về "idle" nên thẻ đó không còn render */}
          {stopNotice && (
            <div className="flex items-start gap-2.5 rounded-xl border border-slate-200 bg-slate-50 p-4 text-slate-600">
              <Ban size={16} className="mt-0.5 shrink-0 text-slate-400" />
              <p className="text-xs font-medium leading-relaxed">{stopNotice}</p>
              <button onClick={() => setStopNotice(null)} className="ml-auto shrink-0 text-slate-300 transition-colors hover:text-slate-500">
                <X size={14} />
              </button>
            </div>
          )}
          {(phase === "polling" || phase === "done") ? (
            <>
              {/* Thống kê nhanh */}
              <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
                <StatCard label="Tổng số bài" value={totalItems} icon={Users} tone="slate" />
                <StatCard label="Hoàn thành" value={doneItems} icon={CheckCircle} tone="emerald" />
                <StatCard label="Đang chấm" value={gradingItems} icon={Clock} tone="blue" pulse={gradingItems > 0} />
                <StatCard label="Cần chấm tay" value={manualItems} icon={AlertCircle} tone="amber" />
                <StatCard label="Bị lỗi" value={errorItems} icon={AlertCircle} tone="rose" />
              </div>

              {/* Thanh tiến độ */}
              <div className="card p-6">
                <div className="mb-4 flex items-end justify-between">
                  <div>
                    <h3 className="flex items-center gap-2 text-base font-bold text-slate-800">
                      <TrendingUp size={18} className="text-indigo-500" /> Tiến độ thực thi
                    </h3>
                    <p className="mt-1 text-xs font-medium text-slate-500">
                      Batch: <span className="rounded bg-slate-100 px-2 py-0.5 font-mono text-slate-700">{batchId}</span>
                    </p>
                  </div>
                  <div className="text-right">
                    <span className="text-3xl font-bold text-slate-800">{pct}<span className="text-lg text-slate-400">%</span></span>
                    {phase === "polling" && (
                      <p className="flex items-center justify-end gap-1.5 text-xs font-semibold text-blue-600">
                        <span className="relative flex h-2 w-2">
                          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400 opacity-75"></span>
                          <span className="relative inline-flex h-2 w-2 rounded-full bg-blue-500"></span>
                        </span>
                        Đang chạy
                      </p>
                    )}
                  </div>
                </div>

                <div className="flex h-3 w-full overflow-hidden rounded-full bg-slate-100">
                  <div className="h-full bg-gradient-to-r from-emerald-400 to-emerald-500 transition-all duration-500 ease-out" style={{ width: `${donePct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-amber-400 to-amber-500 transition-all duration-500 ease-out" style={{ width: `${reviewPct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-rose-400 to-rose-500 transition-all duration-500 ease-out" style={{ width: `${errPct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-slate-300 to-slate-400 transition-all duration-500 ease-out" style={{ width: `${cancelPct}%` }}></div>
                </div>
                <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                  <span>
                    <span className="font-semibold text-emerald-600">{doneItems}</span> xong · <span className="font-semibold text-amber-600">{manualItems}</span> chấm tay · <span className="font-semibold text-rose-600">{errorItems}</span> lỗi
                    {cancelledItems > 0 && <> · <span className="font-semibold text-slate-600">{cancelledItems}</span> đã dừng</>}
                  </span>
                  <span>{processedItems}/{totalItems} đã xử lý</span>
                </div>

                {(phase === "polling" || phase === "done") && (
                  <div className="mt-4 flex flex-wrap items-center justify-end gap-2 border-t border-slate-100 pt-4">
                    {phase === "polling" && (
                      <>
                        <button
                          onClick={stopGrading}
                          disabled={batchAction !== null}
                          title="Ngừng chấm các bài còn lại, giữ nguyên kết quả đã có"
                          className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-sm font-semibold text-amber-700 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {batchAction === "stop"
                            ? <Loader2 size={15} className="animate-spin" />
                            : <StopCircle size={15} />}
                          Dừng chấm
                        </button>
                        <button
                          onClick={cancelGrading}
                          disabled={batchAction !== null}
                          title="Dừng và xóa toàn bộ kết quả + bài nộp của phiên chấm này"
                          className="flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50 px-4 py-2 text-sm font-semibold text-rose-600 transition-colors hover:bg-rose-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {batchAction === "cancel"
                            ? <Loader2 size={15} className="animate-spin" />
                            : <Trash2 size={15} />}
                          Hủy phiên chấm
                        </button>
                      </>
                    )}
                    {phase === "done" && (
                      <button onClick={reset} className="rounded-lg bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-600 transition-colors hover:bg-indigo-100">
                        + Chấm kỳ thi mới
                      </button>
                    )}
                  </div>
                )}
              </div>

              {/* Bảng kết quả */}
              <div className="card min-w-0 overflow-hidden">
                <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-6 py-4">
                  <h3 className="text-sm font-bold uppercase tracking-wider text-slate-700">Chi tiết kết quả</h3>
                  {phase === "done" && (
                    <div className="flex items-center gap-2">
                      <button onClick={downloadResultsJson} title="Tải JSON kết quả đầy đủ" className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95">
                        <FileJson size={16} /> JSON
                      </button>
                      <button onClick={downloadCSV} className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95">
                        <DownloadCloud size={16} /> Xuất CSV
                      </button>
                    </div>
                  )}
                </div>

                <div className="flex flex-wrap items-end gap-3 border-b border-slate-100 bg-white px-6 py-3">
                  <label className="min-w-[190px] text-xs font-semibold text-slate-500">
                    Phạm vi hiển thị
                    <select
                      value={resultFilter}
                      onChange={(event) => setResultFilter(event.target.value)}
                      className="mt-1 block w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                    >
                      <option value="all">Tất cả bài</option>
                      <option value="incidents">Có sự cố chặn chấm</option>
                      <option value="student">Sự cố từ bài làm</option>
                      <option value="system">Sự cố testcase/môi trường</option>
                      <option value="manual">Cần chấm tay</option>
                      <option value="error">Lỗi thực thi</option>
                    </select>
                  </label>
                  <label className="min-w-[210px] text-xs font-semibold text-slate-500">
                    Loại sự cố
                    <select
                      value={diagnosticFilter}
                      onChange={(event) => setDiagnosticFilter(event.target.value)}
                      className="mt-1 block w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 outline-none transition focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                    >
                      <option value="all">Tất cả loại sự cố</option>
                      {availableDiagnosticCategories.map((category) => (
                        <option key={category} value={category}>
                          {diagnosticCategoryLabels[category] || category} ({diagnosticCategoryCounts[category] || 0})
                        </option>
                      ))}
                    </select>
                  </label>
                  <div className="pb-2 text-xs font-medium text-slate-400">
                    Hiển thị {filteredResultRows.length}/{allResultRows.length} bài
                  </div>
                </div>

                <div className="max-w-full overflow-x-auto">
                  <table className="w-full min-w-[900px] table-fixed border-collapse text-left">
                    <colgroup>
                      <col className="w-[24%]" />
                      <col className="w-[13%]" />
                      <col className="w-[17%]" />
                      <col className="w-[28%]" />
                      <col className="w-[10%]" />
                      <col className="w-[8%]" />
                    </colgroup>
                    <thead>
                      <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                        <th className="px-4 py-3.5">Sinh viên</th>
                        <th className="px-3 py-3.5 text-center">Trạng thái</th>
                        <th className="px-3 py-3.5">Tỉ lệ Pass</th>
                        <th className="px-3 py-3.5">Sự cố chặn chấm</th>
                        <th className="px-3 py-3.5 text-right">Điểm số</th>
                        <th className="px-3 py-3.5 text-center">JSON</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredResultRows.map((r) => {
                        let passCount = 0, totalCount = 0;
                        try {
                          const d = JSON.parse(r.details || "{}");
                          passCount = d.soTestPass ?? 0;
                          totalCount = d.tongSoTest ?? 0;
                        } catch (_) {}

                        const isDone = r.status === "DONE";
                        const isError = r.status === "ERROR";
                        const isManual = r.status === "MANUAL_REVIEW";
                        const isGrading = r.status === "GRADING";
                        const isCancelled = r.status === "CANCELLED";
                        const ratio = totalCount > 0 ? Math.round((passCount / totalCount) * 100) : 0;
                        const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                        const diagnosticScope = r.diagnosticOrigin === "STUDENT" ? "Bài sinh viên" :
                          r.diagnosticOrigin === "TESTCASE" ? "Bộ testcase" :
                          r.diagnosticOrigin === "ENVIRONMENT" ? "Môi trường chấm" :
                          r.diagnosticOrigin === "UNDETERMINED" ? "Chưa xác định" : "";
                        const diagnostic = getDiagnosticInfo(r);

                        return (
                          <tr key={r.id} className="transition-colors hover:bg-slate-50/70">
                            <td className="px-4 py-3.5">
                              <div className="flex items-center gap-3">
                                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                                  {initials}
                                </div>
                                <div className="min-w-0">
                                  <p className="truncate text-sm font-semibold text-slate-800">{r.studentName || "—"}</p>
                                  <p className="font-mono text-xs text-slate-400">{r.studentId}</p>
                                </div>
                              </div>
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
                                isDone ? 'bg-emerald-100 text-emerald-700' :
                                isManual ? 'bg-amber-100 text-amber-800' :
                                isError ? 'bg-rose-100 text-rose-700' :
                                isGrading ? 'bg-blue-100 text-blue-700' :
                                'bg-slate-100 text-slate-600'
                              }`}>
                                <span className={`h-1.5 w-1.5 rounded-full ${
                                  isDone ? 'bg-emerald-500' : isManual ? 'bg-amber-500' : isError ? 'bg-rose-500' : isGrading ? 'bg-blue-500 animate-pulse' : 'bg-slate-400'
                                }`}></span>
                                {isDone ? 'Đã xong' : isManual ? 'Cần chấm tay' : isError ? 'Lỗi' : isGrading ? 'Đang chấm' : isCancelled ? 'Đã dừng' : 'Chờ'}
                              </span>
                            </td>
                            <td className="px-3 py-3.5">
                              {(isDone || isManual) ? (
                                <div className="flex items-center gap-2">
                                  <div className="h-1.5 min-w-8 flex-1 overflow-hidden rounded-full bg-slate-100">
                                    <div className={`h-full rounded-full ${ratio >= 50 ? 'bg-emerald-500' : 'bg-amber-500'}`} style={{ width: `${ratio}%` }}></div>
                                  </div>
                                  <span className="text-xs font-medium text-slate-500">{passCount}/{totalCount}</span>
                                </div>
                              ) : <span className="text-slate-300">—</span>}
                            </td>
                            <td className="px-3 py-3.5">
                              {diagnostic ? (
                                <div
                                  className={`w-full min-w-0 rounded-lg border px-2.5 py-2 ${diagnostic.tone}`}
                                  title={[r.diagnosticCode, diagnosticScope, r.diagnosticStage, r.errorLog].filter(Boolean).join(" · ")}
                                >
                                  <div className="flex items-center gap-1.5 text-xs font-bold">
                                    <AlertCircle size={13} className="shrink-0" />
                                    <span className="truncate">{diagnostic.label}</span>
                                  </div>
                                  <p className="mt-1 truncate font-mono text-[10px] opacity-80">
                                    {r.diagnosticCode || "CHƯA PHÂN LOẠI"}
                                  </p>
                                  {(diagnosticScope || r.diagnosticStage) && (
                                    <p className="mt-0.5 truncate text-[10px] font-medium opacity-80">
                                      {[diagnosticScope, formatDiagnosticStage(r.diagnosticStage)].filter(Boolean).join(" · ")}
                                    </p>
                                  )}
                                </div>
                              ) : (
                                <span className="text-xs text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-3 py-3.5 text-right">
                              {r.score != null ? (
                                <span className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${r.score >= PASS_THRESHOLD ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}>
                                  {r.score.toFixed(1)}
                                </span>
                              ) : (
                                <span className="font-medium text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {(isDone || isManual) && (
                                <button
                                  onClick={() => downloadStudentJson(r)}
                                  title={`Tải JSON của ${r.studentId}`}
                                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600"
                                >
                                  <FileJson size={15} />
                                </button>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                      {allResultRows.length === 0 && (
                        <tr>
                          <td colSpan="6" className="px-6 py-10 text-center text-sm text-slate-500">
                            <Loader2 size={20} className="mx-auto mb-2 animate-spin text-slate-300" />
                            Đang chờ dữ liệu...
                          </td>
                        </tr>
                      )}
                      {allResultRows.length > 0 && filteredResultRows.length === 0 && (
                        <tr>
                          <td colSpan="6" className="px-6 py-10 text-center text-sm text-slate-500">
                            Không có bài nào phù hợp với bộ lọc hiện tại.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          ) : (
            <div className="flex h-full flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center backdrop-blur-sm">
              <div className="mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
                <BarChart2 size={36} />
              </div>
              <h3 className="mb-2 text-base font-bold text-slate-700">Chưa có phiên chấm bài nào</h3>
              <p className="max-w-sm text-sm text-slate-500">Cấu hình mã bộ testcase và upload các file ZIP bài làm của sinh viên để bắt đầu chấm điểm tự động.</p>
            </div>
          )}
        </div>
      </div>
    </SidebarLayout>
  );
}

// ── Phân loại lỗi để hiển thị rõ "loại lỗi" ──────────────────────
const ERROR_TONES = {
  amber: "bg-amber-100 text-amber-700",
  rose: "bg-rose-100 text-rose-700",
  blue: "bg-blue-100 text-blue-700",
  slate: "bg-slate-100 text-slate-600",
};

function categorizeError(errStr) {
  const s = String(errStr ?? "");
  const idx = s.indexOf(": ");
  const file = idx > 0 ? s.slice(0, idx) : "";
  const msg = (idx > 0 ? s.slice(idx + 2) : s).trim();

  if (/trùng mã sv|cùng lần upload|cùng lần nộp/i.test(msg))
    return { file, type: "Trùng trong lần nộp", detail: "Mã SV xuất hiện nhiều lần trong cùng một lần upload — chỉ giữ 1 bài.", tone: "amber" };
  if (/sai format|masv_ten|định dạng/i.test(msg))
    return { file, type: "Sai tên file", detail: "Tên file phải theo định dạng MaSV_HoTen.zip.", tone: "amber" };
  if (/chỉ nhận|file rỗng|quá 50mb|rỗng/i.test(msg))
    return { file, type: "File không hợp lệ", detail: msg, tone: "rose" };
  if (/duplicate entry|đã có kết quả/i.test(msg))
    return { file, type: "Đã chấm trước đó", detail: "Mã SV này đã có kết quả cho bộ testcase (giờ sẽ tự ghi đè khi chấm lại).", tone: "blue" };
  if (/sql|constraint|could not execute|statement|database/i.test(msg))
    return { file, type: "Lỗi hệ thống (DB)", detail: "Lỗi khi lưu vào cơ sở dữ liệu.", tone: "rose" };
  return { file, type: "Lỗi khác", detail: msg || "Không xác định", tone: "slate" };
}

// ── Thẻ thống kê nhỏ, tái sử dụng ─────────────────────────────────
function StatCard({ label, value, icon: Icon, tone, pulse }) {
  const tones = {
    slate:   { text: "text-slate-800",  badge: "bg-slate-100 text-slate-500",     border: "border-slate-200" },
    emerald: { text: "text-emerald-600", badge: "bg-emerald-100 text-emerald-600", border: "border-emerald-100" },
    blue:    { text: "text-blue-600",    badge: "bg-blue-100 text-blue-600",       border: "border-blue-100" },
    amber:   { text: "text-amber-600",   badge: "bg-amber-100 text-amber-700",     border: "border-amber-100" },
    rose:    { text: "text-rose-600",    badge: "bg-rose-100 text-rose-600",       border: "border-rose-100" },
  };
  const t = tones[tone] || tones.slate;
  return (
    <div className="card card-hover p-5">
      <div className="mb-3 flex items-center justify-between">
        <p className="eyebrow">{label}</p>
        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${t.badge} ${pulse ? "animate-pulse" : ""}`}>
          <Icon size={16} />
        </span>
      </div>
      <p className={`text-3xl font-bold tracking-tight ${t.text}`}>{value}</p>
    </div>
  );
}
