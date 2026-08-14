"use client";

// Trợ lý AI soạn đề trong trang "Tạo bộ testcase".
//
// Triết lý: AI chỉ ĐỀ XUẤT, giáo viên quyết định. Mỗi bước đều có chỗ sửa tay + ô nhắc AI sửa
// lại + nút chấp nhận riêng; không bước nào tự ghi vào bộ testcase đang chấm.
//
// Backend: /api/ai/* (xem AiAuthorController). Testcase do AI đề xuất luôn là template có sẵn
// trong thư viện nên vẫn đi qua đúng bộ kiểm tra tham số khi lưu.

import { useCallback, useEffect, useMemo, useState } from "react";
import { API_BASE } from "@/lib/config";
import {
  Sparkles, Settings2, KeyRound, Wand2, FileText, Image as ImageIcon, ListChecks,
  Loader2, Check, X, Plus, Trash2, RefreshCw, AlertTriangle, ChevronDown, Save, Info, FileCode2,
} from "lucide-react";

export interface AiContractKey {
  key: string;
  label: string;
  strategy: string;
  value?: string;
  index: number;
  evidence?: string;
}

export interface AiProposedItem {
  instance_id: string;
  template_id: string;
  runner: string;
  name: string;
  description: string;
  skill_code: string;
  layer: string;
  difficulty: string;
  weight: number;
  parameters: Record<string, unknown>;
  enabled: boolean;
  criterion?: string;
  reason?: string;
}

interface MockupScreen { id: string; title: string; svg: string; keys: string[] }
interface StarterFile { path: string; content: string; summary: string }
interface AiSettings {
  provider: string; model: string; baseUrl: string; hasApiKey: boolean;
  apiKeyMasked: string | null; timeoutSeconds: number; providers: string[];
  defaultModels: Record<string, string>; ready: boolean;
}

interface Props {
  examId: string;
  /** Các key đã khai ở Khu vực 0 — để biết key nào AI đề xuất là mới. */
  existingKeys: string[];
  onApplyContract: (keys: AiContractKey[], requireKeys: boolean) => void;
  onApplyItems: (items: AiProposedItem[]) => void;
}

const STRATEGIES = ["key_only", "auto", "widget_type", "icon", "tooltip", "text", "button_text", "type_with_text"];

export default function AiAuthorPanel({ examId, existingKeys, onApplyContract, onApplyItems }: Props) {
  const [open, setOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [settingsDraft, setSettingsDraft] = useState({ provider: "gemini", model: "", baseUrl: "", timeoutSeconds: 180 });
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // Bước 1 — yêu cầu đề
  const [req, setReq] = useState({
    topic: "", knowledge: "", screens: "", features: "", entity: "",
    difficulty: "Trung bình", duration: "90 phút", note: "",
  });

  // Bước 2 — đề bài
  const [deBai, setDeBai] = useState("");
  const [summary, setSummary] = useState("");
  const [criteria, setCriteria] = useState<{ name: string; points: number }[]>([]);
  const [revisePrompt, setRevisePrompt] = useState("");
  const [examAccepted, setExamAccepted] = useState(false);

  // Bước 3 — Item Key + hình
  const [keys, setKeys] = useState<AiContractKey[]>([]);
  const [requireKeys, setRequireKeys] = useState(true);
  const [mockupSpec, setMockupSpec] = useState<unknown>(null);
  const [screens, setScreens] = useState<MockupScreen[]>([]);
  const [keyNotes, setKeyNotes] = useState<string[]>([]);
  const [keysAccepted, setKeysAccepted] = useState(false);

  // Bước 4 — testcase
  const [proposed, setProposed] = useState<AiProposedItem[]>([]);
  const [rejected, setRejected] = useState<{ template_id: string; reason: string }[]>([]);
  const [tcNotes, setTcNotes] = useState<string[]>([]);
  const [missingKeys, setMissingKeys] = useState<string[]>([]);

  // Bước 5 — khung starter phát cho sinh viên
  const [starterFiles, setStarterFiles] = useState<StarterFile[]>([]);
  const [starterWarnings, setStarterWarnings] = useState<string[]>([]);
  const [starterNotes, setStarterNotes] = useState<string[]>([]);
  const [syntax, setSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);

  const loadSettings = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/ai/settings`);
      const data = (await res.json()) as AiSettings;
      if (!res.ok) throw new Error("Không đọc được cấu hình AI");
      setSettings(data);
      setSettingsDraft({
        provider: data.provider, model: data.model || "",
        baseUrl: data.baseUrl || "", timeoutSeconds: data.timeoutSeconds || 180,
      });
    } catch {
      setSettings(null);
    }
  }, []);

  useEffect(() => { if (open && !settings) loadSettings(); }, [open, settings, loadSettings]);

  /** Gọi API AI: gom mọi lỗi về một chỗ để mọi bước báo lỗi giống nhau. */
  const call = async <T,>(path: string, body: unknown, label: string): Promise<T | null> => {
    setBusy(label); setError(null); setInfo(null);
    try {
      const res = await fetch(`${API_BASE}${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "AI trả về lỗi");
      return data as T;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không gọi được AI");
      return null;
    } finally {
      setBusy(null);
    }
  };

  // ── Cấu hình ───────────────────────────────────────────────────
  const saveSettings = async () => {
    const body: Record<string, unknown> = { ...settingsDraft };
    if (apiKeyDraft.trim()) body.apiKey = apiKeyDraft.trim();
    const data = await call<AiSettings>("/ai/settings", body, "settings");
    if (data) {
      setSettings(data);
      setApiKeyDraft("");
      setInfo("Đã lưu cấu hình AI.");
    }
  };

  const testConnection = async () => {
    const data = await call<{ ok: boolean; message: string; elapsedMs: number }>(
      "/ai/settings/test", {}, "test");
    if (data) {
      if (data.ok) setInfo(`Kết nối thành công (${data.elapsedMs} ms).`);
      else setError(data.message || "Không kết nối được.");
    }
  };

  // ── Bước 1 & 2 ─────────────────────────────────────────────────
  const draftExam = async () => {
    if (!req.topic.trim()) { setError("Hãy nhập chủ đề / bài toán của đề."); return; }
    const data = await call<{ de_bai: string; summary: string; criteria: { name: string; points: number }[] }>(
      "/ai/exam/draft", req, "draft");
    if (data) {
      setDeBai(data.de_bai);
      setSummary(data.summary);
      setCriteria(data.criteria || []);
      setExamAccepted(false);
      setKeys([]); setScreens([]); setProposed([]);
    }
  };

  const reviseExam = async () => {
    if (!revisePrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì."); return; }
    const data = await call<{ de_bai: string; summary: string; criteria: { name: string; points: number }[] }>(
      "/ai/exam/revise", { de_bai: deBai, instruction: revisePrompt }, "revise");
    if (data) {
      setDeBai(data.de_bai);
      setSummary(data.summary);
      setCriteria(data.criteria || []);
      setRevisePrompt("");
      setInfo("AI đã sửa đề. Kiểm tra lại phần thay đổi trước khi chấp nhận.");
    }
  };

  // ── Bước 3 ─────────────────────────────────────────────────────
  const analyzeKeys = async () => {
    const data = await call<{
      contract: { require_keys: boolean; keys: AiContractKey[] };
      mockup_spec: unknown; screens: MockupScreen[]; notes: string[]; unused_keys: string[];
    }>("/ai/keys/analyze", { de_bai: deBai }, "keys");
    if (data) {
      setKeys(data.contract?.keys || []);
      setRequireKeys(data.contract?.require_keys ?? true);
      setMockupSpec(data.mockup_spec);
      setScreens(data.screens || []);
      setKeyNotes([...(data.notes || []),
        ...(data.unused_keys?.length ? [`Key chưa xuất hiện trên hình: ${data.unused_keys.join(", ")}`] : [])]);
      setKeysAccepted(false);
    }
  };

  const redrawMockup = async () => {
    const data = await call<{ screens: MockupScreen[] }>("/ai/keys/mockup", { mockup_spec: mockupSpec }, "mockup");
    if (data) { setScreens(data.screens || []); setInfo("Đã vẽ lại hình từ bản mô tả hiện tại."); }
  };

  const updateKey = (i: number, patch: Partial<AiContractKey>) =>
    setKeys((cur) => cur.map((k, idx) => (idx === i ? { ...k, ...patch } : k)));
  const removeKey = (i: number) => setKeys((cur) => cur.filter((_, idx) => idx !== i));
  const addKey = () =>
    setKeys((cur) => [...cur, { key: "", label: "", strategy: "key_only", value: "", index: 0 }]);

  const acceptKeys = () => {
    const clean = keys.filter((k) => k.key.trim());
    if (!clean.length) { setError("Chưa có Item Key nào để chấp nhận."); return; }
    onApplyContract(clean, requireKeys);
    setKeysAccepted(true);
    setInfo(`Đã đưa ${clean.length} Item Key vào Khu vực 0 — Cấu hình bài làm.`);
  };

  /** Chèn hình + bảng key vào cuối đề bài để sinh viên biết phải gắn key nào ở đâu. */
  const insertMockupIntoExam = () => {
    if (!screens.length) return;
    const block = [
      "",
      "## 6. HÌNH MINH HỌA GIAO DIỆN & ITEM KEY",
      "",
      "Mỗi thành phần có mũi tên đỏ trong hình phải được gắn `ValueKey` đúng như chú thích,",
      "nếu không bộ chấm tự động sẽ không tìm thấy thành phần đó.",
      "",
      ...screens.flatMap((s) => [`### ${s.title}`, "", `![${s.title}](mockup/${s.id}.svg)`, ""]),
      "| Item Key | Thành phần |",
      "| --- | --- |",
      ...keys.filter((k) => k.key.trim()).map((k) => `| \`${k.key}\` | ${k.label || ""} |`),
      "",
    ].join("\n");
    setDeBai((cur) => (cur.includes("## 6. HÌNH MINH HỌA") ? cur : cur.trimEnd() + "\n" + block));
    setInfo("Đã chèn hình và bảng Item Key vào đề. Bạn vẫn sửa lại được nội dung ở trên.");
  };

  // ── Bước 4 ─────────────────────────────────────────────────────
  const proposeTestcases = async () => {
    const data = await call<{
      items: AiProposedItem[]; rejected: { template_id: string; reason: string }[];
      notes: string[]; missing_keys: string[]; total_weight: number;
    }>("/ai/testcases/propose", { de_bai: deBai, contract: { keys } }, "testcases");
    if (data) {
      setProposed(data.items || []);
      setRejected(data.rejected || []);
      setTcNotes(data.notes || []);
      setMissingKeys(data.missing_keys || []);
    }
  };

  const acceptItems = () => {
    const enabled = proposed.filter((i) => i.enabled);
    if (!enabled.length) { setError("Chưa chọn testcase nào để thêm."); return; }
    onApplyItems(enabled);
    setInfo(`Đã thêm ${enabled.length} testcase vào Khu vực 3 — bạn vẫn sửa tham số được ở đó.`);
  };

  // ── Bước 5: khung starter ──────────────────────────────────────
  const proposeStarter = async () => {
    const data = await call<{
      files: StarterFile[]; warnings: string[]; notes: string[];
      syntax_ok: boolean | null; syntax_message: string;
    }>("/ai/starter/propose", { de_bai: deBai, contract: { keys } }, "starter");
    if (data) {
      setStarterFiles(data.files || []);
      setStarterWarnings(data.warnings || []);
      setStarterNotes(data.notes || []);
      setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
      setOpenFile(data.files?.[0]?.path ?? null);
    }
  };

  const recheckStarter = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/starter/check", { files: starterFiles }, "starter-check");
    if (data) setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };

  const saveStarter = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu khung starter."); return; }
    const data = await call<{ files: string[] }>(
      `/exam-setup/${encodeURIComponent(examId.trim())}/starter`,
      { files: starterFiles.map((f) => ({ name: f.path, content: f.content })) },
      "starter-save");
    if (data) setInfo(`Đã lưu khung starter (${data.files.length} file). Tải về ở trang Kho đề → Starter.`);
  };

  // ── Lưu bộ phát cho SV ─────────────────────────────────────────
  const saveHandout = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu đề bài."); return; }
    const data = await call<{ files: string[] }>(
      `/exam-setup/${encodeURIComponent(examId.trim())}/handout`,
      { de_bai: deBai, mockups: screens.map((s) => ({ id: s.id, svg: s.svg })) },
      "handout");
    if (data) setInfo(`Đã lưu vào bộ phát cho sinh viên: ${data.files.join(", ")}.`);
  };

  const totalWeight = useMemo(
    () => proposed.filter((i) => i.enabled).reduce((s, i) => s + Number(i.weight || 0), 0),
    [proposed]);
  const newKeyCount = useMemo(
    () => keys.filter((k) => k.key.trim() && !existingKeys.includes(k.key.trim())).length,
    [keys, existingKeys]);

  return (
    <section className="card overflow-hidden">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-violet-50 via-indigo-50 to-sky-50 px-6 py-4 text-left"
      >
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 text-white shadow-sm">
          <Sparkles size={18} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="eyebrow">Trợ lý</p>
          <h2 className="text-sm font-bold text-slate-800">Soạn đề &amp; testcase bằng AI</h2>
        </div>
        {settings && (
          <span className={`hidden shrink-0 rounded-full px-2.5 py-1 text-[11px] font-bold sm:inline ${
            settings.ready ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>
            {settings.ready ? `${settings.provider} · ${settings.model}` : "Chưa có API key"}
          </span>
        )}
        <ChevronDown size={18} className={`shrink-0 text-slate-400 transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {open && (
        <div className="space-y-6 p-6">
          {/* Thông báo chung */}
          {error && (
            <p className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs font-medium leading-relaxed text-rose-700">
              <AlertTriangle size={14} className="mt-0.5 shrink-0" /> {error}
            </p>
          )}
          {info && (
            <p className="flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs font-medium leading-relaxed text-emerald-700">
              <Check size={14} className="mt-0.5 shrink-0" /> {info}
            </p>
          )}

          {/* ── Cấu hình LLM ── */}
          <div className="rounded-2xl border border-slate-200">
            <button
              onClick={() => setSettingsOpen((v) => !v)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left"
            >
              <Settings2 size={15} className="text-slate-500" />
              <span className="text-sm font-bold text-slate-700">Cấu hình LLM (API key)</span>
              {settings?.hasApiKey && (
                <span className="ml-2 font-mono text-[11px] text-slate-400">{settings.apiKeyMasked}</span>
              )}
              <ChevronDown size={16} className={`ml-auto text-slate-400 transition-transform ${settingsOpen ? "rotate-180" : ""}`} />
            </button>
            {settingsOpen && (
              <div className="space-y-3 border-t border-slate-100 p-4">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <Field label="Nhà cung cấp">
                    <select
                      value={settingsDraft.provider}
                      onChange={(e) => setSettingsDraft((d) => ({ ...d, provider: e.target.value, model: "" }))}
                      className={inputClass}
                    >
                      {(settings?.providers || ["gemini", "openai"]).map((p) => (
                        <option key={p} value={p}>{p === "gemini" ? "Google Gemini" : "OpenAI / tương thích OpenAI"}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Model">
                    <input
                      value={settingsDraft.model}
                      onChange={(e) => setSettingsDraft((d) => ({ ...d, model: e.target.value }))}
                      placeholder={settings?.defaultModels?.[settingsDraft.provider] || ""}
                      className={inputClass}
                    />
                  </Field>
                  <Field
                    label="API key"
                    hint={settingsDraft.provider !== settings?.provider
                      ? "Đổi nhà cung cấp thì phải nhập key mới của nhà cung cấp đó"
                      : settings?.hasApiKey ? "Để trống = giữ key đang dùng" : "Bắt buộc để dùng trợ lý"}
                  >
                    <div className="flex items-center gap-2">
                      <KeyRound size={14} className="shrink-0 text-slate-400" />
                      <input
                        type="password"
                        value={apiKeyDraft}
                        onChange={(e) => setApiKeyDraft(e.target.value)}
                        placeholder={settings?.hasApiKey ? settings.apiKeyMasked || "••••" : "Dán API key vào đây"}
                        className={inputClass}
                        autoComplete="off"
                      />
                    </div>
                  </Field>
                  {settingsDraft.provider === "openai" && (
                    <Field label="Endpoint" hint="Đổi để dùng Ollama / OpenRouter / Azure">
                      <input
                        value={settingsDraft.baseUrl}
                        onChange={(e) => setSettingsDraft((d) => ({ ...d, baseUrl: e.target.value }))}
                        placeholder="https://api.openai.com/v1"
                        className={inputClass}
                      />
                    </Field>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <button onClick={saveSettings} disabled={busy !== null} className={primaryBtn}>
                    {busy === "settings" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Lưu cấu hình
                  </button>
                  <button onClick={testConnection} disabled={busy !== null || !settings?.hasApiKey} className={ghostBtn}>
                    {busy === "test" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />} Kiểm tra kết nối
                  </button>
                  <span className="text-[11px] text-slate-400">Key chỉ lưu trên máy này (cơ sở dữ liệu cục bộ).</span>
                </div>
              </div>
            )}
          </div>

          {/* ── Bước 1: yêu cầu ── */}
          <Step index={1} icon={Wand2} title="Mô tả yêu cầu đề" done={!!deBai}>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Chủ đề / bài toán *">
                <input value={req.topic} onChange={(e) => setReq({ ...req, topic: e.target.value })}
                  placeholder="VD: Quản lý sinh viên (thêm/sửa/xóa)" className={inputClass} />
              </Field>
              <Field label="Kiến thức cần kiểm tra">
                <input value={req.knowledge} onChange={(e) => setReq({ ...req, knowledge: e.target.value })}
                  placeholder="MVVM, Riverpod, SQLite, validate form, responsive" className={inputClass} />
              </Field>
              <Field label="Các màn hình">
                <input value={req.screens} onChange={(e) => setReq({ ...req, screens: e.target.value })}
                  placeholder="Danh sách + Chi tiết" className={inputClass} />
              </Field>
              <Field label="Thực thể / dữ liệu">
                <input value={req.entity} onChange={(e) => setReq({ ...req, entity: e.target.value })}
                  placeholder="Student: id, fullName, email, avatar" className={inputClass} />
              </Field>
              <Field label="Chức năng bắt buộc">
                <input value={req.features} onChange={(e) => setReq({ ...req, features: e.target.value })}
                  placeholder="Thêm, sửa, xóa có xác nhận, điều hướng sang chi tiết" className={inputClass} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Độ khó">
                  <select value={req.difficulty} onChange={(e) => setReq({ ...req, difficulty: e.target.value })} className={inputClass}>
                    <option>Dễ</option><option>Trung bình</option><option>Khó</option>
                  </select>
                </Field>
                <Field label="Thời lượng">
                  <input value={req.duration} onChange={(e) => setReq({ ...req, duration: e.target.value })} className={inputClass} />
                </Field>
              </div>
              <div className="sm:col-span-2">
                <Field label="Yêu cầu thêm">
                  <textarea value={req.note} onChange={(e) => setReq({ ...req, note: e.target.value })} rows={2}
                    placeholder="VD: bắt buộc dùng riverpod generator; danh sách hiển thị dạng grid trên tablet"
                    className={inputClass} />
                </Field>
              </div>
            </div>
            <button onClick={draftExam} disabled={busy !== null} className={`${primaryBtn} mt-3`}>
              {busy === "draft" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />} Sinh đề bài
            </button>
          </Step>

          {/* ── Bước 2: đề bài ── */}
          {!!deBai && (
            <Step index={2} icon={FileText} title="Xem lại &amp; sửa đề bài" done={examAccepted}>
              {summary && <p className="mb-2 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600">{summary}</p>}
              {criteria.length > 0 && (
                <p className="mb-2 text-[11px] text-slate-500">
                  Thang điểm AI đề xuất: {criteria.map((c) => `${c.name} (${c.points})`).join(" · ")}
                  {" · Tổng "}<strong>{criteria.reduce((s, c) => s + Number(c.points || 0), 0)}</strong>
                </p>
              )}
              <textarea
                value={deBai}
                onChange={(e) => { setDeBai(e.target.value); setExamAccepted(false); }}
                rows={16}
                className="custom-scrollbar w-full rounded-xl border border-slate-200 bg-white p-3 font-mono text-xs leading-relaxed text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
              />
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <input
                  value={revisePrompt}
                  onChange={(e) => setRevisePrompt(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseExam(); } }}
                  placeholder="Nhắc AI sửa: thêm màn hình chi tiết, bỏ yêu cầu SQLite, chia lại điểm…"
                  className={`${inputClass} min-w-[240px] flex-1`}
                />
                <button onClick={reviseExam} disabled={busy !== null} className={ghostBtn}>
                  {busy === "revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Nhờ AI sửa
                </button>
                <button
                  onClick={() => { setExamAccepted(true); setInfo("Đã chốt đề bài. Sang bước phân tích Item Key."); }}
                  className={primaryBtn}
                >
                  <Check size={15} /> Chấp nhận đề bài
                </button>
              </div>
            </Step>
          )}

          {/* ── Bước 3: Item Key + hình ── */}
          {examAccepted && (
            <Step index={3} icon={ImageIcon} title="Item Key &amp; hình minh họa giao diện" done={keysAccepted}>
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <button onClick={analyzeKeys} disabled={busy !== null} className={primaryBtn}>
                  {busy === "keys" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {keys.length ? "Phân tích lại" : "Phân tích đề → Item Key"}
                </button>
                {screens.length > 0 && (
                  <>
                    <button onClick={redrawMockup} disabled={busy !== null} className={ghostBtn}>
                      {busy === "mockup" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />} Vẽ lại hình
                    </button>
                    <button onClick={insertMockupIntoExam} className={ghostBtn}>
                      <Plus size={15} /> Chèn hình + bảng key vào đề
                    </button>
                  </>
                )}
              </div>

              {keyNotes.length > 0 && (
                <ul className="mb-3 space-y-1">
                  {keyNotes.map((n, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-[11px] leading-relaxed text-amber-800">
                      <Info size={12} className="mt-0.5 shrink-0" /> {n}
                    </li>
                  ))}
                </ul>
              )}

              {keys.length > 0 && (
                <div className="overflow-x-auto rounded-xl border border-slate-200">
                  <table className="w-full min-w-[680px] text-left text-xs">
                    <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-wider text-slate-500">
                      <tr>
                        <th className="px-3 py-2">Item Key</th>
                        <th className="px-3 py-2">Thành phần</th>
                        <th className="px-3 py-2">Cách nhận diện</th>
                        <th className="px-3 py-2">Giá trị</th>
                        <th className="px-3 py-2 w-10"></th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {keys.map((k, i) => (
                        <tr key={i} className="align-top">
                          <td className="px-3 py-2">
                            <input value={k.key} onChange={(e) => updateKey(i, { key: e.target.value })}
                              className={`${inputClass} font-mono`} />
                            {k.evidence && <p className="mt-1 text-[10px] italic text-slate-400">“{k.evidence}”</p>}
                          </td>
                          <td className="px-3 py-2">
                            <input value={k.label} onChange={(e) => updateKey(i, { label: e.target.value })} className={inputClass} />
                          </td>
                          <td className="px-3 py-2">
                            <select value={k.strategy} onChange={(e) => updateKey(i, { strategy: e.target.value })} className={inputClass}>
                              {STRATEGIES.map((s) => <option key={s} value={s}>{s}</option>)}
                            </select>
                          </td>
                          <td className="px-3 py-2">
                            <input value={k.value || ""} onChange={(e) => updateKey(i, { value: e.target.value })}
                              placeholder={k.strategy === "key_only" ? "—" : "bắt buộc"} className={inputClass} />
                          </td>
                          <td className="px-3 py-2">
                            <button onClick={() => removeKey(i)} className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600">
                              <Trash2 size={14} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {keys.length > 0 && (
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <button onClick={addKey} className={ghostBtn}><Plus size={15} /> Thêm key</button>
                  <label className="flex items-center gap-2 text-xs font-medium text-slate-600">
                    <input type="checkbox" checked={requireKeys} onChange={(e) => setRequireKeys(e.target.checked)} />
                    Bắt buộc sinh viên gắn đúng key (require_keys)
                  </label>
                  <button onClick={acceptKeys} className={`${primaryBtn} ml-auto`}>
                    <Check size={15} /> Chấp nhận Item Key ({newKeyCount} key mới)
                  </button>
                </div>
              )}

              {screens.map((s) => (
                <figure key={s.id} className="mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white p-3">
                  <figcaption className="mb-2 text-xs font-bold text-slate-600">{s.title}</figcaption>
                  <div className="min-w-[720px]" dangerouslySetInnerHTML={{ __html: s.svg }} />
                </figure>
              ))}
            </Step>
          )}

          {/* ── Bước 4: testcase ── */}
          {keysAccepted && (
            <Step index={4} icon={ListChecks} title="Bộ testcase đề xuất" done={false}>
              <button onClick={proposeTestcases} disabled={busy !== null} className={primaryBtn}>
                {busy === "testcases" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                {proposed.length ? "Đề xuất lại" : "Đề xuất bộ testcase"}
              </button>

              {missingKeys.length > 0 && (
                <p className="mt-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
                  Testcase đang dùng key chưa khai ở Khu vực 0: <strong>{missingKeys.join(", ")}</strong>.
                  Hãy thêm vào bước 3 rồi chấp nhận lại, nếu không sẽ không lưu được bộ testcase.
                </p>
              )}
              {rejected.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {rejected.map((r, i) => (
                    <li key={i} className="rounded-lg bg-rose-50 p-2 text-[11px] text-rose-700">
                      Bỏ qua <span className="font-mono">{r.template_id || "(không rõ)"}</span>: {r.reason}
                    </li>
                  ))}
                </ul>
              )}
              {tcNotes.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {tcNotes.map((n, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-lg bg-slate-50 p-2 text-[11px] leading-relaxed text-slate-600">
                      <Info size={12} className="mt-0.5 shrink-0" /> {n}
                    </li>
                  ))}
                </ul>
              )}

              {proposed.length > 0 && (
                <>
                  <div className="mt-3 space-y-2">
                    {proposed.map((item, i) => (
                      <article key={item.instance_id}
                        className={`rounded-xl border p-3 ${item.enabled ? "border-slate-200 bg-white" : "border-slate-100 bg-slate-50 opacity-60"}`}>
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2">
                              <input type="checkbox" checked={item.enabled}
                                onChange={(e) => setProposed((cur) => cur.map((x, idx) => idx === i ? { ...x, enabled: e.target.checked } : x))} />
                              <h4 className="text-sm font-bold text-slate-800">{item.name}</h4>
                              <span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[10px] text-indigo-700">{item.runner}</span>
                              <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-500">{item.skill_code}</span>
                            </div>
                            {item.criterion && <p className="mt-1 text-[11px] text-slate-500">Tiêu chí: {item.criterion}</p>}
                            {item.reason && <p className="mt-0.5 text-[11px] italic text-slate-400">{item.reason}</p>}
                            <p className="mt-1 font-mono text-[10px] leading-relaxed text-slate-500">
                              {Object.entries(item.parameters || {}).map(([k, v]) => `${k}=${String(v)}`).join(" · ") || "(không có tham số)"}
                            </p>
                          </div>
                          <div className="flex shrink-0 items-center gap-2">
                            <input type="number" min={0} step={0.5} value={item.weight}
                              onChange={(e) => setProposed((cur) => cur.map((x, idx) => idx === i ? { ...x, weight: Number(e.target.value) } : x))}
                              className="w-20 rounded-lg border border-slate-200 px-2 py-1 text-right text-xs font-bold text-slate-700" />
                            <span className="text-[11px] text-slate-400">điểm</span>
                            <button onClick={() => setProposed((cur) => cur.filter((_, idx) => idx !== i))}
                              className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600">
                              <X size={14} />
                            </button>
                          </div>
                        </div>
                      </article>
                    ))}
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-3">
                    <span className="text-xs text-slate-500">
                      {proposed.filter((i) => i.enabled).length} testcase · tổng điểm <strong>{Math.round(totalWeight * 100) / 100}</strong>
                    </span>
                    <button onClick={acceptItems} className={`${primaryBtn} ml-auto`}>
                      <Check size={15} /> Chấp nhận &amp; thêm vào bộ testcase
                    </button>
                  </div>
                </>
              )}
            </Step>
          )}

          {/* ── Bước 5: khung starter ── */}
          {keysAccepted && (
            <Step index={5} icon={FileCode2} title="Khung starter phát cho sinh viên" done={false}>
              <p className="mb-3 rounded-xl bg-slate-50 p-3 text-[11px] leading-relaxed text-slate-600">
                Khung chỉ gồm <strong>class, thuộc tính, chữ ký hàm và hằng số Item Key</strong>.
                Thân hàm luôn là <span className="font-mono">TODO</span> và giao diện luôn là
                <span className="font-mono"> Placeholder()</span> — phần UI và logic là bài thi của sinh viên,
                hệ thống không cho AI viết sẵn.
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <button onClick={proposeStarter} disabled={busy !== null} className={primaryBtn}>
                  {busy === "starter" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                  {starterFiles.length ? "Sinh lại khung" : "Sinh khung starter"}
                </button>
                {starterFiles.length > 0 && (
                  <>
                    <button onClick={recheckStarter} disabled={busy !== null} className={ghostBtn}>
                      {busy === "starter-check" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
                      Kiểm tra cú pháp
                    </button>
                    <button onClick={saveStarter} disabled={busy !== null || !examId.trim()} className={ghostBtn}>
                      {busy === "starter-save" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
                      Lưu khung cho SV
                    </button>
                  </>
                )}
              </div>

              {syntax && (
                <p className={`mt-3 flex items-start gap-2 rounded-xl border p-2.5 text-[11px] leading-relaxed ${
                  syntax.ok === true ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                    : syntax.ok === false ? "border-rose-200 bg-rose-50 text-rose-700"
                      : "border-amber-200 bg-amber-50 text-amber-800"}`}>
                  {syntax.ok === true ? <Check size={13} className="mt-0.5 shrink-0" />
                    : <AlertTriangle size={13} className="mt-0.5 shrink-0" />}
                  {syntax.message}
                </p>
              )}

              {starterWarnings.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {starterWarnings.map((w, i) => (
                    <li key={i} className="rounded-lg bg-amber-50 p-2 text-[11px] leading-relaxed text-amber-800">{w}</li>
                  ))}
                </ul>
              )}
              {starterNotes.length > 0 && (
                <ul className="mt-3 space-y-1">
                  {starterNotes.map((n, i) => (
                    <li key={i} className="flex items-start gap-2 rounded-lg bg-slate-50 p-2 text-[11px] leading-relaxed text-slate-600">
                      <Info size={12} className="mt-0.5 shrink-0" /> {n}
                    </li>
                  ))}
                </ul>
              )}

              {starterFiles.length > 0 && (
                <div className="mt-3 space-y-2">
                  {starterFiles.map((f) => (
                    <div key={f.path} className="overflow-hidden rounded-xl border border-slate-200">
                      <button
                        onClick={() => setOpenFile(openFile === f.path ? null : f.path)}
                        className="flex w-full items-center gap-2 bg-slate-50 px-3 py-2 text-left"
                      >
                        <FileCode2 size={13} className="shrink-0 text-indigo-500" />
                        <span className="font-mono text-xs font-semibold text-slate-700">{f.path}</span>
                        <span className="truncate text-[11px] text-slate-400">{f.summary}</span>
                        <ChevronDown size={14} className={`ml-auto shrink-0 text-slate-400 transition-transform ${openFile === f.path ? "rotate-180" : ""}`} />
                      </button>
                      {openFile === f.path && (
                        <textarea
                          value={f.content}
                          onChange={(e) => {
                            const content = e.target.value;
                            setStarterFiles((cur) => cur.map((x) => x.path === f.path ? { ...x, content } : x));
                            setSyntax(null);   // sửa tay xong thì kết quả kiểm cú pháp cũ không còn đúng
                          }}
                          rows={Math.min(24, f.content.split("\n").length + 2)}
                          spellCheck={false}
                          className="custom-scrollbar w-full border-t border-slate-200 bg-slate-900 p-3 font-mono text-[11px] leading-relaxed text-slate-100 outline-none"
                        />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </Step>
          )}

          {/* Lưu bộ phát cho SV */}
          {!!deBai && (
            <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-4">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-slate-700">Bộ phát cho sinh viên</p>
                <p className="text-[11px] leading-relaxed text-slate-500">
                  Lưu đề bài + hình minh họa vào bộ testcase <span className="font-mono">{examId.trim() || "(chưa có mã)"}</span>;
                  tải lại ở trang Kho đề bằng nút “Đề bài”.
                </p>
              </div>
              <button onClick={saveHandout} disabled={busy !== null || !examId.trim()} className={primaryBtn}>
                {busy === "handout" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Lưu đề bài + hình
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

const inputClass =
  "w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";
const primaryBtn =
  "flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-sm transition-all hover:from-violet-700 hover:to-indigo-700 active:scale-[0.98] disabled:cursor-not-allowed disabled:from-slate-300 disabled:to-slate-300";
const ghostBtn =
  "flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50";

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-bold uppercase tracking-wider text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[10px] text-slate-400">{hint}</span>}
    </label>
  );
}

function Step({ index, icon: Icon, title, done, children }: {
  index: number; icon: React.ComponentType<{ size?: number; className?: string }>;
  title: string; done: boolean; children: React.ReactNode;
}) {
  return (
    <div className="rounded-2xl border border-slate-200 p-4">
      <div className="mb-3 flex items-center gap-2">
        <span className={`flex h-6 w-6 items-center justify-center rounded-full text-[11px] font-bold ${
          done ? "bg-emerald-100 text-emerald-700" : "bg-indigo-100 text-indigo-700"}`}>
          {done ? <Check size={13} /> : index}
        </span>
        <Icon size={15} className="text-indigo-500" />
        <h3 className="text-sm font-bold text-slate-800">{title}</h3>
      </div>
      {children}
    </div>
  );
}
