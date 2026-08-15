"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import ExamCombobox from "@/components/ui/ExamCombobox";
import PerformanceSettings from "@/components/grading/PerformanceSettings";
import { gradingStatusLabel, gradingStatusTone } from "@/lib/gradingStatus";

// Khóa lưu phiên chấm đang/ vừa chạy → rời trang rồi quay lại KHÔNG mất kết quả
const ACTIVE_BATCH_KEY = "grader_active_batch";
import { UploadCloud, Play, Pause, FileArchive, X, CheckCircle, Clock, AlertCircle, DownloadCloud, Loader2, CheckSquare, BarChart2, Users, TrendingUp, FileJson, StopCircle, Trash2, Ban, RotateCcw, ListFilter, ChevronDown } from "lucide-react";

const normalizedPath = (value) => String(value || "").replace(/\\/g, "/");

const identityFromUsername = (username) => {
  const value = String(username || "").trim();
  const match = value.match(/([A-Za-z]{2}\d{6,})$/);
  return { studentId: (match?.[1] || value).toUpperCase(), studentName: value };
};

const submissionFromFile = (file, suppliedPath = "") => {
  const relativePath = normalizedPath(suppliedPath || file.webkitRelativePath || file.name);
  const segments = relativePath.split("/").filter(Boolean);
  if (segments.length < 2 || !segments.at(-1)?.toLowerCase().endsWith(".zip")) return null;
  const username = segments.at(-2)?.trim();
  if (!username || !/^[A-Za-z0-9_-]{1,60}$/.test(username)) return null;
  return { file, username, relativePath, key: username.toLowerCase() };
};

const readDirectoryEntries = async (reader) => {
  const entries = [];
  while (true) {
    const page = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    if (!page.length) return entries;
    entries.push(...page);
  }
};

const collectDroppedEntry = async (entry, parentPath = "") => {
  const path = parentPath ? `${parentPath}/${entry.name}` : entry.name;
  if (entry.isFile) {
    const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
    return [{ file, relativePath: path }];
  }
  if (!entry.isDirectory) return [];
  const children = await readDirectoryEntries(entry.createReader());
  const nested = await Promise.all(children.map((child) => collectDroppedEntry(child, path)));
  return nested.flat();
};

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
  // Một trục lọc duy nhất cho bảng kết quả: all | scored | grading | blocked.
  const [rowFilter, setRowFilter] = useState("all");
  const [filterOpen, setFilterOpen] = useState(false);
  const [regrading, setRegrading] = useState(false);
  const [batchAction, setBatchAction] = useState(null);   // "stop" | "cancel" khi đang gọi API
  const [stopNotice, setStopNotice] = useState(null);
  const fileRef = useRef();
  const pollRef = useRef(null);
  const filterRef = useRef(null);

  // Người chấm chỉ cần MỘT câu trả lời: bài nào máy chấm không cho ra điểm. Backend phát hành
  // sẵn kết luận đó ở `outcome` (PENDING | SCORED | SYSTEM_BLOCKED | STOPPED).
  //
  // Trước đây chỗ này tự suy lại từ status × diagnostic_origin × diagnostic_code qua ~50 dòng
  // heuristic, kèm cả việc bới JSON test_cases. Hậu quả đo được: 4 mã timeout của MÔI TRƯỜNG
  // (GRADER_TOTAL_TIMEOUT, CONTAINER_WATCHDOG_TIMEOUT, TEST_PROCESS_TIMEOUT,
  // GRADING_TIMEOUT_UNDETERMINED) bị luật "chỉ nhận timeout khi origin=TESTCASE" nuốt mất —
  // đúng nhóm cần báo thì không hiện. Suy đoán ở FE là nguồn sự thật thứ hai; nay bỏ hẳn.
  const isBlocked = (row) => row?.outcome === "SYSTEM_BLOCKED";

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

  // Đóng menu lọc khi bấm ra ngoài hoặc nhấn Esc.
  useEffect(() => {
    if (!filterOpen) return;
    const onDown = (e) => { if (!filterRef.current?.contains(e.target)) setFilterOpen(false); };
    const onKey = (e) => { if (e.key === "Escape") setFilterOpen(false); };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [filterOpen]);

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

    return errStr;
  };

  // File handling
  const addFiles = useCallback((incoming) => {
    const candidates = Array.from(incoming).map((value) => value?.file
      ? submissionFromFile(value.file, value.relativePath)
      : submissionFromFile(value));
    const submissions = candidates.filter(Boolean);
    if (!submissions.length) {
      setUploadErr("Không tìm thấy bài hợp lệ. Mỗi thư mục sinh viên phải chứa một file .zip của thư mục lib.");
      return;
    }
    setFiles(prev => {
      const existing = new Set(prev.map((entry) => entry.key));
      return [...prev, ...submissions.filter((entry) => !existing.has(entry.key))];
    });
    setUploadErr(null);
  }, []);

  const onDrop = useCallback(async (e) => {
    e.preventDefault(); setDragging(false);
    try {
      const roots = Array.from(e.dataTransfer.items || [])
        .map((item) => item.webkitGetAsEntry?.())
        .filter(Boolean);
      if (roots.length) {
        const nested = await Promise.all(roots.map((entry) => collectDroppedEntry(entry)));
        addFiles(nested.flat());
      } else {
        addFiles(e.dataTransfer.files);
      }
    } catch (error) {
      setUploadErr("Không đọc được thư mục đã thả: " + (error?.message || "lỗi không xác định"));
    }
  }, [addFiles]);

  const removeFile = (key) => setFiles((current) => current.filter((entry) => entry.key !== key));

  // Upload + poll
  const execute = async () => {
    if (phase === "uploading" || phase === "polling") return;   // chống bấm nhiều lần
    if (!files.length) { setUploadErr("Chưa có file nào để chấm."); return; }
    if (!examId.trim()) { setUploadErr("Vui lòng nhập mã bộ testcase."); return; }

    setPhase("uploading"); setUploadErr(null); setParseErrors([]); setStopNotice(null);
    setRowFilter("all");

    const form = new FormData();
    form.append("examId", examId.trim());
    files.forEach((entry) => {
      form.append("files", entry.file, entry.file.name);
      form.append("usernames", entry.username);
    });

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

  const toggleBatchPause = async () => {
    if (!batchId) return;
    const action = progress?.status === "PAUSED" ? "resume" : "pause";
    try {
      const res = await fetch(`${API_BASE}/batch/${encodeURIComponent(batchId)}/${action}`, { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data?.error || "Không cập nhật được trạng thái phiên chấm.");
      setProgress(data);
      setUploadErr(null);
    } catch (error) {
      setUploadErr(error?.message || "Không cập nhật được trạng thái phiên chấm.");
    }
  };

  const reset = () => {
    clearInterval(pollRef.current);
    try { localStorage.removeItem(ACTIVE_BATCH_KEY); } catch (_) {}
    setFiles([]); setBatchId(null); setProgress(null);
    setPhase("idle"); setUploadErr(null); setParseErrors([]); setStopNotice(null);
    setRowFilter("all");
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
      if (r.outcome === "STOPPED") note = "Chưa chấm — phiên chấm đã bị dừng";
      // 0 điểm do bài làm KHÔNG lên cột sự cố (người chấm không phải xử lý), nhưng lý do vẫn
      // phải tra được: đây là thứ duy nhất trả lời được khi sinh viên khiếu nại, và với ca
      // không biên dịch được thì bảng testcase trống nên không còn chỗ nào khác nói giúp.
      if (r.outcome === "SCORED" && r.score === 0 && r.diagnosticCode) {
        note = `0 điểm — ${r.diagnosticCode}: ${r.errorLog || ""}`;
      }
      if (r.outcome === "SYSTEM_BLOCKED") {
        // Bài chưa có điểm vì MÁY, không vì bài làm — phải ghi rõ trong CSV, nếu không người
        // đọc file sẽ hiểu ô điểm trống là sinh viên bỏ trắng.
        note = `Lỗi hệ thống, chưa có điểm: ${r.diagnosticCode || "UNCLASSIFIED"} · ${r.diagnosticOrigin || "UNDETERMINED"} · ${r.errorLog || ""}`;
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

      const pathParts = normalizedPath(filename).split('/').filter(Boolean);
      const username = pathParts.length > 1 && pathParts.at(-1)?.toLowerCase().endsWith(".zip")
        ? pathParts.at(-2)
        : filename;
      const { studentId, studentName } = identityFromUsername(username);

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

  /**
   * Tải về thư mục kết quả: bấm một cái là tải ngay, giải nén ra `Json/<MSSV>.json`.
   *
   * <p>KHÔNG dùng `showDirectoryPicker`: nó bắt người dùng chọn thư mục, và Chrome từ chối phần
   * lớn thư mục quen tay ("thư mục này chứa tệp hệ thống") nên thao tác hay chết giữa chừng.
   * Trình duyệt không tải xuống được một thư mục thật, nên ZIP là lớp vận chuyển duy nhất —
   * bên trong vẫn đúng một thư mục `Json` với các file rời, không phải JSON gộp.
   */
  const downloadResultsFolder = async () => {
    if (!batchId) return;
    try {
      const res = await fetch(`${API_BASE}/results/batch/${encodeURIComponent(batchId)}/archive`);
      if (res.status === 404) throw new Error("Chưa có bài nào chấm xong để xuất.");
      if (!res.ok) throw new Error("Không tạo được thư mục kết quả.");
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "Json.zip";
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (error) {
      setUploadErr(error?.message || "Không xuất được thư mục JSON.");
    }
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
  const isPaused = p?.status === "PAUSED";

  const totalItems = (p?.total || 0) + parseErrors.length;
  const doneItems = p?.done || 0;
  const cancelledItems = p?.cancelled || 0;   // bài bị bỏ do người dùng dừng phiên
  // Bài máy chấm KHÔNG cho ra điểm. Trước đây tách "lỗi" và "cần chấm tay" thành hai con số,
  // nhưng người chấm phải xử lý y như nhau nên tách chỉ làm khó đọc. Bài sinh viên làm sai
  // KHÔNG nằm ở đây — nó đã là 0 điểm trong "Hoàn thành".
  const blockedItems = p?.blocked ?? ((p?.error || 0) + (p?.manualReview || 0));
  // File bị loại ngay khi upload (không phải .zip, rỗng, quá 50MB) chưa từng vào hàng đợi.
  const rejectedItems = parseErrors.length;

  // Bài bị dừng cũng là "đã xử lý xong": không tính vào đây thì thanh tiến độ đứng mãi dưới 100%.
  const processedItems = doneItems + blockedItems + cancelledItems + rejectedItems;
  const pct = totalItems > 0 ? Math.round((processedItems / totalItems) * 100) : 0;
  const donePct = totalItems > 0 ? Math.round((doneItems / totalItems) * 100) : 0;
  const blockedPct = totalItems > 0 ? Math.round(((blockedItems + rejectedItems) / totalItems) * 100) : 0;
  const cancelPct = totalItems > 0 ? Math.round((cancelledItems / totalItems) * 100) : 0;

  const totalSize = files.reduce((sum, entry) => sum + entry.file.size, 0);

  const allResultRows = p?.results || [];
  // Sự cố đã được backend GOM THEO NGUYÊN NHÂN: Docker chết một cái là 20 bài cùng hỏng, người
  // chấm cần đọc một dòng chứ không phải cuộn 20 dòng giống nhau.
  const incidents = p?.incidents || [];
  const blockedStudentIds = incidents.flatMap((incident) => incident.studentIds || []);
  // Đếm trên chính danh sách đang hiển thị để con số trên nút khớp đúng số dòng lọc ra được
  // (blockedItems của batch còn cộng cả bài không nằm trong bảng).
  const blockedRowCount = allResultRows.filter(isBlocked).length;
  // Nhãn tiếng Việt của từng mã lấy luôn từ nhóm sự cố — không dựng bảng tra thứ hai ở FE.
  const incidentLabelByCode = Object.fromEntries(
    incidents.map((incident) => [incident.code, incident.label])
  );

  // Bộ lọc khai BẰNG DỮ LIỆU, không phải bằng chuỗi if: cả menu lọc lẫn 4 thẻ thống kê phía trên
  // đều đọc từ đây nên số trên thẻ luôn đúng bằng số dòng lọc ra được — không có đường nào để hai
  // chỗ lệch nhau.
  const rowFilters = [
    { key: "all",     label: "Tất cả bài",   count: allResultRows.length,
      match: () => true },
    { key: "scored",  label: "Đã chấm",      count: allResultRows.filter((r) => r.outcome === "SCORED").length,
      match: (r) => r.outcome === "SCORED" },
    { key: "grading", label: "Đang chấm",    count: allResultRows.filter((r) => r.outcome === "PENDING").length,
      match: (r) => r.outcome === "PENDING" },
    { key: "blocked", label: "Lỗi hệ thống", count: blockedRowCount,
      match: isBlocked },
  ];
  const activeFilter = rowFilters.find((f) => f.key === rowFilter) || rowFilters[0];
  const filteredResultRows = allResultRows.filter(activeFilter.match);

  // Chấm lại đúng nhóm bài hỏng sau khi đã sửa máy/testcase — việc gần như luôn phải làm sau một
  // sự cố hệ thống, nên đặt ngay cạnh cảnh báo thay vì bắt vào trang Lịch sử bấm từng bài.
  const regradeBlocked = async () => {
    if (!blockedStudentIds.length || regrading) return;
    const targetExam = p?.examId || examId.trim();
    if (!targetExam) return;
    setRegrading(true);
    try {
      const res = await fetch(`${API_BASE}/batch/regrade-batch/${encodeURIComponent(targetExam)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ studentIds: blockedStudentIds }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { setUploadErr(data?.error || "Không chấm lại được nhóm bài này."); return; }
      setBatchId(data.batchId);
      setProgress(null);
      setRowFilter("all");
      setStopNotice(`Đang chấm lại ${data.queued || 0} bài bị sự cố hệ thống.`);
      setPhase("polling");
      startPolling(data.batchId);
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
    } finally {
      setRegrading(false);
    }
  };

  return (
    <SidebarLayout
      title="Chấm bài tự động"
      subtitle="Chấm tự động bài thi Flutter trong môi trường Docker cô lập"
      activePath="/teacher/grading"
      /* Nới trần bề ngang (mặc định max-w-6xl) — bảng kết quả 6 cột không đủ chỗ trong 1152px
         nên phải kéo ngang; cùng mức với trang Quản lý bộ testcase để hai trang nhìn đồng bộ. */
      contentClassName="max-w-[1600px]"
    >
      {/* Cột trái CỐ ĐỊNH 320px: form thiết lập không dài ra thì cũng không đẹp hơn. Toàn bộ
          phần dôi ra dồn cho cột kết quả — đó mới là nơi người chấm nhìn lâu nhất. */}
      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[320px_minmax(0,1fr)]">

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
                    <p className="mb-1 text-sm font-semibold text-slate-700">Kéo thả thư mục bài nộp vào đây</p>
                    <p className="text-xs text-slate-500">Mỗi thư mục mang tên username và chứa một file <span className="font-mono text-slate-600">.zip</span> của thư mục lib</p>
                    <input
                      ref={fileRef}
                      type="file"
                      multiple
                      webkitdirectory=""
                      directory=""
                      className="hidden"
                      onChange={(event) => { addFiles(event.target.files); event.target.value = ""; }}
                    />
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
                  <p className="text-xs text-indigo-700/80">Đang upload {files.length} thư mục bài thi lên server</p>
                </div>
              )}
            </div>
          </div>

          {/* Cấu hình tài nguyên Docker (CPU/RAM mỗi bài + số bài song song) */}
          <PerformanceSettings running={isRunning} />

          {/* Danh sách file đang chọn */}
          {files.length > 0 && phase === "idle" && (
            <div className="card flex max-h-[420px] flex-col overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/50 px-5 py-4">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Bài nộp đã chọn ({files.length})</span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-500">{(totalSize / 1024 / 1024).toFixed(1)} MB</span>
                </div>
                <button onClick={() => setFiles([])} className="text-xs font-semibold text-rose-500 transition-colors hover:text-rose-700">Xóa hết</button>
              </div>
              <div className="custom-scrollbar overflow-y-auto p-2">
                {files.map((entry) => (
                  <div key={entry.key} className="group flex items-center gap-3 rounded-lg p-3 transition-colors hover:bg-slate-50">
                    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-500">
                      <FileArchive size={15} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-slate-700">{entry.username}</p>
                      <p className="truncate text-xs text-slate-400">{entry.file.name} · {(entry.file.size / 1024).toFixed(0)} KB</p>
                    </div>
                    <button onClick={() => removeFile(entry.key)} className="p-1 text-slate-300 opacity-0 transition-opacity hover:text-rose-500 group-hover:opacity-100">
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
              {/* Thống kê nhanh — cũng LÀ bộ lọc: bấm thẻ nào thì bảng dưới hiện đúng nhóm đó,
                  giống hệt menu lọc. Số lấy từ cùng một khai báo `rowFilters` nên không lệch.
                  File bị loại ngay khi upload không nằm ở đây; chúng có khối riêng "N file bị bỏ qua". */}
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                {rowFilters.map((f) => (
                  <StatCard
                    key={f.key}
                    label={f.key === "all" ? "Tổng số bài" : f.label}
                    value={f.count}
                    icon={STAT_ICON[f.key]}
                    tone={STAT_TONE[f.key]}
                    pulse={f.key === "grading" && f.count > 0}
                    active={rowFilter === f.key}
                    onClick={() => setRowFilter(f.key)}
                  />
                ))}
              </div>

              {/* Thanh tiến độ */}
              <div className="card p-6">
                <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
                  <div>
                    <h3 className="flex items-center gap-2 text-base font-bold text-slate-800">
                      <TrendingUp size={18} className="text-indigo-500" /> Tiến độ thực thi
                    </h3>
                    <p className="mt-1 text-xs font-medium text-slate-500">
                      Batch: <span className="rounded bg-slate-100 px-2 py-0.5 font-mono text-slate-700">{batchId}</span>
                    </p>
                  </div>
                  <div className="flex items-end gap-3 text-right">
                    {phase === "polling" && (
                      <button
                        type="button"
                        onClick={toggleBatchPause}
                        className="inline-flex items-center gap-1.5 rounded-lg border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs font-semibold text-indigo-700 transition hover:bg-indigo-100"
                        title={isPaused
                          ? "Tiếp tục đưa bài đang chờ vào máy chấm"
                          : "Tạm dừng sau khi các bài đang chạy hoàn tất"}
                      >
                        {isPaused ? <Play size={14} /> : <Pause size={14} />}
                        {isPaused ? "Tiếp tục" : "Tạm dừng"}
                      </button>
                    )}
                    <div>
                    <span className="text-3xl font-bold text-slate-800">{pct}<span className="text-lg text-slate-400">%</span></span>
                    {phase === "polling" && (
                      <p className={`flex items-center justify-end gap-1.5 text-xs font-semibold ${isPaused ? "text-amber-600" : "text-blue-600"}`}>
                        <span className="relative flex h-2 w-2">
                          {!isPaused && <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400 opacity-75"></span>}
                          <span className={`relative inline-flex h-2 w-2 rounded-full ${isPaused ? "bg-amber-500" : "bg-blue-500"}`}></span>
                        </span>
                        {isPaused ? "Đã tạm dừng" : "Đang chạy"}
                      </p>
                    )}
                    </div>
                  </div>
                </div>

                <div className="flex h-3 w-full overflow-hidden rounded-full bg-slate-100">
                  <div className="h-full bg-gradient-to-r from-emerald-400 to-emerald-500 transition-all duration-500 ease-out" style={{ width: `${donePct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-amber-400 to-amber-500 transition-all duration-500 ease-out" style={{ width: `${blockedPct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-slate-300 to-slate-400 transition-all duration-500 ease-out" style={{ width: `${cancelPct}%` }}></div>
                </div>
                <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                  <span>
                    <span className="font-semibold text-emerald-600">{doneItems}</span> có điểm
                    {(blockedItems + rejectedItems) > 0 && <> · <span className="font-semibold text-amber-600">{blockedItems + rejectedItems}</span> lỗi hệ thống</>}
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
                      <button onClick={downloadResultsFolder} title="Xuất thư mục gồm một JSON cho mỗi sinh viên" className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95">
                        <FileJson size={16} /> Xuất JSON
                      </button>
                      <button onClick={downloadCSV} className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 shadow-sm transition-all hover:text-slate-900 hover:shadow active:scale-95">
                        <DownloadCloud size={16} /> Xuất CSV
                      </button>
                    </div>
                  )}
                </div>

                {/* Sự cố HỆ THỐNG — thứ duy nhất người chấm phải xử lý. Bài 0 điểm do sinh viên
                    làm sai không xuất hiện ở đây: điểm số đã nói hết. Không có sự cố thì cả khối
                    này biến mất, màn hình sạch. */}
                {incidents.length > 0 && (
                  <div className="border-b border-amber-100 bg-amber-50 px-6 py-4">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="flex items-start gap-2">
                        <AlertCircle size={16} className="mt-0.5 shrink-0 text-amber-600" />
                        <div>
                          <p className="text-sm font-bold text-amber-900">
                            {blockedItems} bài chưa có điểm do sự cố hệ thống
                          </p>
                          <p className="text-xs text-amber-700">
                            Máy chấm không cho ra kết quả tin được — cần xử lý rồi chấm lại.
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={regradeBlocked}
                          disabled={regrading || isRunning || !blockedStudentIds.length}
                          title="Chấm lại đúng nhóm bài bị sự cố, sau khi đã xử lý nguyên nhân"
                          className="flex items-center gap-2 rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-xs font-semibold text-amber-800 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {regrading ? <Loader2 size={14} className="animate-spin" /> : <RotateCcw size={14} />}
                          Chấm lại {blockedStudentIds.length} bài
                        </button>
                      </div>
                    </div>

                    <ul className="mt-3 space-y-1.5">
                      {incidents.map((incident) => (
                        <li
                          key={incident.code}
                          className="flex flex-wrap items-baseline gap-x-2 rounded-lg border border-amber-200 bg-white px-3 py-2 text-xs"
                          title={incident.message || ""}
                        >
                          <span className="font-bold text-amber-900">{incident.count} bài</span>
                          <span className="text-slate-700">· {incident.label}</span>
                          <span className="text-slate-400">· {incident.originLabel}</span>
                          <span className="ml-auto truncate font-mono text-[10px] text-slate-400">{incident.code}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {/* Bộ lọc CỐ ĐỊNH, luôn thấy — kể cả khi lô hiện tại sạch. Gom vào một menu thay
                    vì rải nút: bốn nhóm là bốn lựa chọn loại trừ nhau, menu nói rõ điều đó và
                    không chiếm thêm bề ngang khi số nhóm tăng. */}
                <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 bg-white px-6 py-2.5">
                  <div ref={filterRef} className="relative">
                    <button
                      type="button"
                      onClick={() => setFilterOpen((v) => !v)}
                      aria-haspopup="listbox"
                      aria-expanded={filterOpen}
                      className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
                    >
                      <ListFilter size={14} className="text-slate-400" />
                      {activeFilter.label} ({activeFilter.count})
                      <ChevronDown size={14} className={`text-slate-400 transition-transform ${filterOpen ? "rotate-180" : ""}`} />
                    </button>
                    {filterOpen && (
                      <ul
                        role="listbox"
                        className="absolute left-0 top-full z-30 mt-1 w-56 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-xl"
                      >
                        {rowFilters.map((f) => (
                          <li key={f.key}>
                            <button
                              type="button"
                              role="option"
                              aria-selected={rowFilter === f.key}
                              onClick={() => { setRowFilter(f.key); setFilterOpen(false); }}
                              className={`flex w-full items-center justify-between px-3 py-2 text-left text-xs font-semibold transition-colors ${
                                rowFilter === f.key
                                  ? "bg-indigo-50 text-indigo-700"
                                  : "text-slate-600 hover:bg-slate-50"
                              }`}
                            >
                              <span>{f.label}</span>
                              <span className="font-mono text-[11px] text-slate-400">{f.count}</span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  <span className="ml-auto text-xs font-medium text-slate-400">
                    Hiển thị {filteredResultRows.length}/{allResultRows.length} bài
                  </span>
                </div>

                {/* Vừa trong một màn: bỏ min-w cứng 900px (thứ ép kéo ngang ở cột hẹp) và chia
                    lại tỉ lệ theo lượng chữ thật của từng cột. Vẫn giữ overflow-x-auto làm lưới
                    an toàn cho màn rất hẹp, nhưng ở bố cục mới nó không còn kích hoạt. */}
                <div className="max-w-full overflow-x-auto">
                  <table className="w-full min-w-[680px] table-fixed border-collapse text-left">
                    <colgroup>
                      <col className="w-[22%]" />
                      <col className="w-[19%]" />
                      <col className="w-[14%]" />
                      <col className="w-[25%]" />
                      <col className="w-[13%]" />
                      <col className="w-[7%]" />
                    </colgroup>
                    <thead>
                      <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                        <th className="px-3 py-3.5 text-center">Sinh viên</th>
                        <th className="px-3 py-3.5 text-center">Trạng thái</th>
                        <th className="px-3 py-3.5 text-center">Tỉ lệ Pass</th>
                        <th className="px-3 py-3.5 text-center">Sự cố hệ thống</th>
                        <th className="px-3 py-3.5 text-center">Điểm số</th>
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

                        const isDone = r.outcome === "SCORED";
                        const tone = gradingStatusTone(r.status, r.outcome);
                        const ratio = totalCount > 0 ? Math.round((passCount / totalCount) * 100) : 0;
                        const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();
                        const diagnosticScope = r.diagnosticOrigin === "STUDENT" ? "Bài sinh viên" :
                          r.diagnosticOrigin === "TESTCASE" ? "Bộ testcase" :
                          r.diagnosticOrigin === "ENVIRONMENT" ? "Môi trường chấm" :
                          r.diagnosticOrigin === "UNDETERMINED" ? "Chưa xác định" : "";
                        const blocked = isBlocked(r);

                        return (
                          <tr key={r.id} className="transition-colors hover:bg-slate-50/70">
                            <td className="px-3 py-3.5 text-center">
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
                              <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${tone.pill}`}>
                                <span className={`h-1.5 w-1.5 rounded-full ${tone.dot}`}></span>
                                {gradingStatusLabel(r.status, r.outcome)}
                              </span>
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {(isDone || totalCount > 0) ? (
                                <div className="flex items-center gap-2">
                                  <div className="h-1.5 min-w-8 flex-1 overflow-hidden rounded-full bg-slate-100">
                                    <div className={`h-full rounded-full ${ratio >= 50 ? 'bg-emerald-500' : 'bg-amber-500'}`} style={{ width: `${ratio}%` }}></div>
                                  </div>
                                  <span className="text-xs font-medium text-slate-500">{passCount}/{totalCount}</span>
                                </div>
                              ) : <span className="text-slate-300">—</span>}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {blocked ? (
                                <div
                                  className="w-full min-w-0 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-amber-800"
                                  title={[r.diagnosticCode, diagnosticScope, r.diagnosticStage, r.errorLog].filter(Boolean).join(" · ")}
                                >
                                  <div className="flex items-center gap-1.5 text-xs font-bold">
                                    <AlertCircle size={13} className="shrink-0" />
                                    <span className="truncate">
                                      {incidentLabelByCode[r.diagnosticCode] || "Sự cố chưa xác định nguồn"}
                                    </span>
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
                            <td className="px-3 py-3.5 text-center">
                              {r.score != null ? (
                                <span
                                  className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${r.score >= PASS_THRESHOLD ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}
                                  title={r.score === 0 && r.diagnosticCode
                                    ? `${r.diagnosticCode} · ${r.errorLog || ""}`
                                    : undefined}
                                >
                                  {r.score.toFixed(1)}
                                </span>
                              ) : (
                                <span className="font-medium text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-3 py-3.5 text-center">
                              {(isDone || blocked) && (
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
                          <td colSpan="6" className="px-6 py-10 text-center text-sm">
                            {/* Lọc "Lỗi hệ thống" mà rỗng là TIN TỐT, không phải kết quả trống —
                                nói thẳng ra thay vì để người chấm tự suy từ một bảng trắng. */}
                            {rowFilter === "blocked" ? (
                              <span className="inline-flex items-center gap-1.5 font-semibold text-emerald-600">
                                <CheckCircle size={15} /> Không có bài nào lỗi do hệ thống.
                              </span>
                            ) : rowFilter === "grading" ? (
                              <span className="text-slate-500">Không còn bài nào đang chờ hoặc đang chấm.</span>
                            ) : rowFilter === "scored" ? (
                              <span className="text-slate-500">Chưa có bài nào chấm xong.</span>
                            ) : (
                              <span className="text-slate-500">Không có bài nào phù hợp với bộ lọc hiện tại.</span>
                            )}
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
              <p className="max-w-sm text-sm text-slate-500">Chọn mã bộ testcase và upload các thư mục username có chứa một file .zip của thư mục lib để bắt đầu chấm điểm tự động.</p>
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
  if (/sai format|username|\.zip|định dạng/i.test(msg))
    return { file, type: "Sai cấu trúc thư mục", detail: "Mỗi thư mục username phải chứa một file .zip của thư mục lib.", tone: "amber" };
  if (/chỉ nhận|file rỗng|quá 50mb|rỗng/i.test(msg))
    return { file, type: "File không hợp lệ", detail: msg, tone: "rose" };
  if (/duplicate entry|đã có kết quả/i.test(msg))
    return { file, type: "Đã chấm trước đó", detail: "Mã SV này đã có kết quả cho bộ testcase (giờ sẽ tự ghi đè khi chấm lại).", tone: "blue" };
  if (/sql|constraint|could not execute|statement|database/i.test(msg))
    return { file, type: "Lỗi hệ thống (DB)", detail: "Lỗi khi lưu vào cơ sở dữ liệu.", tone: "rose" };
  return { file, type: "Lỗi khác", detail: msg || "Không xác định", tone: "slate" };
}

// Icon/màu của 4 thẻ thống kê, khớp theo khoá bộ lọc (xem `rowFilters`).
const STAT_ICON = { all: Users, scored: CheckCircle, grading: Clock, blocked: AlertCircle };
const STAT_TONE = { all: "slate", scored: "emerald", grading: "blue", blocked: "amber" };

// ── Thẻ thống kê nhỏ, tái sử dụng ─────────────────────────────────
function StatCard({ label, value, icon: Icon, tone, pulse, active, onClick }) {
  const tones = {
    slate:   { text: "text-slate-800",  badge: "bg-slate-100 text-slate-500",     border: "border-slate-200" },
    emerald: { text: "text-emerald-600", badge: "bg-emerald-100 text-emerald-600", border: "border-emerald-100" },
    blue:    { text: "text-blue-600",    badge: "bg-blue-100 text-blue-600",       border: "border-blue-100" },
    amber:   { text: "text-amber-600",   badge: "bg-amber-100 text-amber-700",     border: "border-amber-100" },
    rose:    { text: "text-rose-600",    badge: "bg-rose-100 text-rose-600",       border: "border-rose-100" },
  };
  const t = tones[tone] || tones.slate;
  const body = (
    <>
      <div className="mb-3 flex items-center justify-between">
        <p className="eyebrow">{label}</p>
        <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${t.badge} ${pulse ? "animate-pulse" : ""}`}>
          <Icon size={16} />
        </span>
      </div>
      <p className={`text-3xl font-bold tracking-tight ${t.text}`}>{value}</p>
    </>
  );
  if (!onClick) return <div className="card card-hover p-5">{body}</div>;
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={!!active}
      title={`Chỉ hiện nhóm: ${label}`}
      // Viền đậm + nền nhạt khi đang lọc theo thẻ này — nếu không có dấu hiệu, người dùng bấm
      // xong thấy bảng đổi mà không biết vì sao.
      className={`card card-hover p-5 text-left transition-all ${
        active ? "ring-2 ring-indigo-400 ring-offset-1" : "hover:-translate-y-0.5"
      }`}
    >
      {body}
    </button>
  );
}
