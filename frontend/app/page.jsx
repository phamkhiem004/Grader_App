"use client";

import { useState, useRef, useCallback } from "react";

const API_BASE = "http://localhost:8080/api";

const STATUS_COLOR = {
  QUEUED: { bg: "#F1EFE8", text: "#5F5E5A", label: "Chờ" },
  GRADING: { bg: "#E6F1FB", text: "#185FA5", label: "Đang chấm" },
  DONE: { bg: "#EAF3DE", text: "#3B6D11", label: "Xong" },
  ERROR: { bg: "#FCEBEB", text: "#A32D2D", label: "Lỗi" },
};

function StatusBadge({ status }) {
  const s = STATUS_COLOR[status] || STATUS_COLOR.QUEUED;
  return (
    <span style={{
      background: s.bg, color: s.text,
      fontSize: 11, fontWeight: 600, padding: "2px 8px",
      borderRadius: 99, letterSpacing: "0.03em"
    }}>{s.label}</span>
  );
}

function ProgressBar({ done, total, error }) {
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  const errPct = total > 0 ? Math.round((error / total) * 100) : 0;
  return (
    <div style={{ marginTop: 6 }}>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, color: "#888780", marginBottom: 4 }}>
        <span>{done}/{total} bài</span>
        <span style={{ fontVariantNumeric: "tabular-nums", fontWeight: 600, color: pct === 100 ? "#3B6D11" : "#185FA5" }}>{pct}%</span>
      </div>
      <div style={{ height: 6, borderRadius: 99, background: "#E8E6DF", overflow: "hidden", display: "flex" }}>
        <div style={{ width: `${pct}%`, background: error > 0 ? "#3B6D11" : "#3B6D11", transition: "width .4s ease" }} />
        <div style={{ width: `${errPct}%`, background: "#A32D2D", transition: "width .4s ease" }} />
      </div>
    </div>
  );
}

export default function BatchGradingPage() {
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

  // ── File handling ───────────────────────────────────────────
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

  // ── Upload + poll ───────────────────────────────────────────
  const execute = async () => {
    if (!files.length) { setUploadErr("Chưa có file nào để chấm."); return; }
    if (!examId.trim()) { setUploadErr("Nhập mã đề thi."); return; }

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
    if (!progress?.results?.length) return;
    const header = "Mã SV,Họ tên,Điểm,Trạng thái,Batch\n";
    const rows = progress.results.map(r =>
      `${r.studentId},"${r.studentName || ""}",${r.score ?? ""},${r.status},${r.batchId}`
    ).join("\n");
    const blob = new Blob(["\uFEFF" + header + rows], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `ketqua_${batchId}.csv`;
    a.click();
  };

  // ── Derived ─────────────────────────────────────────────────
  const isRunning = phase === "uploading" || phase === "polling";
  const p = progress;

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
        ::-webkit-scrollbar { width:4px }
        ::-webkit-scrollbar-thumb { background:#C4C2BA; border-radius:2px }
        @keyframes spin { to { transform: rotate(360deg) } }
        @keyframes fadeIn { from { opacity:0; transform:translateY(8px) } to { opacity:1; transform:none } }
        .row-enter { animation: fadeIn .2s ease forwards }
      `}</style>

      <div style={{ maxWidth: 820, margin: "0 auto" }}>

        {/* ── Header ── */}
        <div style={{ marginBottom: 36 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6 }}>
            <div style={{
              width: 32, height: 32, borderRadius: 6,
              background: "#2C2C2A", display: "flex", alignItems: "center", justifyContent: "center"
            }}>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <rect x="2" y="2" width="5" height="5" rx="1" fill="#F5F3EE" />
                <rect x="9" y="2" width="5" height="5" rx="1" fill="#F5F3EE" opacity=".6" />
                <rect x="2" y="9" width="5" height="5" rx="1" fill="#F5F3EE" opacity=".6" />
                <rect x="9" y="9" width="5" height="5" rx="1" fill="#F5F3EE" />
              </svg>
            </div>
            <span style={{ fontSize: 13, fontWeight: 600, color: "#2C2C2A", letterSpacing: "0.08em", textTransform: "uppercase" }}>
              Hệ thống chấm thi tự động
            </span>
          </div>
          <p style={{ fontSize: 12, color: "#888780", margin: 0, fontFamily: "'IBM Plex Sans', sans-serif" }}>
            Upload hàng loạt bài nộp của sinh viên · Docker grader · Chấm 3 bài song song
          </p>
        </div>

        {/* ── Config row ── */}
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
            disabled={isRunning}
            placeholder="VD: FLUTTER_PE_01"
            style={{
              flex: 1, border: "1px solid #E3E1D9", borderRadius: 8,
              padding: "8px 12px", fontSize: 13, background: isRunning ? "#F5F3EE" : "#fff",
              color: "#2C2C2A", outline: "none"
            }}
          />
          <div style={{
            fontSize: 11, color: "#888780", whiteSpace: "nowrap",
            fontFamily: "'IBM Plex Sans', sans-serif"
          }}>
            {files.length} file đã chọn
          </div>
        </div>

        {/* ── Drop zone ── */}
        {phase === "idle" && (
          <div
            onDrop={onDrop}
            onDragOver={e => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onClick={() => fileRef.current.click()}
            style={{
              border: `2px dashed ${dragging ? "#2C2C2A" : "#C4C2BA"}`,
              borderRadius: 12, padding: "40px 24px", textAlign: "center",
              cursor: "pointer", transition: "all .15s",
              background: dragging ? "#EEECEA" : "#FAFAF8",
              marginBottom: 16
            }}
          >
            <div style={{ fontSize: 28, marginBottom: 8 }}>📂</div>
            <p style={{ fontSize: 13, color: "#444441", margin: "0 0 4px", fontWeight: 500, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              Kéo thả file ZIP vào đây hoặc bấm để chọn
            </p>
            <p style={{ fontSize: 11, color: "#888780", margin: 0, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              Chỉ nhận .zip · Format tên: <code style={{ background: "#F1EFE8", padding: "1px 5px", borderRadius: 4 }}>MaSV_HoTen.zip</code>
            </p>
            <input ref={fileRef} type="file" multiple accept=".zip"
              style={{ display: "none" }}
              onChange={e => addFiles(e.target.files)} />
          </div>
        )}

        {/* ── File list ── */}
        {files.length > 0 && phase === "idle" && (
          <div style={{
            background: "#fff", border: "1px solid #E3E1D9",
            borderRadius: 12, overflow: "hidden", marginBottom: 16
          }}>
            <div style={{
              padding: "10px 16px", borderBottom: "1px solid #F1EFE8",
              display: "flex", justifyContent: "space-between", alignItems: "center"
            }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: "#888780", letterSpacing: "0.06em", textTransform: "uppercase" }}>
                Danh sách bài nộp ({files.length})
              </span>
              <button onClick={() => setFiles([])} style={{
                border: "none", background: "none", fontSize: 11,
                color: "#A32D2D", fontWeight: 500, padding: "2px 6px"
              }}>Xóa tất cả</button>
            </div>
            <div style={{ maxHeight: 220, overflowY: "auto" }}>
              {files.map((f, i) => {
                const parts = f.name.replace(".zip", "").split("_");
                const maSV = parts[0] || "—";
                const tenSV = parts.slice(1).join(" ") || "—";
                return (
                  <div key={f.name} className="row-enter" style={{
                    display: "flex", alignItems: "center", gap: 12,
                    padding: "9px 16px",
                    borderBottom: i < files.length - 1 ? "1px solid #F5F3EE" : "none",
                    animationDelay: `${i * 20}ms`
                  }}>
                    <span style={{ fontSize: 11, color: "#B4B2A9", minWidth: 20, textAlign: "right" }}>{i + 1}</span>
                    <code style={{ fontSize: 12, color: "#185FA5", minWidth: 90 }}>{maSV}</code>
                    <span style={{ fontSize: 12, color: "#444441", flex: 1, fontFamily: "'IBM Plex Sans',sans-serif" }}>{tenSV}</span>
                    <span style={{ fontSize: 11, color: "#B4B2A9" }}>{(f.size / 1024).toFixed(0)} KB</span>
                    <button onClick={() => removeFile(f.name)} style={{
                      border: "none", background: "none", color: "#C4C2BA",
                      fontSize: 16, lineHeight: 1, padding: "0 2px",
                      transition: "color .1s"
                    }} onMouseEnter={e => e.target.style.color = "#A32D2D"}
                      onMouseLeave={e => e.target.style.color = "#C4C2BA"}>×</button>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* ── Error ── */}
        {uploadErr && (
          <div style={{
            background: "#FCEBEB", border: "1px solid #F7C1C1",
            borderRadius: 8, padding: "10px 14px", marginBottom: 16,
            fontSize: 12, color: "#791F1F", fontFamily: "'IBM Plex Sans',sans-serif"
          }}>⚠ {uploadErr}</div>
        )}
        {parseErrors.length > 0 && (
          <div style={{
            background: "#FAEEDA", border: "1px solid #FAC775",
            borderRadius: 8, padding: "10px 14px", marginBottom: 16, fontSize: 12
          }}>
            <div style={{ fontWeight: 600, color: "#633806", marginBottom: 4, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              File sai format tên — bị bỏ qua:
            </div>
            {parseErrors.map((e, i) => (
              <div key={i} style={{ color: "#854F0B", fontFamily: "'IBM Plex Sans',sans-serif" }}>• {e}</div>
            ))}
          </div>
        )}

        {/* ── Execute button ── */}
        {phase === "idle" && (
          <button onClick={execute} style={{
            width: "100%", padding: "14px", borderRadius: 10,
            background: "#2C2C2A", color: "#F5F3EE", border: "none",
            fontSize: 13, fontWeight: 600, letterSpacing: "0.05em",
            textTransform: "uppercase", transition: "background .15s"
          }}
            onMouseEnter={e => e.target.style.background = "#444441"}
            onMouseLeave={e => e.target.style.background = "#2C2C2A"}>
            ▶ Bắt đầu chấm tự động
          </button>
        )}

        {/* ── Uploading spinner ── */}
        {phase === "uploading" && (
          <div style={{
            background: "#fff", border: "1px solid #E3E1D9", borderRadius: 12,
            padding: "24px", textAlign: "center"
          }}>
            <div style={{
              width: 24, height: 24, border: "2px solid #E3E1D9",
              borderTopColor: "#2C2C2A", borderRadius: "50%",
              animation: "spin .8s linear infinite", margin: "0 auto 12px"
            }} />
            <p style={{ fontSize: 13, color: "#444441", margin: 0, fontFamily: "'IBM Plex Sans',sans-serif" }}>
              Đang upload {files.length} bài lên server...
            </p>
          </div>
        )}

        {/* ── Progress panel ── */}
        {(phase === "polling" || phase === "done") && p && (
          <div style={{ animation: "fadeIn .3s ease" }}>

            {/* Summary cards */}
            <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 10, marginBottom: 16 }}>
              {[
                { label: "Tổng", val: p.total, color: "#2C2C2A" },
                { label: "Xong", val: p.done, color: "#3B6D11" },
                { label: "Đang chấm", val: p.grading, color: "#185FA5" },
                { label: "Lỗi", val: p.error, color: "#A32D2D" },
              ].map(c => (
                <div key={c.label} style={{
                  background: "#fff", border: "1px solid #E3E1D9",
                  borderRadius: 10, padding: "14px 16px", textAlign: "center"
                }}>
                  <div style={{ fontSize: 22, fontWeight: 600, color: c.color, marginBottom: 2 }}>{c.val}</div>
                  <div style={{ fontSize: 11, color: "#888780", letterSpacing: "0.04em", fontFamily: "'IBM Plex Sans',sans-serif" }}>{c.label}</div>
                </div>
              ))}
            </div>

            {/* Progress bar */}
            <div style={{
              background: "#fff", border: "1px solid #E3E1D9",
              borderRadius: 12, padding: "16px 20px", marginBottom: 16
            }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
                <span style={{ fontSize: 11, fontWeight: 600, color: "#888780", letterSpacing: "0.06em", textTransform: "uppercase" }}>
                  Tiến độ chấm — batch: <code style={{ color: "#185FA5" }}>{batchId}</code>
                </span>
                {phase === "polling" && (
                  <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                    <div style={{
                      width: 8, height: 8, borderRadius: "50%", background: "#3B6D11",
                      animation: "spin 1s linear infinite", border: "1.5px solid #97C459", borderTopColor: "transparent"
                    }} />
                    <span style={{ fontSize: 11, color: "#3B6D11", fontFamily: "'IBM Plex Sans',sans-serif" }}>Đang chạy...</span>
                  </div>
                )}
                {phase === "done" && (
                  <span style={{ fontSize: 11, color: "#3B6D11", fontWeight: 600, fontFamily: "'IBM Plex Sans',sans-serif" }}>✓ Hoàn tất</span>
                )}
              </div>
              <ProgressBar done={p.done} total={p.total} error={p.error} />
            </div>

            {/* Results table */}
            <div style={{
              background: "#fff", border: "1px solid #E3E1D9",
              borderRadius: 12, overflow: "hidden", marginBottom: 16
            }}>
              <div style={{
                padding: "10px 16px", borderBottom: "1px solid #F1EFE8",
                display: "flex", justifyContent: "space-between", alignItems: "center"
              }}>
                <span style={{ fontSize: 11, fontWeight: 600, color: "#888780", letterSpacing: "0.06em", textTransform: "uppercase" }}>
                  Kết quả từng sinh viên
                </span>
                {phase === "done" && (
                  <button onClick={downloadCSV} style={{
                    border: "1px solid #E3E1D9", borderRadius: 6, background: "#F5F3EE",
                    fontSize: 11, fontWeight: 600, color: "#444441", padding: "4px 10px",
                    letterSpacing: "0.04em"
                  }}>↓ Xuất CSV</button>
                )}
              </div>

              {/* Table header */}
              <div style={{
                display: "grid", gridTemplateColumns: "100px 1fr 80px 100px 100px",
                padding: "8px 16px", background: "#FAFAF8",
                borderBottom: "1px solid #F1EFE8",
                fontSize: 10, fontWeight: 600, color: "#B4B2A9",
                letterSpacing: "0.07em", textTransform: "uppercase"
              }}>
                <span>Mã SV</span>
                <span>Họ tên</span>
                <span style={{ textAlign: "center" }}>Điểm</span>
                <span style={{ textAlign: "center" }}>Trạng thái</span>
                <span style={{ textAlign: "center" }}>Pass/Total</span>
              </div>

              <div style={{ maxHeight: 380, overflowY: "auto" }}>
                {p.results?.map((r, i) => {
                  let passCount = 0, totalCount = 0;
                  try {
                    const d = JSON.parse(r.details || "{}");
                    passCount = d.soTestPass ?? 0;
                    totalCount = d.tongSoTest ?? 0;
                  } catch (_) { }
                  return (
                    <div key={r.id} className="row-enter" style={{
                      display: "grid",
                      gridTemplateColumns: "100px 1fr 80px 100px 100px",
                      padding: "10px 16px",
                      borderBottom: i < p.results.length - 1 ? "1px solid #F5F3EE" : "none",
                      alignItems: "center",
                      animationDelay: `${i * 15}ms`
                    }}>
                      <code style={{ fontSize: 12, color: "#185FA5" }}>{r.studentId}</code>
                      <span style={{ fontSize: 12, color: "#2C2C2A", fontFamily: "'IBM Plex Sans',sans-serif" }}>
                        {r.studentName || "—"}
                      </span>
                      <span style={{
                        textAlign: "center", fontSize: 14, fontWeight: 600,
                        color: r.score >= 5 ? "#3B6D11" : r.score != null ? "#A32D2D" : "#888780"
                      }}>
                        {r.score != null ? r.score.toFixed(1) : "—"}
                      </span>
                      <span style={{ textAlign: "center" }}>
                        <StatusBadge status={r.status} />
                      </span>
                      <span style={{ textAlign: "center", fontSize: 12, color: "#888780", fontFamily: "'IBM Plex Sans',sans-serif" }}>
                        {r.status === "DONE" ? `${passCount}/${totalCount}` : "—"}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Reset button */}
            {phase === "done" && (
              <button onClick={reset} style={{
                width: "100%", padding: "12px", borderRadius: 10,
                background: "none", color: "#2C2C2A",
                border: "1.5px solid #C4C2BA",
                fontSize: 12, fontWeight: 600, letterSpacing: "0.05em", textTransform: "uppercase"
              }}
                onMouseEnter={e => { e.target.style.background = "#F1EFE8" }}
                onMouseLeave={e => { e.target.style.background = "none" }}>
                ↺ Chấm batch mới
              </button>
            )}
          </div>
        )}

      </div>
    </div>
  );
}