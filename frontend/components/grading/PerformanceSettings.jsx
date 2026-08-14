"use client";

// Thẻ "Hiệu năng chấm" trong trang Chấm bài tự động: chỉnh CPU/RAM mỗi container Docker và số
// bài chấm song song mà KHÔNG cần sửa application.yml rồi khởi động lại backend.
// Backend: GET/POST /api/grading-runtime/settings (validate + lưu DB + đổi số worker ngay).

import { useCallback, useEffect, useMemo, useState } from "react";
import { API_BASE } from "@/lib/config";
import {
  Gauge, Cpu, MemoryStick, Layers, Timer, Save, RotateCcw, Loader2,
  ChevronDown, AlertTriangle, CheckCircle2, Server,
} from "lucide-react";

const fmtMem = (mb) => (mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 === 0 ? 0 : 1)} GB` : `${mb} MB`);

export default function PerformanceSettings({ running = false }) {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState(null);     // phản hồi backend (settings đã lưu + limits + host)
  const [draft, setDraft] = useState(null);   // giá trị đang chỉnh trên giao diện
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(null); // "save" | "reset"
  const [msg, setMsg] = useState(null);       // { type: "ok" | "err", text }

  const load = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/grading-runtime/settings`);
      const d = await res.json();
      if (!res.ok) throw new Error(d.error || "Không đọc được cấu hình hiệu năng.");
      setData(d);
      setDraft(d.settings);
    } catch (e) {
      setMsg({ type: "err", text: e.message || "Không kết nối được server." });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const limits = data?.limits;
  const host = data?.host;

  const dirty = useMemo(
    () => !!draft && !!data && JSON.stringify(draft) !== JSON.stringify(data.settings),
    [draft, data]
  );

  const totalCpus = draft ? draft.cpus * draft.maxConcurrent : 0;
  const totalMemMb = draft ? draft.memoryMb * draft.maxConcurrent : 0;

  // Cảnh báo tính tại chỗ để kéo thanh trượt là thấy ngay. Ràng buộc CỨNG (min/max) vẫn do
  // backend kiểm tra lần cuối khi lưu.
  const warnings = useMemo(() => {
    if (!draft) return [];
    const out = [];
    if (host?.cpus && totalCpus > host.cpus)
      out.push(`Tổng ${totalCpus.toFixed(1)} CPU vượt ${host.cpus} CPU Docker nhìn thấy — các bài sẽ giành CPU của nhau, chấm CHẬM hơn chứ không nhanh hơn.`);
    if (host?.memoryMb && totalMemMb > host.memoryMb * 0.85)
      out.push(`Tổng RAM ${fmtMem(totalMemMb)} gần chạm ${fmtMem(host.memoryMb)} của Docker — container dễ bị giết vì hết bộ nhớ, bài đúng vẫn báo lỗi biên dịch.`);
    if (draft.memoryMb < 1536)
      out.push("RAM dưới 1.5 GB thường không đủ để compile Flutter: bài làm đúng vẫn có thể ra 0/0.");
    if (draft.cpus < 1)
      out.push("Dưới 1 CPU mỗi bài, compile Flutter rất chậm và dễ chạm thời gian tối đa — nên tăng luôn ô thời gian.");
    if (host && host.dockerAvailable === false)
      out.push(`Chưa đọc được thông tin Docker${host.error ? ` (${host.error})` : ""} — giới hạn đang lấy tạm theo CPU của máy.`);
    return out;
  }, [draft, host, totalCpus, totalMemMb]);

  const set = (key, value) => setDraft((d) => ({ ...d, [key]: value }));

  const applyPreset = (p) => {
    setMsg(null);
    setDraft((d) => ({ ...d, cpus: p.cpus, memoryMb: p.memoryMb, maxConcurrent: p.maxConcurrent }));
  };

  const submit = async (mode) => {
    if (saving) return;
    setSaving(mode);
    setMsg(null);
    try {
      const url = mode === "reset"
        ? `${API_BASE}/grading-runtime/settings/reset`
        : `${API_BASE}/grading-runtime/settings`;
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: mode === "reset" ? undefined : JSON.stringify(draft),
      });
      const d = await res.json();
      if (!res.ok) throw new Error(d.error || "Lưu cấu hình thất bại.");
      setData(d);
      setDraft(d.settings);
      setMsg({
        type: "ok",
        text: mode === "reset"
          ? "Đã khôi phục cấu hình mặc định."
          : "Đã lưu. Số bài song song áp dụng ngay; CPU/RAM áp dụng từ bài chấm kế tiếp.",
      });
    } catch (e) {
      setMsg({ type: "err", text: e.message || "Không kết nối được server." });
    } finally {
      setSaving(null);
    }
  };

  const summary = draft
    ? `${draft.cpus} CPU · ${fmtMem(draft.memoryMb)} · ${draft.maxConcurrent} bài song song`
    : "Đang tải...";

  return (
    <div className="card overflow-hidden">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-violet-50 to-indigo-50 px-6 py-4 text-left"
      >
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 text-white shadow-sm">
          <Gauge size={18} />
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-bold text-slate-800">Hiệu năng chấm</h2>
          <p className="truncate text-xs text-slate-500">{summary}</p>
        </div>
        {dirty && <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700">Chưa lưu</span>}
        <ChevronDown size={18} className={`shrink-0 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {open && (
        <div className="space-y-5 p-6">
          {loading || !draft || !limits ? (
            <div className="flex items-center justify-center gap-2 py-6 text-sm text-slate-400">
              <Loader2 size={16} className="animate-spin" /> Đang đọc cấu hình...
            </div>
          ) : (
            <>
              {/* Mức dựng sẵn tính theo năng lực máy */}
              {data.presets?.length > 0 && (
                <div>
                  <p className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">Mức dựng sẵn</p>
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                    {data.presets.map((p) => {
                      const active = draft.cpus === p.cpus && draft.memoryMb === p.memoryMb
                        && draft.maxConcurrent === p.maxConcurrent;
                      return (
                        <button
                          key={p.key}
                          onClick={() => applyPreset(p)}
                          title={p.description}
                          className={`rounded-xl border p-3 text-left transition-all ${
                            active
                              ? "border-indigo-300 bg-indigo-50 ring-1 ring-indigo-200"
                              : "border-slate-200 bg-white hover:border-indigo-200 hover:bg-slate-50"
                          }`}
                        >
                          <p className="text-sm font-bold text-slate-800">{p.label}</p>
                          <p className="mt-0.5 font-mono text-[11px] text-slate-500">
                            {p.cpus} CPU · {fmtMem(p.memoryMb)} · ×{p.maxConcurrent}
                          </p>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              <Control
                icon={Cpu}
                label="CPU mỗi bài"
                hint="Tăng giúp compile Flutter nhanh hơn"
                value={draft.cpus}
                min={limits.cpusMin}
                max={limits.cpusMax}
                step={limits.cpusStep}
                display={`${draft.cpus} CPU`}
                onChange={(v) => set("cpus", Math.round(v * 10) / 10)}
              />
              <Control
                icon={MemoryStick}
                label="RAM mỗi bài"
                hint="Thiếu RAM → bài đúng vẫn báo lỗi biên dịch"
                value={draft.memoryMb}
                min={limits.memoryMbMin}
                max={limits.memoryMbMax}
                step={limits.memoryMbStep}
                display={fmtMem(draft.memoryMb)}
                onChange={(v) => set("memoryMb", Math.round(v))}
              />
              <Control
                icon={Layers}
                label="Số bài chấm song song"
                hint="Số container chạy cùng lúc trong một lượt chấm"
                value={draft.maxConcurrent}
                min={limits.maxConcurrentMin}
                max={limits.maxConcurrentMax}
                step={1}
                display={`${draft.maxConcurrent} bài`}
                onChange={(v) => set("maxConcurrent", Math.round(v))}
              />
              <Control
                icon={Timer}
                label="Thời gian tối đa mỗi bài"
                hint="Quá hạn thì bài chuyển sang diện chấm tay, không bị 0 điểm"
                value={draft.timeoutSeconds}
                min={limits.timeoutSecondsMin}
                max={limits.timeoutSecondsMax}
                step={limits.timeoutSecondsStep}
                display={`${draft.timeoutSeconds}s`}
                onChange={(v) => set("timeoutSeconds", Math.round(v))}
              />

              {/* Tổng tài nguyên so với máy */}
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                <div className="mb-1.5 flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
                  <Server size={13} /> Tổng tài nguyên khi chạy hết công suất
                </div>
                <p className="text-sm font-semibold text-slate-700">
                  {totalCpus.toFixed(1)} CPU
                  {host?.cpus ? <span className="text-slate-400"> / {host.cpus}</span> : null}
                  <span className="mx-2 text-slate-300">·</span>
                  {fmtMem(totalMemMb)}
                  {host?.memoryMb ? <span className="text-slate-400"> / {fmtMem(host.memoryMb)}</span> : null}
                </p>
                {data.runtime && (
                  <p className="mt-1 text-[11px] text-slate-400">
                    Đang chạy: {data.runtime.activeWorkers}/{data.runtime.targetWorkers} luồng chấm
                    {data.runtime.queuedJobs > 0 ? ` · ${data.runtime.queuedJobs} bài đang chờ` : ""}
                  </p>
                )}
              </div>

              {warnings.length > 0 && (
                <ul className="space-y-2">
                  {warnings.map((w, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-2.5 text-xs leading-relaxed text-amber-800">
                      <AlertTriangle size={14} className="mt-0.5 shrink-0" /> {w}
                    </li>
                  ))}
                </ul>
              )}

              {running && (
                <p className="rounded-xl border border-blue-100 bg-blue-50 p-2.5 text-xs leading-relaxed text-blue-700">
                  Đang có phiên chấm chạy: đổi số bài song song có hiệu lực ngay (bài đang chấm dở không bị cắt),
                  còn CPU/RAM chỉ áp dụng cho bài bắt đầu sau khi lưu.
                </p>
              )}

              {msg && (
                <div className={`flex items-start gap-2 rounded-xl border p-2.5 text-xs font-medium ${
                  msg.type === "ok"
                    ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                    : "border-rose-200 bg-rose-50 text-rose-600"
                }`}>
                  {msg.type === "ok" ? <CheckCircle2 size={14} className="mt-0.5 shrink-0" /> : <AlertTriangle size={14} className="mt-0.5 shrink-0" />}
                  <span className="leading-relaxed">{msg.text}</span>
                </div>
              )}

              <div className="flex items-center gap-2">
                <button
                  onClick={() => submit("save")}
                  disabled={!dirty || saving !== null}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:from-violet-700 hover:to-indigo-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300 disabled:shadow-none"
                >
                  {saving === "save" ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />}
                  Lưu cấu hình
                </button>
                <button
                  onClick={() => submit("reset")}
                  disabled={saving !== null}
                  title="Về đúng cấu hình mặc định của hệ thống"
                  className="flex items-center gap-2 rounded-xl border border-slate-200 px-3 py-2.5 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-60"
                >
                  {saving === "reset" ? <Loader2 size={16} className="animate-spin" /> : <RotateCcw size={15} />}
                  Mặc định
                </button>
              </div>

              {data.updatedAt && (
                <p className="text-[11px] text-slate-400">
                  Lần chỉnh gần nhất: {new Date(data.updatedAt).toLocaleString("vi-VN")}
                  {data.updatedBy ? ` · ${data.updatedBy}` : ""}
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

// Một dòng cấu hình: thanh trượt để chỉnh nhanh + ô số để gõ chính xác.
function Control({ icon: Icon, label, hint, value, min, max, step, display, onChange }) {
  const clamp = (v) => Math.min(max, Math.max(min, v));

  // Ô số giữ NGUYÊN VĂN người dùng đang gõ và chỉ chốt khi rời ô/Enter: nếu ép kiểu ngay từng
  // phím thì gõ "1536" sẽ bị kẹp về min ngay ở phím "1".
  const [text, setText] = useState(String(value));
  useEffect(() => { setText(String(value)); }, [value]);
  const commit = () => {
    const v = parseFloat(text);
    if (isNaN(v)) { setText(String(value)); return; }
    const c = clamp(v);
    setText(String(c));
    onChange(c);
  };

  return (
    <div>
      <div className="mb-1.5 flex items-center gap-2">
        <Icon size={15} className="shrink-0 text-indigo-500" />
        <span className="text-sm font-semibold text-slate-700">{label}</span>
        <input
          type="number"
          value={text}
          min={min}
          max={max}
          step={step}
          onChange={(e) => setText(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(); } }}
          className="ml-auto w-24 rounded-lg border border-slate-200 bg-white px-2 py-1 text-right text-sm font-bold text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
        />
      </div>
      <input
        type="range"
        value={value}
        min={min}
        max={max}
        step={step}
        onChange={(e) => onChange(clamp(parseFloat(e.target.value)))}
        className="w-full accent-indigo-600"
      />
      <div className="mt-0.5 flex items-center justify-between text-[11px] text-slate-400">
        <span>{hint}</span>
        <span className="font-mono font-semibold text-slate-500">{display}</span>
      </div>
    </div>
  );
}
