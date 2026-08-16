"use client";

// Trợ lý AI soạn đề trong trang "Tạo bộ testcase".
//
// Triết lý: AI chỉ ĐỀ XUẤT, giáo viên quyết định. Mỗi bước đều có chỗ sửa tay + ô nhắc AI sửa
// lại + nút chấp nhận riêng; không bước nào tự ghi vào bộ testcase đang chấm.
//
// Backend: /api/ai/* (xem AiAuthorController). Testcase do AI đề xuất luôn là template có sẵn
// trong thư viện nên vẫn đi qua đúng bộ kiểm tra tham số khi lưu.

import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { API_BASE } from "@/lib/config";
import {
  Sparkles, Settings2, KeyRound, Wand2, FileText, Image as ImageIcon, ListChecks,
  Loader2, Check, X, Plus, Trash2, RefreshCw, AlertTriangle, ChevronDown, Save, Info, FileCode2,
  Upload,
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
interface AiModel { id: string; label: string; provider: string; vendor: string }
interface AiSettings {
  model: string; provider: string; vendor: string; keyUrl: string | null;
  hasApiKey: boolean; apiKeyMasked: string | null; keyWarning: string | null;
  baseUrl: string; customBaseUrl: boolean;
  timeoutSeconds: number; models: AiModel[]; ready: boolean;
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
  const [modelDraft, setModelDraft] = useState("");
  const [baseUrlDraft, setBaseUrlDraft] = useState("");    // dịch vụ trung gian / cổng nội bộ
  const [customModel, setCustomModel] = useState(false);   // gõ tay mã model không có trong danh sách
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // Bước 1 — hai nhánh: nhờ AI soạn đề mới, hay tải đề có sẵn lên rồi phân tích
  const [source, setSource] = useState<"ai" | "upload">("ai");
  const [importedName, setImportedName] = useState("");
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
      setModelDraft(data.model || "");
      setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
      setCustomModel(!(data.models || []).some((m) => m.id === data.model));
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
  /** Ghi cấu hình đang gõ xuống server; trả về bản đã lưu để nơi gọi dùng tiếp. */
  const persistSettings = async (label: string) => {
    const body: Record<string, unknown> = {
      model: modelDraft.trim(),
      baseUrl: baseUrlDraft.trim(),   // rỗng = quay lại endpoint chính thức của hãng
    };
    if (apiKeyDraft.trim()) body.apiKey = apiKeyDraft.trim();
    const data = await call<AiSettings>("/ai/settings", body, label);
    if (data) {
      setSettings(data);
      setApiKeyDraft("");
      setModelDraft(data.model);
      setBaseUrlDraft(data.customBaseUrl ? data.baseUrl || "" : "");
    }
    return data;
  };

  const saveSettings = async () => {
    const data = await persistSettings("settings");
    if (data) {
      setInfo(data.hasApiKey
        ? `Đã lưu. Đang dùng ${data.model} (${data.vendor}).`
        : `Đã chọn ${data.model}. Hãy dán API key của ${data.vendor} rồi lưu lại.`);
    }
  };

  const testConnection = async () => {
    // Phép thử luôn chạy trên cấu hình ĐÃ LƯU. Chưa lưu mà bấm thử thì hoá ra đang kiểm tra key
    // cũ của hãng cũ — lỗi 401 trả về sẽ nói về hãng mà người dùng tưởng mình đã đổi khỏi.
    let current = settings;
    if (settingsDirty) {
      current = await persistSettings("test");
      if (!current) return;
    }
    if (!current?.hasApiKey) {
      setError(`Chưa có API key cho ${current?.vendor || "model đang chọn"}. Hãy dán key rồi thử lại.`);
      return;
    }
    const data = await call<{ ok: boolean; message: string; elapsedMs: number }>(
      "/ai/settings/test", {}, "test");
    if (data) {
      if (data.ok) setInfo(`Kết nối thành công tới ${current.model} (${data.elapsedMs} ms).`);
      else setError(data.message || "Không kết nối được.");
    }
  };

  /**
   * Nhánh "đã có đề sẵn": tải file lên, backend bóc chữ (không tốn lượt AI nào), rồi đi thẳng
   * sang bước xem lại đề → phân tích Item Key. Bỏ hẳn bước nhờ AI soạn đề.
   */
  const importExam = async (file: File) => {
    setBusy("import");
    setError(null);
    setInfo(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const res = await fetch(`${API_BASE}/ai/exam/import`, { method: "POST", body: form });
      const data = await res.json();
      if (!res.ok) throw new Error(data?.error || "Không đọc được file đề.");
      setDeBai(String(data.de_bai || ""));
      setSummary("");
      setCriteria([]);
      setExamAccepted(false);
      setKeys([]); setScreens([]); setProposed([]);
      setImportedName(String(data.file_name || file.name));
      const warnings: string[] = Array.isArray(data.warnings) ? data.warnings : [];
      setInfo(`Đã đọc ${file.name} (${String(data.de_bai || "").length} ký tự).`
        + (warnings.length ? ` ${warnings.join(" ")}` : " Hãy xem lại đề rồi bấm chấp nhận."));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không đọc được file đề.");
    } finally {
      setBusy(null);
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

  // Gom model theo hãng để ô chọn có nhóm Claude / GPT / Gemini rõ ràng.
  const modelsByVendor = useMemo(() => {
    const out: Record<string, AiModel[]> = {};
    (settings?.models || []).forEach((m) => {
      (out[m.vendor] ||= []).push(m);
    });
    return out;
  }, [settings]);

  /** Hãng của model ĐANG CHỌN trong form (chưa lưu) — suy từ mã model, giống hệt backend. */
  const draftVendor = useMemo(() => {
    if (baseUrlDraft.trim()) return "Dịch vụ trung gian (giao thức OpenAI)";
    const id = modelDraft.trim().toLowerCase();
    if (!id) return "";
    if (id.startsWith("claude")) return "Claude (Anthropic)";
    if (id.startsWith("gemini")) return "Gemini (Google)";
    return "GPT (OpenAI)";
  }, [modelDraft, baseUrlDraft]);

  // Đổi hãng thì key cũ chắc chắn vô dụng → báo trước thay vì để dính lỗi 401 lúc sinh đề.
  const vendorChanged = !!settings && !!draftVendor && draftVendor !== settings.vendor;

  /** Còn thứ chưa lưu: model vừa đổi, endpoint vừa sửa, hoặc key vừa dán. */
  const settingsDirty = !!settings
    && (modelDraft.trim() !== settings.model
      || apiKeyDraft.trim().length > 0
      || baseUrlDraft.trim() !== (settings.customBaseUrl ? settings.baseUrl : ""));

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
            {settings.ready ? settings.model : "Chưa có API key"}
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
              <span className="text-sm font-bold text-slate-700">Chọn model &amp; API key</span>
              {settings?.hasApiKey && (
                <span className="ml-2 font-mono text-[11px] text-slate-400">{settings.apiKeyMasked}</span>
              )}
              <ChevronDown size={16} className={`ml-auto text-slate-400 transition-transform ${settingsOpen ? "rotate-180" : ""}`} />
            </button>
            {settingsOpen && (
              <div className="space-y-3 border-t border-slate-100 p-4">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {/* Chỉ cần chọn model: nhà cung cấp và endpoint suy ra từ chính mã model. */}
                  <Field label="Model AI">
                    <select
                      value={customModel ? "__custom__" : modelDraft}
                      onChange={(e) => {
                        if (e.target.value === "__custom__") { setCustomModel(true); return; }
                        setCustomModel(false);
                        setModelDraft(e.target.value);
                      }}
                      className={inputClass}
                    >
                      {Object.entries(modelsByVendor).map(([vendor, list]) => (
                        <optgroup key={vendor} label={vendor}>
                          {list.map((m) => (
                            <option key={m.id} value={m.id}>{m.label}</option>
                          ))}
                        </optgroup>
                      ))}
                      <option value="__custom__">Tự nhập mã model khác…</option>
                    </select>
                  </Field>
                  <Field
                    label="API key"
                    hint={vendorChanged
                      ? `Đổi sang ${draftVendor} thì phải nhập key mới của hãng đó`
                      : undefined}
                  >
                    <div className="flex items-center gap-2">
                      <KeyRound size={14} className="shrink-0 text-slate-400" />
                      {/* KHÔNG dùng type="password": trình duyệt tự điền mật khẩu đã lưu vào ô này,
                          người dùng dán key nối phía sau → lưu thành "<mật khẩu>sk-ant-…" và hãng
                          chỉ trả về "invalid x-api-key". Che bằng CSS, và dán vào thì cắt sạch
                          khoảng trắng (key copy từ web hay dính \n ở cuối). */}
                      <input
                        type="text"
                        name="grader-ai-key"
                        value={apiKeyDraft}
                        onChange={(e) => setApiKeyDraft(e.target.value.replace(/\s+/g, ""))}
                        placeholder={settings?.hasApiKey && !vendorChanged
                          ? settings.apiKeyMasked || "••••" : "Dán API key vào đây"}
                        className={inputClass}
                        style={{ WebkitTextSecurity: "disc" } as CSSProperties}
                        autoComplete="off"
                        spellCheck={false}
                        data-lpignore="true"
                        data-1p-ignore
                        data-form-type="other"
                      />
                    </div>
                  </Field>
                  {customModel && (
                    <div className="sm:col-span-2">
                      <Field label="Mã model" hint="Bắt đầu bằng claude… / gpt… / gemini… để gọi đúng hãng">
                        <input
                          value={modelDraft}
                          onChange={(e) => setModelDraft(e.target.value)}
                          placeholder="VD: claude-sonnet-5"
                          className={`${inputClass} font-mono`}
                        />
                      </Field>
                    </div>
                  )}
                  <div className="sm:col-span-2">
                    <Field label="Endpoint riêng (tùy chọn)">
                      <input
                        value={baseUrlDraft}
                        onChange={(e) => setBaseUrlDraft(e.target.value)}
                        placeholder="https://api.dich-vu-cua-ban.com/v1"
                        className={`${inputClass} font-mono`}
                      />
                    </Field>
                  </div>
                </div>
                {settings?.keyWarning && (
                  <p className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-2.5 text-[11px] leading-relaxed text-amber-800">
                    <AlertTriangle size={13} className="mt-0.5 shrink-0" /> {settings.keyWarning}
                  </p>
                )}
                <div className="flex flex-wrap items-center gap-2">
                  <button onClick={saveSettings} disabled={busy !== null || !modelDraft.trim() || !settingsDirty}
                    className={primaryBtn}>
                    {busy === "settings" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Lưu cấu hình
                  </button>
                  <button onClick={testConnection}
                    disabled={busy !== null || (!settings?.hasApiKey && !apiKeyDraft.trim())}
                    className={ghostBtn}>
                    {busy === "test" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
                    {settingsDirty ? "Lưu & kiểm tra kết nối" : "Kiểm tra kết nối"}
                  </button>
                  {settings?.keyUrl && (
                    <a href={settings.keyUrl} target="_blank" rel="noreferrer"
                      className="text-[11px] font-semibold text-indigo-600 underline-offset-2 hover:underline">
                      Lấy API key của {settings.vendor}
                    </a>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* ── Bước 1: yêu cầu ── */}
          <Step index={1} icon={Wand2}
            title={source === "ai" ? "Mô tả yêu cầu đề" : "Tải đề có sẵn lên"} done={!!deBai}>
            {/* Hai nhánh vào bài: soạn đề mới, hoặc đã có đề rồi thì bỏ qua bước soạn. */}
            <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {([
                { id: "ai" as const, icon: Sparkles, title: "Tạo đề bằng AI",
                  desc: "Mô tả yêu cầu, AI soạn đề rồi bạn sửa lại" },
                { id: "upload" as const, icon: Upload, title: "Tải đề có sẵn lên",
                  desc: "PDF, Word (.docx) hoặc .txt — AI đọc rồi phân tích luôn" },
              ]).map((choice) => (
                <button key={choice.id} type="button" onClick={() => setSource(choice.id)}
                  className={`flex items-start gap-2.5 rounded-xl border p-3 text-left transition-colors ${
                    source === choice.id
                      ? "border-indigo-300 bg-indigo-50/70 ring-1 ring-indigo-200"
                      : "border-slate-200 bg-white hover:border-slate-300"}`}>
                  <choice.icon size={16} className={`mt-0.5 shrink-0 ${
                    source === choice.id ? "text-indigo-600" : "text-slate-400"}`} />
                  <span className="min-w-0">
                    <span className="block text-sm font-bold text-slate-700">{choice.title}</span>
                    <span className="block text-[11px] leading-relaxed text-slate-500">{choice.desc}</span>
                  </span>
                </button>
              ))}
            </div>

            {source === "upload" ? (
              <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-5 text-center">
                <input id="ai-exam-file" type="file" className="hidden"
                  accept=".pdf,.docx,.txt,.md,.markdown"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = "";        // chọn lại đúng file đó vẫn phải kích hoạt onChange
                    if (file) importExam(file);
                  }} />
                <label htmlFor="ai-exam-file"
                  className={`${primaryBtn} mx-auto w-fit cursor-pointer ${busy ? "pointer-events-none opacity-60" : ""}`}>
                  {busy === "import" ? <Loader2 size={15} className="animate-spin" /> : <Upload size={15} />}
                  Chọn file đề
                </label>
                <p className="mt-2.5 text-[11px] leading-relaxed text-slate-500">
                  Nhận .docx, .pdf, .txt, .md. PDF bản scan (chỉ có ảnh) không bóc được chữ — hãy dùng .docx.
                </p>
                {importedName && (
                  <p className="mt-2 font-mono text-[11px] text-emerald-600">Đã đọc: {importedName}</p>
                )}
              </div>
            ) : (
            <>
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
            </>
            )}
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

              {/* Trình soạn thảo kiểu IDE: cây file bên trái, code LUÔN hiện bên phải. Kiểu xếp
                  gấp cũ chỉ thấy tên file nên phải bấm từng cái mới biết AI viết gì. */}
              {starterFiles.length > 0 && (() => {
                const active = starterFiles.find((f) => f.path === openFile) || starterFiles[0];
                const lines = active.content.split("\n");
                return (
                  <div className="mt-3 flex min-h-[22rem] overflow-hidden rounded-xl border border-slate-800 bg-slate-900">
                    <div className="custom-scrollbar w-52 shrink-0 overflow-y-auto border-r border-slate-800 bg-slate-950/60 py-2">
                      <p className="px-3 pb-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-500">
                        {starterFiles.length} file
                      </p>
                      {starterFiles.map((f) => (
                        <button key={f.path} type="button" onClick={() => setOpenFile(f.path)}
                          title={`${f.path} · ${f.summary}`}
                          className={`flex w-full items-center gap-1.5 px-3 py-1.5 text-left font-mono text-[11px] transition-colors ${
                            f.path === active.path
                              ? "bg-slate-800 text-indigo-300"
                              : "text-slate-400 hover:bg-slate-800/60 hover:text-slate-200"}`}>
                          <FileCode2 size={12} className="shrink-0" />
                          <span className="truncate">{f.path}</span>
                        </button>
                      ))}
                    </div>
                    <div className="flex min-w-0 flex-1 flex-col">
                      <div className="flex items-center gap-2 border-b border-slate-800 px-3 py-2">
                        <span className="font-mono text-[11px] font-semibold text-slate-200">{active.path}</span>
                        <span className="truncate text-[10px] text-slate-500">{active.summary}</span>
                        <span className="ml-auto shrink-0 text-[10px] text-slate-600">{lines.length} dòng</span>
                      </div>
                      <div className="custom-scrollbar flex min-h-0 flex-1 overflow-auto">
                        {/* Số dòng bám theo nội dung đang gõ, cuộn chung khung với code. */}
                        <pre aria-hidden className="select-none border-r border-slate-800 bg-slate-950/40 px-2 py-3 text-right font-mono text-[11px] leading-relaxed text-slate-600">
                          {lines.map((_, i) => i + 1).join("\n")}
                        </pre>
                        <textarea
                          value={active.content}
                          onChange={(e) => {
                            const content = e.target.value;
                            setStarterFiles((cur) => cur.map((x) => x.path === active.path ? { ...x, content } : x));
                            setSyntax(null);   // sửa tay xong thì kết quả kiểm cú pháp cũ không còn đúng
                          }}
                          spellCheck={false}
                          wrap="off"
                          className="min-h-full w-full resize-none bg-transparent px-3 py-3 font-mono text-[11px] leading-relaxed text-slate-100 outline-none"
                          style={{ minHeight: `${lines.length * 1.5 + 1.5}rem` }}
                        />
                      </div>
                    </div>
                  </div>
                );
              })()}
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
