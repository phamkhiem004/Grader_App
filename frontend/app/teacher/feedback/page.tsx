"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import { getToken } from "@/lib/auth";
import {
  MessageSquareText, Play, Square, Download, Loader2, CheckCircle2,
  AlertCircle, XCircle, Sparkles, ShieldAlert, RefreshCw,
} from "lucide-react";
import ExamCombobox from "@/components/ui/ExamCombobox";

interface ExamOption { examId: string; examName: string; }
interface StudentLite {
  studentId: string;
  studentName: string | null;
  score: number | null;
  status: string;
  hasJson?: boolean;
}
type RowState = "pending" | "loading" | "done" | "error";
interface FbRow {
  studentId: string;
  studentName: string | null;
  score: number | null;
  scoreSummary?: string | null;
  feedbackText?: string | null;
  teacherReviewRequired?: boolean;
  reviewReasons?: string[];
  sources?: string[];
  error?: string | null;
  _state: RowState;
}

// Số luồng song song: Ollama (CPU) xử lý tuần tự → 2 (nhiều hơn sẽ chờ quá lâu → timeout).
// API (openai) chạy song song thật → 8 luồng nhanh hơn nhiều cho hàng loạt bài.
const concurrencyFor = (provider: string) => (provider === "openai" ? 8 : 2);

// ── Xuất .xlsx THẬT (OOXML) — không cần thư viện ngoài, không lỗi "định dạng không khớp" ──
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(b: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function concatBytes(parts: Uint8Array[]): Uint8Array {
  let len = 0;
  for (const p of parts) len += p.length;
  const out = new Uint8Array(len);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}
const u16 = (n: number) => new Uint8Array([n & 0xff, (n >> 8) & 0xff]);
const u32 = (n: number) => new Uint8Array([n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff]);

function zipStore(files: { name: string; data: Uint8Array }[]): Uint8Array {
  const enc = new TextEncoder();
  const locals: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const f of files) {
    const nb = enc.encode(f.name);
    const crc = crc32(f.data);
    const sz = u32(f.data.length);
    const local = concatBytes([
      u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(crc), sz, sz, u16(nb.length), u16(0), nb, f.data,
    ]);
    locals.push(local);
    central.push(concatBytes([
      u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(crc), sz, sz, u16(nb.length), u16(0), u16(0), u16(0), u16(0), u32(0),
      u32(offset), nb,
    ]));
    offset += local.length;
  }
  const cd = concatBytes(central);
  const eocd = concatBytes([
    u32(0x06054b50), u16(0), u16(0), u16(files.length), u16(files.length),
    u32(cd.length), u32(offset), u16(0),
  ]);
  return concatBytes([...locals, cd, eocd]);
}

function buildXlsx(headers: string[], rows: string[][]): Uint8Array {
  const enc = new TextEncoder();
  const xesc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const colName = (i: number) => {
    let s = ""; i++;
    while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); }
    return s;
  };
  const all = [headers, ...rows];
  let sheetRows = "";
  all.forEach((row, r) => {
    const cells = row.map((val, c) =>
      `<c r="${colName(c)}${r + 1}" t="inlineStr"${r === 0 ? "" : ' s="1"'}><is><t xml:space="preserve">${xesc(String(val ?? ""))}</t></is></c>`
    ).join("");
    sheetRows += `<row r="${r + 1}">${cells}</row>`;
  });
  const part = (s: string) => enc.encode(s);
  const files = [
    { name: "[Content_Types].xml", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`) },
    { name: "_rels/.rels", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`) },
    { name: "xl/workbook.xml", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Nhan xet" sheetId="1" r:id="rId1"/></sheets></workbook>`) },
    { name: "xl/_rels/workbook.xml.rels", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`) },
    { name: "xl/styles.xml", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs></styleSheet>`) },
    { name: "xl/worksheets/sheet1.xml", data: part(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="5" max="5" width="60" customWidth="1"/></cols><sheetData>${sheetRows}</sheetData></worksheet>`) },
  ];
  return zipStore(files);
}

/** Chạy worker theo "pool" với số luồng giới hạn; dừng sớm nếu shouldStop() = true. */
async function runPool<T>(
  items: T[],
  concurrency: number,
  shouldStop: () => boolean,
  worker: (item: T, idx: number) => Promise<void>,
) {
  let cursor = 0;
  const lanes = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      if (shouldStop()) return;
      const idx = cursor++;
      await worker(items[idx], idx);
    }
  });
  await Promise.all(lanes);
}

export default function FeedbackPage() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [examId, setExamId] = useState("");
  const [rows, setRows] = useState<FbRow[]>([]);
  const [running, setRunning] = useState(false);
  const [loadingList, setLoadingList] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [botUp, setBotUp] = useState<boolean | null>(null);
  const [botBase, setBotBase] = useState<string>("");
  const [botProvider, setBotProvider] = useState<string>("");   // ollama | openai

  const stopRef = useRef(false);

  // Danh sách đề đã chấm (gợi ý cho ô nhập mã đề)
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then((r) => (r.ok ? r.json() : []))
      .then((d: ExamOption[]) => setExams(Array.isArray(d) ? d : []))
      .catch(() => setExams([]));
  }, []);

  // Kiểm tra bot có đang chạy không
  const checkBot = () => {
    setBotUp(null);
    fetch(`${API_BASE}/feedback/health`)
      .then((r) => (r.ok ? r.json() : { up: false }))
      .then((d) => { setBotUp(!!d.up); setBotBase(d.base || ""); setBotProvider(d.provider || ""); })
      .catch(() => setBotUp(false));
  };
  useEffect(checkBot, []);

  const done = rows.filter((r) => r._state === "done").length;
  const errored = rows.filter((r) => r._state === "error").length;
  const finished = rows.length > 0 && done + errored === rows.length;
  const reviewCount = useMemo(
    () => rows.filter((r) => r._state === "done" && r.teacherReviewRequired).length,
    [rows],
  );

  const patchRow = (idx: number, patch: Partial<FbRow>) =>
    setRows((list) => list.map((r, i) => (i === idx ? { ...r, ...patch } : r)));

  const run = async () => {
    const id = examId.trim();
    if (!id) { setError("Hãy nhập mã đề (ví dụ: PE_01)."); return; }
    setError(null);
    setRunning(true);
    stopRef.current = false;
    setLoadingList(true);
    setRows([]);

    let students: StudentLite[] = [];
    try {
      const res = await fetch(`${API_BASE}/results/exam/${encodeURIComponent(id)}`);
      const data = res.ok ? await res.json() : [];
      students = Array.isArray(data) ? data : [];
    } catch {
      students = [];
    } finally {
      setLoadingList(false);
    }

    if (students.length === 0) {
      setError(`Đề "${id}" chưa có bài nộp nào đã chấm. Kiểm tra lại mã đề.`);
      setRunning(false);
      return;
    }

    // Khởi tạo bảng ở trạng thái chờ
    const initial: FbRow[] = students.map((s) => ({
      studentId: s.studentId,
      studentName: s.studentName,
      score: s.score,
      _state: "pending",
    }));
    setRows(initial);

    const token = getToken();
    await runPool(students, concurrencyFor(botProvider), () => stopRef.current, async (s, idx) => {
      patchRow(idx, { _state: "loading" });
      try {
        const res = await fetch(
          `${API_BASE}/feedback/exam/${encodeURIComponent(id)}/${encodeURIComponent(s.studentId)}`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
          },
        );
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          patchRow(idx, { _state: "error", error: data.error || `HTTP ${res.status}` });
          return;
        }
        patchRow(idx, {
          ...data,
          _state: data.error ? "error" : "done",
        });
      } catch (e: any) {
        patchRow(idx, { _state: "error", error: e?.message || "Lỗi mạng" });
      }
    });

    setRunning(false);
  };

  const stop = () => { stopRef.current = true; setRunning(false); };

  const exportXls = () => {
    const ready = rows.filter((r) => r._state === "done" || r._state === "error");
    if (ready.length === 0) return;
    const headers = ["Mã SV", "Tên SV", "Điểm", "Tóm tắt điểm", "Lời nhận xét", "Cần GV xem lại", "Ghi chú"];
    const data: string[][] = ready.map((r) => [
      r.studentId ?? "",
      r.studentName ?? "",
      r.score != null ? String(r.score) : "",
      r.scoreSummary ?? "",
      r.feedbackText ?? "",
      r.teacherReviewRequired ? "Có" : "Không",
      r.error || (r.reviewReasons || []).join(" | ") || "",
    ]);
    // Dựng .xlsx THẬT (OOXML zip) → mở Excel không còn cảnh báo "định dạng không khớp".
    const bytes = buildXlsx(headers, data);
    const ab = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const blob = new Blob([ab], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    const stamp = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `nhan-xet_${examId.trim() || "de"}_${stamp}.xlsx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const progressPct = rows.length ? Math.round(((done + errored) / rows.length) * 100) : 0;

  return (
    <SidebarLayout
      title="Nhận xét bài làm bằng AI"
      subtitle="Nhập mã đề → AI đọc kết quả chấm (JSON) và viết lời nhận xét cho từng sinh viên"
      activePath="/teacher/feedback"
    >
      <div className="space-y-5">
        {/* Thanh điều khiển */}
        <div className="card p-5">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-[220px] flex-1">
              <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">
                Mã đề thi
              </label>
              <ExamCombobox
                options={exams}
                value={examId}
                onChange={setExamId}
                onEnter={() => { if (!running) run(); }}
                disabled={running}
                placeholder="VD: PE_01"
              />
            </div>

            {!running ? (
              <button
                onClick={run}
                className="flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95"
              >
                <Play size={16} /> Đọc &amp; nhận xét bài làm
              </button>
            ) : (
              <button
                onClick={stop}
                className="flex items-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-rose-700 active:scale-95"
              >
                <Square size={16} /> Dừng
              </button>
            )}

            <button
              onClick={exportXls}
              disabled={done + errored === 0}
              className="flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-2.5 text-sm font-semibold text-emerald-700 transition-all hover:bg-emerald-100 active:scale-95 disabled:opacity-40"
            >
              <Download size={16} /> Tải Excel (.xlsx)
            </button>
          </div>

          {/* Trạng thái bot */}
          <div className="mt-3 flex items-center gap-2 text-xs">
            {botUp === null ? (
              <span className="flex items-center gap-1.5 text-slate-400"><Loader2 size={13} className="animate-spin" /> Đang kiểm tra AI feedback bot…</span>
            ) : botUp ? (
              <span className="flex items-center gap-1.5 text-emerald-600">
                <CheckCircle2 size={13} /> Đã kết nối AI feedback bot
                {botProvider === "openai"
                  ? <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[11px] font-bold text-emerald-700">API nhanh · {botProvider} (8 luồng)</span>
                  : botProvider
                    ? <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-semibold text-amber-700">local · {botProvider} (chậm trên CPU, 2 luồng)</span>
                    : null}
              </span>
            ) : (
              <span className="flex items-center gap-1.5 text-rose-600">
                <ShieldAlert size={13} /> Chưa kết nối được AI feedback bot{botBase ? ` (${botBase})` : ""} — hãy chạy <code className="rounded bg-rose-50 px-1">uvicorn app.main:app</code> trong repo prm393-feedback-bot.
              </span>
            )}
            <button onClick={checkBot} className="ml-1 flex items-center gap-1 text-slate-400 hover:text-slate-600">
              <RefreshCw size={12} /> Kiểm tra lại
            </button>
          </div>

          {error && (
            <div className="mt-3 flex items-center gap-2 rounded-lg border border-rose-200 bg-rose-50 p-2.5 text-xs font-medium text-rose-600">
              <AlertCircle size={14} /> {error}
            </div>
          )}
        </div>

        {/* Tiến độ */}
        {rows.length > 0 && (
          <div className="card p-4">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-sm">
              <div className="flex items-center gap-3">
                <span className="font-semibold text-slate-700">
                  {done + errored}/{rows.length} bài
                </span>
                <span className="flex items-center gap-1 text-emerald-600"><CheckCircle2 size={14} /> {done} xong</span>
                {errored > 0 && <span className="flex items-center gap-1 text-rose-500"><XCircle size={14} /> {errored} lỗi</span>}
                {reviewCount > 0 && <span className="flex items-center gap-1 text-amber-600"><AlertCircle size={14} /> {reviewCount} cần GV xem lại</span>}
              </div>
              {running && <span className="flex items-center gap-1.5 text-xs text-indigo-500"><Loader2 size={13} className="animate-spin" /> Đang chạy…</span>}
            </div>
            <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100">
              <div
                className={`h-full rounded-full transition-all duration-300 ${finished ? "bg-emerald-500" : "bg-indigo-500"}`}
                style={{ width: `${progressPct}%` }}
              />
            </div>
          </div>
        )}

        {/* Bảng kết quả */}
        {rows.length === 0 ? (
          loadingList ? (
            <div className="card flex items-center justify-center gap-2 p-12 text-sm text-slate-400">
              <Loader2 size={18} className="animate-spin" /> Đang tải danh sách sinh viên…
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
                <MessageSquareText size={32} />
              </div>
              <h3 className="mb-1 text-base font-bold text-slate-700">Nhập mã đề rồi bấm “Đọc &amp; nhận xét bài làm”</h3>
              <p className="max-w-md text-sm text-slate-500">
                AI sẽ đọc kết quả chấm (JSON) của từng sinh viên trong đề và viết lời nhận xét kèm lời khuyên. Sau đó bạn có thể tải toàn bộ nhận xét ra file Excel.
              </p>
            </div>
          )
        ) : (
          <div className="card overflow-hidden">
            <div className="custom-scrollbar overflow-x-auto">
              <table className="w-full min-w-[820px] text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50/60 text-left text-xs font-bold uppercase tracking-wider text-slate-500">
                    <th className="px-4 py-3">Sinh viên</th>
                    <th className="px-4 py-3 text-center">Điểm</th>
                    <th className="px-4 py-3">Lời nhận xét của AI</th>
                    <th className="px-4 py-3 text-center">Trạng thái</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {rows.map((r) => (
                    <tr key={r.studentId} className="align-top hover:bg-slate-50/50">
                      <td className="px-4 py-3">
                        <p className="font-semibold text-slate-800">{r.studentName || "—"}</p>
                        <p className="font-mono text-xs text-slate-400">{r.studentId}</p>
                        {r.teacherReviewRequired && r._state === "done" && (
                          <span className="mt-1 inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-semibold text-amber-700">
                            <AlertCircle size={11} /> Cần GV xem lại
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className="font-bold text-slate-700">{r.score != null ? r.score.toFixed(1) : "—"}</span>
                        {r.scoreSummary && <p className="text-[11px] text-slate-400">{r.scoreSummary}</p>}
                      </td>
                      <td className="px-4 py-3">
                        {r._state === "done" ? (
                          <div className="custom-scrollbar max-h-44 overflow-y-auto whitespace-pre-wrap text-sm leading-relaxed text-slate-700">
                            {r.feedbackText || "—"}
                          </div>
                        ) : r._state === "error" ? (
                          <span className="text-xs text-rose-500">{r.error || "Lỗi sinh nhận xét"}</span>
                        ) : r._state === "loading" ? (
                          <span className="flex items-center gap-1.5 text-xs text-indigo-500"><Loader2 size={13} className="animate-spin" /> AI đang viết nhận xét…</span>
                        ) : (
                          <span className="text-xs text-slate-400">Đang chờ…</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-center">
                        {r._state === "done" ? (
                          <CheckCircle2 size={18} className="mx-auto text-emerald-500" />
                        ) : r._state === "error" ? (
                          <XCircle size={18} className="mx-auto text-rose-400" />
                        ) : r._state === "loading" ? (
                          <Loader2 size={18} className="mx-auto animate-spin text-indigo-400" />
                        ) : (
                          <span className="text-slate-300">•</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center gap-1.5 border-t border-slate-100 bg-slate-50/40 px-4 py-2.5 text-[11px] text-slate-400">
              <Sparkles size={12} /> Nhận xét do AI sinh tự động từ kết quả chấm — giảng viên nên rà soát trước khi gửi cho sinh viên.
            </div>
          </div>
        )}
      </div>
    </SidebarLayout>
  );
}
