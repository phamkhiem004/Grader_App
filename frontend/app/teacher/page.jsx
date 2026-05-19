"use client";

import { useState, useRef, useCallback } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { Settings, UploadCloud, FileJson, FileCode2, Package, Play, CheckCircle, AlertCircle, Loader2 } from "lucide-react";

const API_BASE = "http://localhost:8080/api";

export default function TeacherSetupPage() {
  const [examId, setExamId] = useState("");
  const [file, setFile] = useState(null);
  const [dragging, setDragging] = useState(false);
  const [phase, setPhase] = useState("idle"); // idle | uploading | done
  const [error, setError] = useState(null);
  const fileRef = useRef();

  // Xử lý File ZIP
  const handleFile = (incoming) => {
    const selected = incoming[0];
    if (selected && selected.name.endsWith(".zip")) {
      setFile(selected);
      setError(null);
    } else {
      setError("Vui lòng chỉ chọn 1 file định dạng .zip");
    }
  };

  const onDrop = useCallback((e) => {
    e.preventDefault();
    setDragging(false);
    handleFile(e.dataTransfer.files);
  }, []);

  // Gửi dữ liệu lên Spring Boot
  const execute = async () => {
    if (!examId.trim()) { setError("Vui lòng nhập Mã đề thi."); return; }
    if (!file) { setError("Vui lòng chọn file cấu hình (.zip)."); return; }

    setPhase("uploading");
    setError(null);

    const form = new FormData();
    form.append("examId", examId.trim());
    form.append("testcase", file);

    try {
      const res = await fetch(`${API_BASE}/exam-setup/upload-testcase`, {
        method: "POST",
        body: form
      });

      if (!res.ok) {
        const data = await res.json();
        setError(data.error || "Lỗi server khi cấu hình đề thi.");
        setPhase("idle");
        return;
      }

      setPhase("done");
    } catch (e) {
      setError("Không kết nối được server: " + e.message);
      setPhase("idle");
    }
  };

  const reset = () => {
    setFile(null);
    setExamId("");
    setPhase("idle");
    setError(null);
  };

  const isRunning = phase === "uploading";

  return (
    <SidebarLayout title="Cấu hình Đề thi (Exam Setup)" activePath="/teacher">
      <div className="max-w-4xl mx-auto">
        
        {/* Tiêu đề trang */}
        <div className="mb-8">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center shadow-lg shadow-indigo-600/20 text-white">
              <Settings size={20} />
            </div>
            <div>
              <h2 className="text-xl font-bold text-slate-800">Khởi tạo Sandbox Chấm điểm</h2>
              <p className="text-sm text-slate-500">Cấu hình Testcase (.dart), Script chấm điểm và Bảng phân bổ điểm (Rubric)</p>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
          {/* Form cấu hình */}
          <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 md:col-span-2">
            <div className="grid md:grid-cols-2 gap-8">
              
              {/* Bước 1: Mã đề */}
              <div className="space-y-4">
                <div className="flex items-center gap-2 mb-4">
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-slate-100 text-slate-600 font-bold text-xs">1</span>
                  <h3 className="text-sm font-bold text-slate-800">Nhập mã đề thi</h3>
                </div>
                
                <div>
                  <input
                    value={examId}
                    onChange={e => setExamId(e.target.value)}
                    disabled={isRunning || phase === "done"}
                    placeholder="VD: FLUTTER_PE_01"
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all disabled:opacity-60 font-medium text-slate-800 uppercase"
                  />
                  <p className="text-xs text-slate-400 mt-2">Mã đề này sẽ được dùng để khớp với bài làm của sinh viên khi chấm tự động.</p>
                </div>
              </div>

              {/* Bước 2: Upload File */}
              <div className="space-y-4">
                <div className="flex items-center gap-2 mb-4">
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-slate-100 text-slate-600 font-bold text-xs">2</span>
                  <h3 className="text-sm font-bold text-slate-800">Upload gói cấu hình (ZIP)</h3>
                </div>

                {phase === "idle" && (
                  <div
                    onDrop={onDrop}
                    onDragOver={e => { e.preventDefault(); setDragging(true); }}
                    onDragLeave={() => setDragging(false)}
                    onClick={() => fileRef.current.click()}
                    className={`border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition-all ${
                      dragging ? "border-indigo-500 bg-indigo-50" : "border-slate-200 bg-slate-50 hover:bg-slate-100 hover:border-slate-300"
                    }`}
                  >
                    <div className="bg-white w-12 h-12 rounded-full flex items-center justify-center mx-auto mb-3 shadow-sm border border-slate-100">
                      {file ? <Package size={24} className="text-indigo-500" /> : <UploadCloud size={24} className="text-slate-400" />}
                    </div>
                    {file ? (
                      <div>
                        <p className="text-sm font-bold text-indigo-700 mb-1">{file.name}</p>
                        <p className="text-xs text-slate-500">{(file.size / 1024).toFixed(1)} KB - Nhấp để thay đổi</p>
                      </div>
                    ) : (
                      <div>
                        <p className="text-sm font-semibold text-slate-700 mb-1">Kéo thả ZIP testcase vào đây</p>
                        <p className="text-xs text-slate-500">Phải chứa exam.dart, grader.dart, skills_matrix.json</p>
                      </div>
                    )}
                    <input ref={fileRef} type="file" accept=".zip" className="hidden" onChange={(e) => handleFile(e.target.files)} />
                  </div>
                )}

                {/* Error Box */}
                {error && (
                  <div className="bg-rose-50 text-rose-600 border border-rose-200 rounded-xl p-4 flex items-start gap-3 mt-4">
                    <AlertCircle size={18} className="shrink-0 mt-0.5" />
                    <p className="text-sm font-medium">{error}</p>
                  </div>
                )}
              </div>
            </div>

            {/* Bước 3: Action */}
            <div className="mt-8 pt-6 border-t border-slate-100">
              {phase === "idle" && (
                <button 
                  onClick={execute}
                  disabled={!examId || !file}
                  className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white font-semibold py-3.5 px-4 rounded-xl flex items-center justify-center gap-2 transition-all shadow-sm shadow-indigo-600/20 active:scale-[0.98]"
                >
                  <Play size={18} />
                  Xây dựng môi trường chấm (Docker Build)
                </button>
              )}

              {/* Uploading State */}
              {phase === "uploading" && (
                <div className="bg-indigo-50 border border-indigo-100 rounded-xl p-8 text-center max-w-lg mx-auto">
                  <Loader2 size={32} className="animate-spin text-indigo-600 mx-auto mb-4" />
                  <h3 className="text-base font-bold text-indigo-900 mb-2">Đang khởi tạo Sandbox...</h3>
                  <p className="text-sm text-indigo-700/80">Hệ thống đang giải nén testcase và build Docker Image riêng biệt cho mã đề <strong>{examId}</strong>. Quá trình này có thể mất 1-2 phút.</p>
                </div>
              )}

              {/* Done State */}
              {phase === "done" && (
                <div className="text-center max-w-lg mx-auto">
                  <div className="bg-emerald-50 border border-emerald-100 rounded-xl p-8 mb-4">
                    <div className="w-16 h-16 bg-emerald-100 rounded-full flex items-center justify-center mx-auto mb-4">
                      <CheckCircle size={32} className="text-emerald-600" />
                    </div>
                    <h3 className="text-lg font-bold text-emerald-800 mb-2">Thành công!</h3>
                    <p className="text-sm text-emerald-700/80">
                      Môi trường chấm điểm cho đề thi <strong>{examId}</strong> đã sẵn sàng. Bạn có thể quay lại Dashboard để bắt đầu chấm bài sinh viên.
                    </p>
                  </div>
                  
                  <button onClick={reset} className="text-sm font-semibold text-slate-600 hover:text-slate-800 bg-white border border-slate-200 px-6 py-2.5 rounded-xl transition-all shadow-sm hover:shadow">
                    Cấu hình mã đề khác
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Thông tin thêm về cấu trúc file */}
        {phase === "idle" && (
          <div className="mt-8 bg-slate-100 rounded-2xl p-6 border border-slate-200/60">
            <h3 className="text-sm font-bold text-slate-700 mb-4 flex items-center gap-2">
              <Package size={16} /> Cấu trúc file ZIP yêu cầu
            </h3>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
                <div className="flex items-center gap-2 mb-2 text-indigo-600"><FileCode2 size={16} /><span className="font-mono text-sm font-bold">exam.dart</span></div>
                <p className="text-xs text-slate-500">File chứa các Unit/Widget test do giảng viên viết dùng package test của Flutter.</p>
              </div>
              <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
                <div className="flex items-center gap-2 mb-2 text-rose-600"><FileCode2 size={16} /><span className="font-mono text-sm font-bold">grader.dart</span></div>
                <p className="text-xs text-slate-500">Script điều phối việc chạy <code className="bg-slate-100 px-1 rounded">flutter test</code> và đọc kết quả máy chấm trả về dạng JSON.</p>
              </div>
              <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
                <div className="flex items-center gap-2 mb-2 text-emerald-600"><FileJson size={16} /><span className="font-mono text-sm font-bold">skills_matrix.json</span></div>
                <p className="text-xs text-slate-500">Rubric chứa ma trận điểm, định nghĩa điểm số cho từng tiêu chí testcase.</p>
              </div>
            </div>
          </div>
        )}

      </div>
    </SidebarLayout>
  );
}