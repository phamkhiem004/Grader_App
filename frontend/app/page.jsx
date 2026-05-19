"use client";

import { useState, useRef, useCallback } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { UploadCloud, Play, FileArchive, X, CheckCircle, Clock, AlertCircle, DownloadCloud, Loader2, CheckSquare, BarChart2 } from "lucide-react";

const API_BASE = "http://localhost:8080/api";

export default function DashboardPage() {
  const [examId, setExamId] = useState("FLUTTER_PE_01");
  const [files, setFiles] = useState([]);
  const [dragging, setDragging] = useState(false);
  const [batchId, setBatchId] = useState(null);
  const [progress, setProgress] = useState(null);
  const [phase, setPhase] = useState("idle"); // idle | uploading | polling | done
  const [uploadErr, setUploadErr] = useState(null);
  const [parseErrors, setParseErrors] = useState([]);
  const fileRef = useRef();
  const pollRef = useRef(null);

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
    if (!files.length) { setUploadErr("Chưa có file nào để chấm."); return; }
    if (!examId.trim()) { setUploadErr("Vui lòng nhập mã đề thi."); return; }

    setPhase("uploading"); setUploadErr(null); setParseErrors([]);

    const form = new FormData();
    form.append("examId", examId.trim());
    files.forEach(f => form.append("files", f));

    try {
      const res = await fetch(`${API_BASE}/batch/upload`, { method: "POST", body: form });
      const data = await res.json();

      if (!res.ok) { setUploadErr(data.error || "Lỗi server."); setPhase("idle"); return; }

      setBatchId(data.batchId);
      if (data.parseErrors?.length) setParseErrors(data.parseErrors);

      setPhase("polling");
      startPolling(data.batchId);
    } catch (e) {
      setUploadErr("Không kết nối được server: " + e.message);
      setPhase("idle");
    }
  };

  const startPolling = (bid) => {
    pollRef.current = setInterval(async () => {
      try {
        const res = await fetch(`${API_BASE}/batch/progress/${bid}`);
        const data = await res.json();
        setProgress(data);
        const pending = data.queued + data.grading;
        if (pending === 0) {
          clearInterval(pollRef.current);
          setPhase("done");
        }
      } catch (_) { }
    }, 3000);
  };

  const reset = () => {
    clearInterval(pollRef.current);
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

    const blob = new Blob(["\uFEFF" + header + allRows], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    
    // Tạo tên file định dạng: Mã đề thi_YYYY-MM-DD.csv
    const dateStr = new Date().toISOString().split('T')[0];
    a.download = `${examId}_${dateStr}.csv`;
    
    a.click();
  };

  const isRunning = phase === "uploading" || phase === "polling";
  const p = progress;
  
  const totalItems = (p?.total || 0) + parseErrors.length;
  const errorItems = (p?.error || 0) + parseErrors.length;
  const doneItems = p?.done || 0;
  const gradingItems = p?.grading || 0;
  
  const pct = totalItems > 0 ? Math.round((doneItems / totalItems) * 100) : 0;
  const errPct = totalItems > 0 ? Math.round((errorItems / totalItems) * 100) : 0;

  return (
    <SidebarLayout title="Dashboard Chấm Thi (Batch Grading)" activePath="/">
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-8">
        
        {/* Cột trái: Form cấu hình & Upload */}
        <div className="xl:col-span-1 space-y-6">
          <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
            <h2 className="text-base font-bold text-slate-800 mb-4 flex items-center gap-2">
              <CheckSquare size={18} className="text-blue-600" />
              Thiết lập phiên chấm bài
            </h2>
            
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Mã Đề Thi</label>
                <input
                  value={examId}
                  onChange={e => setExamId(e.target.value)}
                  disabled={isRunning}
                  placeholder="VD: FLUTTER_PE_01"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all disabled:opacity-60 font-medium text-slate-800"
                />
              </div>

              {phase === "idle" && (
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Upload Bài Nộp</label>
                  <div
                    onDrop={onDrop}
                    onDragOver={e => { e.preventDefault(); setDragging(true); }}
                    onDragLeave={() => setDragging(false)}
                    onClick={() => fileRef.current.click()}
                    className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-all ${
                      dragging ? "border-blue-500 bg-blue-50" : "border-slate-200 bg-slate-50 hover:bg-slate-100 hover:border-slate-300"
                    }`}
                  >
                    <div className="bg-white w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3 shadow-sm border border-slate-100">
                      <UploadCloud size={24} className={dragging ? "text-blue-500" : "text-slate-400"} />
                    </div>
                    <p className="text-sm font-semibold text-slate-700 mb-1">Kéo thả file ZIP vào đây</p>
                    <p className="text-xs text-slate-500">Hoặc click để duyệt file. Format: MaSV_HoTen.zip</p>
                    <input ref={fileRef} type="file" multiple accept=".zip" className="hidden" onChange={e => addFiles(e.target.files)} />
                  </div>
                </div>
              )}

              {/* Lỗi Upload */}
              {uploadErr && (
                <div className="bg-rose-50 text-rose-600 border border-rose-200 rounded-xl p-4 flex items-start gap-3">
                  <AlertCircle size={18} className="shrink-0 mt-0.5" />
                  <p className="text-sm font-medium">{uploadErr}</p>
                </div>
              )}

              {/* Lỗi Format Tên File / Bị bỏ qua */}
              {parseErrors.length > 0 && (
                <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
                  <h3 className="text-sm font-bold text-amber-800 mb-2 flex items-center gap-2">
                    <AlertCircle size={16} /> File bị lỗi hoặc sai định dạng
                  </h3>
                  <ul className="space-y-2">
                    {parseErrors.map((err, i) => (
                      <li key={i} className="text-xs text-amber-700 font-medium ml-6 list-disc break-words whitespace-pre-wrap">
                        {formatErrorMsg(err)}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Nút Execute */}
              {phase === "idle" && (
                <button 
                  onClick={execute}
                  disabled={files.length === 0}
                  className="w-full bg-slate-800 hover:bg-slate-900 disabled:bg-slate-300 disabled:cursor-not-allowed text-white font-semibold py-3.5 px-4 rounded-xl flex items-center justify-center gap-2 transition-all shadow-sm active:scale-[0.98]"
                >
                  <Play size={18} />
                  Bắt đầu chấm tự động ({files.length} bài)
                </button>
              )}

              {/* Loading State */}
              {phase === "uploading" && (
                <div className="bg-blue-50 border border-blue-100 rounded-xl p-6 text-center">
                  <Loader2 size={28} className="animate-spin text-blue-600 mx-auto mb-3" />
                  <h3 className="text-sm font-bold text-blue-900 mb-1">Đang tải dữ liệu lên...</h3>
                  <p className="text-xs text-blue-700/80">Đang upload {files.length} bài thi lên server</p>
                </div>
              )}
            </div>
          </div>

          {/* Danh sách file đang chọn */}
          {files.length > 0 && phase === "idle" && (
            <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden flex flex-col max-h-[400px]">
              <div className="px-5 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
                <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">File đã chọn ({files.length})</span>
                <button onClick={() => setFiles([])} className="text-xs font-semibold text-rose-500 hover:text-rose-700">Xóa hết</button>
              </div>
              <div className="overflow-y-auto custom-scrollbar p-2">
                {files.map((f) => (
                  <div key={f.name} className="flex items-center gap-3 p-3 hover:bg-slate-50 rounded-lg group">
                    <FileArchive size={16} className="text-slate-400 shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-700 truncate">{f.name}</p>
                      <p className="text-xs text-slate-400">{(f.size / 1024).toFixed(0)} KB</p>
                    </div>
                    <button onClick={() => removeFile(f.name)} className="text-slate-300 hover:text-rose-500 p-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <X size={16} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Cột phải: Tiến độ & Kết quả (Chỉ hiện khi polling hoặc done) */}
        <div className="xl:col-span-2 space-y-6">
          {(phase === "polling" || phase === "done") ? (
            <>
              {/* Thống kê nhanh */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-5">
                  <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">Tổng số bài</p>
                  <p className="text-3xl font-bold text-slate-800">{totalItems}</p>
                </div>
                <div className="bg-white rounded-2xl shadow-sm border border-emerald-100 p-5 relative overflow-hidden">
                  <div className="absolute top-0 right-0 p-4 opacity-10"><CheckCircle size={48} className="text-emerald-500" /></div>
                  <p className="text-xs font-bold text-emerald-600 uppercase tracking-wider mb-1">Hoàn thành</p>
                  <p className="text-3xl font-bold text-emerald-600">{doneItems}</p>
                </div>
                <div className="bg-white rounded-2xl shadow-sm border border-blue-100 p-5 relative overflow-hidden">
                  <div className="absolute top-0 right-0 p-4 opacity-10"><Clock size={48} className="text-blue-500" /></div>
                  <p className="text-xs font-bold text-blue-600 uppercase tracking-wider mb-1">Đang chấm</p>
                  <p className="text-3xl font-bold text-blue-600">{gradingItems}</p>
                </div>
                <div className="bg-white rounded-2xl shadow-sm border border-rose-100 p-5 relative overflow-hidden">
                  <div className="absolute top-0 right-0 p-4 opacity-10"><AlertCircle size={48} className="text-rose-500" /></div>
                  <p className="text-xs font-bold text-rose-600 uppercase tracking-wider mb-1">Bị Lỗi</p>
                  <p className="text-3xl font-bold text-rose-600">{errorItems}</p>
                </div>
              </div>

              {/* Thanh tiến độ */}
              <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
                <div className="flex justify-between items-end mb-4">
                  <div>
                    <h3 className="text-base font-bold text-slate-800">Tiến độ thực thi</h3>
                    <p className="text-xs font-medium text-slate-500 mt-1">Batch ID: <span className="text-slate-700 font-mono bg-slate-100 px-2 py-0.5 rounded">{batchId}</span></p>
                  </div>
                  <div className="text-right">
                    <span className="text-2xl font-bold text-blue-600">{pct}%</span>
                  </div>
                </div>
                
                <div className="h-3 w-full bg-slate-100 rounded-full overflow-hidden flex">
                  <div className="h-full bg-blue-500 transition-all duration-500 ease-out" style={{ width: `${pct}%` }}></div>
                  <div className="h-full bg-rose-500 transition-all duration-500 ease-out" style={{ width: `${errPct}%` }}></div>
                </div>
                
                {phase === "done" && (
                  <div className="mt-4 flex justify-end">
                    <button onClick={reset} className="text-sm font-semibold text-blue-600 hover:text-blue-800 bg-blue-50 px-4 py-2 rounded-lg transition-colors">
                      Chấm kỳ thi mới
                    </button>
                  </div>
                )}
              </div>

              {/* Bảng kết quả */}
              <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
                <div className="px-6 py-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
                  <h3 className="text-sm font-bold text-slate-800 uppercase tracking-wider">Chi tiết kết quả</h3>
                  {phase === "done" && (
                    <button onClick={downloadCSV} className="flex items-center gap-2 text-xs font-semibold text-slate-600 hover:text-slate-900 bg-white border border-slate-200 px-3 py-1.5 rounded-lg shadow-sm transition-all hover:shadow active:scale-95">
                      <DownloadCloud size={16} /> Xuất CSV
                    </button>
                  )}
                </div>
                
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-white border-b border-slate-100 text-xs font-bold text-slate-500 uppercase tracking-wider">
                        <th className="px-6 py-4 font-bold">Mã SV</th>
                        <th className="px-6 py-4 font-bold">Họ & Tên</th>
                        <th className="px-6 py-4 font-bold text-center">Trạng thái</th>
                        <th className="px-6 py-4 font-bold text-center">Tỉ lệ Pass</th>
                        <th className="px-6 py-4 font-bold text-right">Điểm số</th>
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

                        return (
                          <tr key={r.id} className="hover:bg-slate-50/50 transition-colors">
                            <td className="px-6 py-4 text-sm font-mono font-medium text-slate-700">{r.studentId}</td>
                            <td className="px-6 py-4 text-sm font-medium text-slate-800">{r.studentName || "—"}</td>
                            <td className="px-6 py-4 text-center">
                              <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-semibold ${
                                isDone ? 'bg-emerald-100 text-emerald-700' :
                                isError ? 'bg-rose-100 text-rose-700' :
                                isGrading ? 'bg-blue-100 text-blue-700' :
                                'bg-slate-100 text-slate-600'
                              }`}>
                                {isDone ? 'Đã xong' : isError ? 'Lỗi' : isGrading ? 'Đang chấm' : 'Chờ'}
                              </span>
                            </td>
                            <td className="px-6 py-4 text-center text-sm text-slate-500 font-medium">
                              {isDone ? `${passCount}/${totalCount}` : "—"}
                            </td>
                            <td className="px-6 py-4 text-right">
                              {r.score != null ? (
                                <span className={`text-base font-bold ${r.score >= 5 ? 'text-emerald-600' : 'text-rose-600'}`}>
                                  {r.score.toFixed(1)}
                                </span>
                              ) : (
                                <span className="text-slate-400 font-medium">—</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                      {(!p?.results || p.results.length === 0) && (
                        <tr>
                          <td colSpan="5" className="px-6 py-8 text-center text-sm text-slate-500">Đang chờ dữ liệu...</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          ) : (
            <div className="h-full flex flex-col items-center justify-center text-center p-12 border-2 border-dashed border-slate-200 rounded-2xl bg-slate-50/50">
              <div className="w-16 h-16 bg-slate-100 rounded-full flex items-center justify-center mb-4 text-slate-400">
                <BarChart2 size={32} />
              </div>
              <h3 className="text-base font-bold text-slate-700 mb-2">Chưa có phiên chấm bài nào</h3>
              <p className="text-sm text-slate-500 max-w-sm">Hãy cấu hình đề thi và upload file ZIP bài làm của sinh viên để bắt đầu tiến trình chấm điểm tự động.</p>
            </div>
          )}
        </div>
      </div>
    </SidebarLayout>
  );
}