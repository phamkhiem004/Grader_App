"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import { getToken } from "@/lib/auth";

// Khóa lưu phiên chấm đang/ vừa chạy → rời trang rồi quay lại KHÔNG mất kết quả
const ACTIVE_BATCH_KEY = "grader_active_batch";
import { UploadCloud, Play, FileArchive, X, CheckCircle, Clock, AlertCircle, DownloadCloud, Loader2, CheckSquare, BarChart2, Users, TrendingUp, FileJson } from "lucide-react";

export default function DashboardPage() {
  const [examId, setExamId] = useState("");
  const [files, setFiles] = useState([]);
  const [dragging, setDragging] = useState(false);
  const [batchId, setBatchId] = useState(null);
  const [progress, setProgress] = useState(null);
  const [phase, setPhase] = useState("idle"); // idle | uploading | polling | done
  const [uploadErr, setUploadErr] = useState(null);
  const [parseErrors, setParseErrors] = useState([]);
  const fileRef = useRef();
  const pollRef = useRef(null);

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
    if (!examId.trim()) { setUploadErr("Vui lòng nhập mã đề thi."); return; }

    setPhase("uploading"); setUploadErr(null); setParseErrors([]);

    const form = new FormData();
    form.append("examId", examId.trim());
    files.forEach(f => form.append("files", f));

    try {
      const res = await fetch(`${API_BASE}/batch/upload`, { method: "POST", headers: { Authorization: `Bearer ${getToken() ?? ""}` }, body: form });
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
    setPhase("idle"); setUploadErr(null); setParseErrors([]);
  };

  const downloadCSV = () => {
    if (!progress?.results?.length && !parseErrors.length) return;

    const header = "Mã SV,Họ tên,Điểm,Trạng thái,Ghi chú\n";

    // 1. Các bài hợp lệ đã nạp vào server
    const validRows = (progress?.results || []).map(r => {
      let note = "";
      if (r.status === "ERROR") note = "Lỗi khi chấm (thường do lỗi compile hoặc crash)";
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

    // Tạo tên file định dạng: Mã đề thi_YYYY-MM-DD.csv
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
  const doneItems = p?.done || 0;
  const gradingItems = p?.grading || 0;

  const pct = totalItems > 0 ? Math.round((doneItems / totalItems) * 100) : 0;
  const errPct = totalItems > 0 ? Math.round((errorItems / totalItems) * 100) : 0;

  const totalSize = files.reduce((s, f) => s + f.size, 0);

  return (
    <SidebarLayout title="Chấm bài hàng loạt" subtitle="Chấm tự động bài thi Flutter trong môi trường Docker cô lập" activePath="/">
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">

        {/* Cột trái: Form cấu hình & Upload */}
        <div className="space-y-6 xl:col-span-1">
          <div className="card overflow-hidden">
            {/* Header gradient */}
            <div className="flex items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-indigo-50 to-blue-50 px-6 py-4">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-white shadow-sm">
                <CheckSquare size={18} />
              </div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">Thiết lập phiên chấm bài</h2>
                <p className="text-xs text-slate-500">Chọn đề và tải bài nộp</p>
              </div>
            </div>

            <div className="space-y-4 p-6">
              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Mã Đề Thi</label>
                <input
                  value={examId}
                  onChange={e => setExamId(e.target.value)}
                  disabled={isRunning}
                  placeholder="Nhập mã đề thi..."
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-medium text-slate-800 transition-all focus:border-transparent focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-60"
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
        <div className="space-y-6 xl:col-span-2">
          {(phase === "polling" || phase === "done") ? (
            <>
              {/* Thống kê nhanh */}
              <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
                <StatCard label="Tổng số bài" value={totalItems} icon={Users} tone="slate" />
                <StatCard label="Hoàn thành" value={doneItems} icon={CheckCircle} tone="emerald" />
                <StatCard label="Đang chấm" value={gradingItems} icon={Clock} tone="blue" pulse={gradingItems > 0} />
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
                  <div className="h-full bg-gradient-to-r from-emerald-400 to-emerald-500 transition-all duration-500 ease-out" style={{ width: `${pct}%` }}></div>
                  <div className="h-full bg-gradient-to-r from-rose-400 to-rose-500 transition-all duration-500 ease-out" style={{ width: `${errPct}%` }}></div>
                </div>
                <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                  <span><span className="font-semibold text-emerald-600">{doneItems}</span> đạt · <span className="font-semibold text-rose-600">{errorItems}</span> lỗi</span>
                  <span>{doneItems + errorItems}/{totalItems} đã xử lý</span>
                </div>

                {phase === "done" && (
                  <div className="mt-4 flex justify-end border-t border-slate-100 pt-4">
                    <button onClick={reset} className="rounded-lg bg-indigo-50 px-4 py-2 text-sm font-semibold text-indigo-600 transition-colors hover:bg-indigo-100">
                      + Chấm kỳ thi mới
                    </button>
                  </div>
                )}
              </div>

              {/* Bảng kết quả */}
              <div className="card overflow-hidden">
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

                <div className="overflow-x-auto">
                  <table className="w-full border-collapse text-left">
                    <thead>
                      <tr className="border-b border-slate-100 bg-white text-xs font-bold uppercase tracking-wider text-slate-500">
                        <th className="px-6 py-3.5">Sinh viên</th>
                        <th className="px-6 py-3.5 text-center">Trạng thái</th>
                        <th className="px-6 py-3.5">Tỉ lệ Pass</th>
                        <th className="px-6 py-3.5 text-right">Điểm số</th>
                        <th className="px-4 py-3.5 text-center">JSON</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {p?.results?.map((r) => {
                        let passCount = 0, totalCount = 0;
                        try {
                          const d = JSON.parse(r.details || "{}");
                          passCount = d.soTestPass ?? 0;
                          totalCount = d.tongSoTest ?? 0;
                        } catch (_) {}

                        const isDone = r.status === "DONE";
                        const isError = r.status === "ERROR";
                        const isGrading = r.status === "GRADING";
                        const ratio = totalCount > 0 ? Math.round((passCount / totalCount) * 100) : 0;
                        const initials = (r.studentName || r.studentId || "?").trim().charAt(0).toUpperCase();

                        return (
                          <tr key={r.id} className="transition-colors hover:bg-slate-50/70">
                            <td className="px-6 py-3.5">
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
                            <td className="px-6 py-3.5 text-center">
                              <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
                                isDone ? 'bg-emerald-100 text-emerald-700' :
                                isError ? 'bg-rose-100 text-rose-700' :
                                isGrading ? 'bg-blue-100 text-blue-700' :
                                'bg-slate-100 text-slate-600'
                              }`}>
                                <span className={`h-1.5 w-1.5 rounded-full ${
                                  isDone ? 'bg-emerald-500' : isError ? 'bg-rose-500' : isGrading ? 'bg-blue-500 animate-pulse' : 'bg-slate-400'
                                }`}></span>
                                {isDone ? 'Đã xong' : isError ? 'Lỗi' : isGrading ? 'Đang chấm' : 'Chờ'}
                              </span>
                            </td>
                            <td className="px-6 py-3.5">
                              {isDone ? (
                                <div className="flex items-center gap-2">
                                  <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-100">
                                    <div className={`h-full rounded-full ${ratio >= 50 ? 'bg-emerald-500' : 'bg-amber-500'}`} style={{ width: `${ratio}%` }}></div>
                                  </div>
                                  <span className="text-xs font-medium text-slate-500">{passCount}/{totalCount}</span>
                                </div>
                              ) : isError && r.errorLog ? (
                                <span className="line-clamp-2 max-w-xs text-xs text-rose-500" title={r.errorLog}>{r.errorLog}</span>
                              ) : <span className="text-slate-300">—</span>}
                            </td>
                            <td className="px-6 py-3.5 text-right">
                              {r.score != null ? (
                                <span className={`inline-block rounded-lg px-2.5 py-1 text-sm font-bold ${r.score >= PASS_THRESHOLD ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}>
                                  {r.score.toFixed(1)}
                                </span>
                              ) : (
                                <span className="font-medium text-slate-300">—</span>
                              )}
                            </td>
                            <td className="px-4 py-3.5 text-center">
                              {isDone && (
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
                      {(!p?.results || p.results.length === 0) && (
                        <tr>
                          <td colSpan="5" className="px-6 py-10 text-center text-sm text-slate-500">
                            <Loader2 size={20} className="mx-auto mb-2 animate-spin text-slate-300" />
                            Đang chờ dữ liệu...
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
              <p className="max-w-sm text-sm text-slate-500">Cấu hình mã đề và upload các file ZIP bài làm của sinh viên để bắt đầu chấm điểm tự động.</p>
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
    return { file, type: "Đã chấm trước đó", detail: "Mã SV này đã có kết quả cho đề thi (giờ sẽ tự ghi đè khi chấm lại).", tone: "blue" };
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
