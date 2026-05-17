"use client";

import { useState, useRef, useCallback } from "react";

const API_BASE = "http://localhost:8080/api";

export default function TeacherSetupPage() {
  const [examId, setExamId] = useState("");
  const [file, setFile] = useState(null);
  const [dragging, setDragging] = useState(false);
  const [phase, setPhase] = useState("idle"); // idle | uploading | done
  const [error, setError] = useState(null);
  const fileRef = useRef();

  // ── Xử lý File ZIP ───────────────────────────────────────────
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

  // ── Gửi dữ liệu lên Spring Boot ──────────────────────────────
  const execute = async () => {
    if (!examId.trim()) { setError("Vui lòng nhập Mã đề thi."); return; }
    if (!file) { setError("Vui lòng chọn file cấu hình (.zip)."); return; }

    setPhase("uploading");
    setError(null);

    const form = new FormData();
    form.append("examId", examId.trim());
    form.append("testcase", file);

    try {
      // API này sẽ xử lý giải nén và build Docker Image cho đề thi
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
    <div style={{
      minHeight: "100vh", background: "#F5F3EE",
      fontFamily: "'IBM Plex Mono', 'Courier New', monospace",
      padding: "40px 24px"
    }}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&display=swap');
        * { box-sizing: border-box; }
        button { cursor: pointer; font-family: inherit; }
        input  { font-family: inherit; }
        @keyframes spin { to { transform: rotate(360deg) } }
        @keyframes fadeIn { from { opacity:0; transform:translateY(8px) } to { opacity:1; transform:none } }
        .fade-enter { animation: fadeIn .3s ease forwards }
      `}</style>

      <div style={{ maxWidth: 820, margin: "0 auto" }}>

        {/* ── Header (Giữ nguyên style) ── */}
        <div style={{ marginBottom: 36 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
            <div style={{
              width: 32, height: 32, borderRadius: 6,
              background: "#185FA5", display: "flex", alignItems: "center", justifyContent: "center"
            }}>
              <span style={{ color: "#fff", fontSize: 16 }}>🛠</span>
            </div>
            <span style={{ fontSize: 13, fontWeight: 600, color: "#2C2C2A", letterSpacing: "0.08em", textTransform: "uppercase" }}>
              Phân hệ Giảng viên: Setup Đề Thi
            </span>
          </div>
          <p style={{ fontSize: 12, color: "#888780", margin: 0, fontFamily: "'IBM Plex Sans', sans-serif" }}>
            Cấu hình Testcase (.dart) · Script chấm điểm · Bảng phân bổ điểm (.json)
          </p>
        </div>

        {/* ── Nhập mã đề ── */}
        <div style={{
          background: "#fff", border: "1px solid #E3E1D9",
          borderRadius: 12, padding: "16px 20px", marginBottom: 16,
          display: "flex", alignItems: "center", gap: 16
        }}>
          <label style={{ fontSize: 11, fontWeight: 600, color: "#888780", letterSpacing: "0.06em", textTransform: "uppercase", whiteSpace: "nowrap" }}>
            Mã đề thi
          </label>
          <input
            value={examId}
            onChange={e => setExamId(e.target.value)}
            disabled={isRunning || phase === "done"}
            placeholder="VD: FLUTTER_PE_01"
            style={{
              flex: 1, border: "1px solid #E3E1D9", borderRadius: 8,
              padding: "8px 12px", fontSize: 13, background: (isRunning || phase === "done") ? "#F5F3EE" : "#fff",
              color: "#2C2C2A", outline: "none"
            }}
          />
        </div>

        {/* ── Drop zone (Kéo thả giống trang SV) ── */}
        {phase === "idle" && (
          <div
            onDrop={onDrop}
            onDragOver={e => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onClick={() => fileRef.current.click()}
            style={{
              border: `2px dashed ${dragging ? "#185FA5" : "#C4C2BA"}`,
              borderRadius: 12, padding: "60px 24px", textAlign: "center",
              cursor: "pointer", transition: "all .15s",
              background: dragging ? "#E6F1FB" : "#FAFAF8",
              marginBottom: 16
            }}
          >
            <div style={{ fontSize: 32, marginBottom: 12 }}>🗜️</div>
            <p style={{ fontSize: 13, color: "#444441", margin: "0 0 6px", fontWeight: 500, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              {file ? <span style={{ color: "#185FA5" }}>Đã chọn: {file.name}</span> : "Kéo thả file ZIP chứa Testcase vào đây"}
            </p>
            <p style={{ fontSize: 11, color: "#888780", margin: 0, fontFamily: "'IBM Plex Sans',sans-serif", lineHeight: 1.6 }}>
              ZIP phải chứa: <code style={{ background: "#F1EFE8", padding: "2px 5px", borderRadius: 4 }}>exam_test.dart</code>,
              <code style={{ background: "#F1EFE8", padding: "2px 5px", borderRadius: 4, margin: "0 4px" }}>grader.dart</code>,
              <code style={{ background: "#F1EFE8", padding: "2px 5px", borderRadius: 4 }}>skills_matrix.json</code>
            </p>
            <input ref={fileRef} type="file" accept=".zip"
              style={{ display: "none" }}
              onChange={(e) => handleFile(e.target.files)} />
          </div>
        )}

        {/* ── Hiển thị lỗi ── */}
        {error && (
          <div className="fade-enter" style={{
            background: "#FCEBEB", border: "1px solid #F7C1C1",
            borderRadius: 8, padding: "12px 16px", marginBottom: 16,
            fontSize: 12, color: "#791F1F", fontFamily: "'IBM Plex Sans',sans-serif"
          }}>⚠ {error}</div>
        )}

        {/* ── Nút thực thi ── */}
        {phase === "idle" && (
          <button onClick={execute} style={{
            width: "100%", padding: "14px", borderRadius: 10,
            background: "#185FA5", color: "#fff", border: "none",
            fontSize: 13, fontWeight: 600, letterSpacing: "0.05em",
            textTransform: "uppercase", transition: "background .15s"
          }}
            onMouseEnter={e => e.target.style.background = "#134b82"}
            onMouseLeave={e => e.target.style.background = "#185FA5"}>
            ⬆ Upload và Cấu hình môi trường chấm
          </button>
        )}

        {/* ── Trạng thái đang xử lý ── */}
        {phase === "uploading" && (
          <div className="fade-enter" style={{
            background: "#fff", border: "1px solid #E3E1D9", borderRadius: 12,
            padding: "40px", textAlign: "center"
          }}>
            <div style={{
              width: 28, height: 28, border: "3px solid #E6F1FB",
              borderTopColor: "#185FA5", borderRadius: "50%",
              animation: "spin 1s linear infinite", margin: "0 auto 16px"
            }} />
            <p style={{ fontSize: 14, fontWeight: 600, color: "#185FA5", margin: "0 0 8px", fontFamily: "'IBM Plex Sans',sans-serif" }}>
              Đang khởi tạo Sandbox...
            </p>
            <p style={{ fontSize: 12, color: "#888780", margin: 0, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              Đang giải nén testcase và chuẩn bị Docker Image cho mã đề {examId}.
            </p>
          </div>
        )}

        {/* ── Trạng thái hoàn tất ── */}
        {phase === "done" && (
          <div className="fade-enter" style={{ textAlign: "center" }}>
            <div style={{
              background: "#EAF3DE", border: "1px solid #C4D9A8", borderRadius: 12,
              padding: "40px", marginBottom: 16
            }}>
              <div style={{ fontSize: 40, marginBottom: 16 }}>✅</div>
              <p style={{ fontSize: 15, fontWeight: 600, color: "#3B6D11", margin: "0 0 8px", fontFamily: "'IBM Plex Sans',sans-serif" }}>
                Thành công!
              </p>
              <p style={{ fontSize: 13, color: "#546e32", margin: 0, fontFamily: "'IBM Plex Sans',sans-serif" }}>
                Đề thi <strong>{examId}</strong> đã được nạp Testcase thành công. Giờ bạn có thể quay lại trang chủ để chấm bài.
              </p>
            </div>
            <button onClick={reset} style={{
              width: "100%", padding: "12px", borderRadius: 10,
              background: "#fff", color: "#2C2C2A", border: "1.5px solid #E3E1D9",
              fontSize: 12, fontWeight: 600, textTransform: "uppercase"
            }}>
              ↺ Thiết lập mã đề khác
            </button>
          </div>
        )}
      </div>
    </div>
  );
}