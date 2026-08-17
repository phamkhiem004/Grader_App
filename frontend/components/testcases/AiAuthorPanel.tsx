"use client";

// Trợ lý AI soạn đề trong trang "Tạo bộ testcase".
//
// Triết lý: AI chỉ ĐỀ XUẤT, giáo viên quyết định. Mỗi bước đều có chỗ sửa tay + ô nhắc AI sửa
// lại + nút chấp nhận riêng; không bước nào tự ghi vào bộ testcase đang chấm.
//
// Backend: /api/ai/* (xem AiAuthorController). Testcase do AI đề xuất luôn là template có sẵn
// trong thư viện nên vẫn đi qua đúng bộ kiểm tra tham số khi lưu.

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { API_BASE } from "@/lib/config";
import { downloadBlob, downloadText, imageFileToSvg, svgToPng } from "@/lib/mockup-image";
import {
  clearAiDraft, DRAFT_NO_EXAM, fetchAiDraftFromServer, pushAiDraftToServer, readAiDraft, writeAiDraft,
} from "@/lib/aiAuthorDrafts";
import {
  Sparkles, Settings2, KeyRound, Wand2, FileText, Image as ImageIcon, ListChecks,
  Loader2, Check, X, Plus, Trash2, RefreshCw, AlertTriangle, ChevronDown, Save, Info, FileCode2,
  Upload, Download, RotateCcw,
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

interface MockupScreen {
  id: string; title: string; svg: string; keys: string[];
  /** Hình do AI vẽ — giữ lại để hoàn tác khi giáo viên thay bằng ảnh của mình. */
  aiSvg?: string;
  /** Có giá trị = đang dùng ảnh giáo viên tải lên chứ không phải hình AI vẽ. */
  uploadName?: string;
}
interface StarterFile { path: string; content: string; summary: string }
/** Kết quả một lượt sinh/sửa khung starter; `spec` là bản mô tả để lượt sửa sau nối tiếp. */
interface StarterResult {
  files: StarterFile[]; warnings: string[]; notes: string[]; spec: unknown;
  syntax_ok: boolean | null; syntax_message: string;
}
interface AiModel { id: string; label: string; provider: string; vendor: string }
interface AiSettings {
  model: string; provider: string; vendor: string; keyUrl: string | null;
  hasApiKey: boolean; apiKeyMasked: string | null; keyWarning: string | null;
  /** Độ dài key đang lưu — soi được "key dán thiếu/thừa ký tự" mà không lộ nội dung key. */
  apiKeyLength: number;
  baseUrl: string; customBaseUrl: boolean;
  timeoutSeconds: number; models: AiModel[]; ready: boolean;
}

interface Props {
  examId: string;
  /** Các key đã khai ở Khu vực 0 — để biết key nào AI đề xuất là mới. */
  existingKeys: string[];
  /** Testcase này đã nằm trong Khu vực 3 chưa (trang cha trả lời, xem hasProposedItem). */
  hasItem?: (templateId: string, parameters: Record<string, unknown>) => boolean;
  onApplyContract: (keys: AiContractKey[], requireKeys: boolean) => void;
  onApplyItems: (items: AiProposedItem[]) => void;
}

const STRATEGIES = ["key_only", "auto", "widget_type", "icon", "tooltip", "text", "button_text", "type_with_text"];

export default function AiAuthorPanel({ examId, existingKeys, hasItem, onApplyContract, onApplyItems }: Props) {
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
    architecture: "MVVM + Riverpod (tùy chọn)", storage: "SQLite",
    difficulty: "Trung bình", duration: "90 phút", note: "",
  });

  // Bước 2 — đề bài
  const [deBai, setDeBai] = useState("");
  const [summary, setSummary] = useState("");
  // Thang điểm AI trả về KHÔNG hiện thành dòng riêng nữa (đã nằm trong mục 5 của đề); vẫn giữ
  // state để reset khi sinh đề mới, tránh số liệu của đề cũ dính sang.
  const [, setCriteria] = useState<{ name: string; points: number }[]>([]);
  const [revisePrompt, setRevisePrompt] = useState("");
  const [examAccepted, setExamAccepted] = useState(false);

  // Bước 3 — Item Key + hình
  const [keys, setKeys] = useState<AiContractKey[]>([]);
  const [requireKeys, setRequireKeys] = useState(true);
  const [mockupSpec, setMockupSpec] = useState<unknown>(null);
  const [screens, setScreens] = useState<MockupScreen[]>([]);
  const [keyNotes, setKeyNotes] = useState<string[]>([]);
  /**
   * Key đã khai nhưng CHƯA có mặt trên hình. Giữ thành danh sách riêng (không nhét vào ghi chú)
   * để còn nhờ AI vẽ bổ sung đúng mấy key đó bằng một cú bấm.
   */
  const [unusedKeys, setUnusedKeys] = useState<string[]>([]);
  const [keysAccepted, setKeysAccepted] = useState(false);
  const [mockupPrompt, setMockupPrompt] = useState("");
  /** Màn hình đang chờ nhận ảnh từ máy — một ô chọn file dùng chung cho mọi hình. */
  const [uploadTarget, setUploadTarget] = useState<string | null>(null);
  const mockupFileRef = useRef<HTMLInputElement | null>(null);

  // Bước 4 — testcase
  const [proposed, setProposed] = useState<AiProposedItem[]>([]);
  const [rejected, setRejected] = useState<{ template_id: string; reason: string }[]>([]);
  const [missingKeys, setMissingKeys] = useState<string[]>([]);

  // Bước 5 — khung starter phát cho sinh viên
  const [starterFiles, setStarterFiles] = useState<StarterFile[]>([]);
  const [starterWarnings, setStarterWarnings] = useState<string[]>([]);
  const [syntax, setSyntax] = useState<{ ok: boolean | null; message: string } | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [starterPrompt, setStarterPrompt] = useState("");
  /** Bản mô tả khung AI trả về — gửi lại cho lượt "nhờ AI sửa" để sửa tiếp từ đúng bản này. */
  const [starterSpec, setStarterSpec] = useState<unknown>(null);
  /** true = giáo viên đã gõ tay vào code, lượt AI sửa kế tiếp sẽ ghi đè. */
  const [starterEdited, setStarterEdited] = useState(false);

  // ── Bản nháp: giữ nguyên bước đang dở khi tải lại trang / quay lại sau ──
  /** Nháp của bộ nào đã nạp xong. Chưa nạp mà đã ghi thì lần mount đầu (state rỗng) xoá sạch nháp. */
  const restoredFor = useRef<string | null>(null);
  /** Mốc thời gian của bản nháp vừa khôi phục — để báo cho giáo viên biết họ đang tiếp tục dở dang. */
  const [restoredAt, setRestoredAt] = useState<number | null>(null);
  /** Phần bị bỏ bớt khi ghi nháp vì localStorage đầy (thường là hình minh họa). */
  const [draftTrimmed, setDraftTrimmed] = useState<string[]>([]);
  /**
   * Khoá cất nháp. Chưa gõ mã bộ testcase thì cất tạm ở {@link DRAFT_NO_EXAM} — bước soạn đề
   * không cần mã, mà không có chỗ cất thì cả phần đó mất trắng lúc gõ mã vào.
   */
  const draftKey = examId.trim() || DRAFT_NO_EXAM;

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

  /**
   * Khôi phục bản nháp của bộ đang mở.
   *
   * <p>Chạy khi đổi mã bộ testcase — mở lại trang, bấm "Sửa" đúng bộ đó, hay gõ lại mã đều rơi
   * vào đây. Có nháp thì mở sẵn panel: đang làm dở mà panel đóng im thì người dùng tưởng mất hết.
   */
  useEffect(() => {
    const previous = restoredFor.current;
    if (previous === draftKey) return;
    restoredFor.current = draftKey;
    setDraftTrimmed([]);
    let cancelled = false;

    (async () => {
      const local = readAiDraft(draftKey);
      // Nháp còn được gắn vào chính bộ testcase trên server, nên bấm "Sửa" một bộ đã soạn bằng AI
      // ở máy khác vẫn mở lại đúng phiên đó. Hai bên cùng có thì lấy bản MỚI HƠN.
      const remote = await fetchAiDraftFromServer(API_BASE, draftKey);
      if (cancelled || restoredFor.current !== draftKey) return;
      const draft = !remote ? local
        : !local ? remote
        : (remote.updatedAt || 0) > (local.updatedAt || 0) ? remote : local;

      if (!draft) {
        // Không có nháp cho khoá này. Đổi khoá GIỮA PHIÊN chỉ xảy ra khi ô mã bộ testcase đang
        // được gõ (đặt mã lần đầu, hay đổi mã bộ đang sửa) — việc trên màn hình vẫn là việc đó,
        // chỉ đổi chỗ cất. Dọn màn ở đây là xoá trắng công sức giáo viên vừa bỏ ra, đúng lỗi
        // "bấm Xem code hiện tại là mất hết"; mà gõ "PE_62"→"PE_63" thì mọi mã dở dang ở giữa
        // ("PE_6") đều rơi vào nhánh này. Mở bộ khác thật sự là bấm "Sửa" từ Kho → trang dựng
        // lại từ đầu, previous = null, nên vẫn dọn được màn.
        if (previous !== null) {
          if (previous === DRAFT_NO_EXAM) clearAiDraft(DRAFT_NO_EXAM);   // đã có mã thật để cất
          return;                                                        // GIỮ NGUYÊN việc đang làm
        }
        setRestoredAt(null);
        resetWizard();
        return;
      }
      applyDraft(draft);
    })();
    return () => { cancelled = true; };
  }, [draftKey]);

  /** Đổ một bản nháp (từ localStorage hoặc từ server) trở lại các bước của trợ lý. */
  const applyDraft = (draft: { updatedAt: number; state: Record<string, unknown> }) => {
    const s = draft.state as Record<string, never>;
    setSource(s.source ?? "ai");
    setImportedName(s.importedName ?? "");
    if (s.req) setReq((cur) => ({ ...cur, ...(s.req as object) }));
    setDeBai(s.deBai ?? "");
    setSummary(s.summary ?? "");
    setExamAccepted(!!s.examAccepted);
    setKeys(s.keys ?? []);
    setRequireKeys(s.requireKeys ?? true);
    setMockupSpec(s.mockupSpec ?? null);
    setScreens(s.screens ?? []);
    setKeyNotes(s.keyNotes ?? []);
    setUnusedKeys(s.unusedKeys ?? []);
    setKeysAccepted(!!s.keysAccepted);
    setProposed(s.proposed ?? []);
    setRejected(s.rejected ?? []);
    setMissingKeys(s.missingKeys ?? []);
    setStarterFiles(s.starterFiles ?? []);
    setStarterSpec(s.starterSpec ?? null);
    setStarterWarnings(s.starterWarnings ?? []);
    setSyntax(s.syntax ?? null);
    setOpenFile(s.openFile ?? null);
    setRestoredAt(draft.updatedAt);
    setOpen(true);
  };

  /** Về màn trắng (đổi sang bộ chưa có nháp, hoặc người dùng bấm "Bắt đầu lại"). */
  const resetWizard = () => {
    setDeBai(""); setSummary(""); setCriteria([]); setExamAccepted(false);
    setKeys([]); setRequireKeys(true); setMockupSpec(null); setScreens([]);
    setKeyNotes([]); setUnusedKeys([]); setKeysAccepted(false);
    setProposed([]); setRejected([]); setMissingKeys([]);
    setStarterFiles([]); setStarterSpec(null); setStarterWarnings([]);
    setSyntax(null); setOpenFile(null); setStarterEdited(false);
    setImportedName(""); setRevisePrompt(""); setMockupPrompt(""); setStarterPrompt("");
  };

  /**
   * Ghi nháp mỗi khi có thay đổi đáng kể. Hoãn 800ms: gõ sửa đề trong ô textarea đổi state theo
   * từng ký tự, ghi thẳng là serialize cả bộ hình + khung starter sau mỗi phím.
   */
  useEffect(() => {
    if (restoredFor.current !== draftKey) return;           // chưa khôi phục xong thì chưa được ghi
    const empty = !deBai && !keys.length && !screens.length
      && !proposed.length && !starterFiles.length;
    const timer = setTimeout(() => {
      if (empty) {
        clearAiDraft(draftKey);
        pushAiDraftToServer(API_BASE, draftKey, null);
        setRestoredAt(null);
        return;
      }
      const state = {
        source, importedName, req, deBai, summary, examAccepted,
        keys, requireKeys, mockupSpec, screens, keyNotes, unusedKeys, keysAccepted,
        proposed, rejected, missingKeys,
        starterFiles, starterSpec, starterWarnings, syntax, openFile,
      };
      setDraftTrimmed(writeAiDraft(draftKey, state));
      // Gắn luôn vào bộ testcase trên server: bấm "Sửa" bộ này ở máy khác vẫn mở lại đúng phiên.
      pushAiDraftToServer(API_BASE, draftKey, state);
    }, 800);
    return () => clearTimeout(timer);
  }, [draftKey, source, importedName, req, deBai, summary, examAccepted,
    keys, requireKeys, mockupSpec, screens, keyNotes, unusedKeys, keysAccepted,
    proposed, rejected, missingKeys, starterFiles, starterSpec, starterWarnings, syntax, openFile]);

  /** Bỏ hẳn bản nháp và làm lại từ đầu cho bộ này. */
  const discardDraft = () => {
    if (!confirm("Bỏ bản nháp của bộ này và bắt đầu lại từ đầu?")) return;
    clearAiDraft(draftKey);
    pushAiDraftToServer(API_BASE, draftKey, null);
    resetWizard();
    setRestoredAt(null);
    setDraftTrimmed([]);
    setInfo("Đã bỏ bản nháp. Bắt đầu lại từ bước soạn đề.");
  };

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

  /**
   * MỘT nút cho cả việc thử lẫn lưu: gọi thử trên cấu hình ĐANG GÕ (không ghi gì), thử được mới
   * lưu.
   *
   * <p>Tách hai nút hoá ra thừa: chẳng ai muốn lưu một cấu hình vừa thử hỏng, cũng chẳng ai thử
   * xong lại không muốn lưu. Quan trọng là THỨ TỰ — thử trước nên gõ nhầm key không ghi đè mất
   * key đang chạy được.
   */
  const testAndSaveSettings = async () => {
    if (!apiKeyDraft.trim() && !settings?.hasApiKey) {
      setError(`Chưa có API key cho ${draftVendor}. Hãy dán key rồi thử lại.`);
      return;
    }
    const probe = await call<{ ok: boolean; message: string; elapsedMs: number }>(
      "/ai/settings/test",
      {
        model: modelDraft.trim(),
        apiKey: apiKeyDraft.trim(),              // rỗng = dùng key đã lưu
        baseUrl: baseUrlDraft.trim(),            // rỗng = endpoint chính thức của hãng
      },
      "settings");
    if (!probe) return;
    if (!probe.ok) {
      setError(probe.message || "Không kết nối được — chưa lưu gì cả.");
      return;
    }
    const data = await persistSettings("settings");
    if (data) {
      setInfo(`Kết nối thành công (${probe.elapsedMs} ms) và đã lưu. `
        + `Đang dùng ${data.model} (${data.vendor}).`);
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
      setUnusedKeys(data.unused_keys || []);
      setKeysAccepted(false);
    }
  };

  /**
   * Giữ lại ảnh giáo viên đã tải lên khi hình được vẽ lại: họ chọn ảnh đó là có chủ ý, một lần
   * bấm "vẽ lại" không được âm thầm quăng nó đi. Bản AI mới vẫn nằm trong aiSvg để hoàn tác.
   */
  const keepUploads = (next: MockupScreen[]) =>
    setScreens((prev) => next.map((fresh) => {
      const old = prev.find((s) => s.id === fresh.id);
      return old?.uploadName
        ? { ...fresh, svg: old.svg, uploadName: old.uploadName, aiSvg: fresh.svg }
        : fresh;
    }));

  const redrawMockup = async () => {
    const data = await call<{ screens: MockupScreen[] }>("/ai/keys/mockup", { mockup_spec: mockupSpec }, "mockup");
    if (data) {
      keepUploads(data.screens || []);
      setInfo("Đã vẽ lại hình từ bản mô tả hiện tại.");
    }
  };

  /** Nhờ AI sửa BỐ CỤC hình bằng lời; toạ độ vẫn do máy chủ tính nên hình không bao giờ rối. */
  const reviseMockup = async (instruction?: string) => {
    const order = (instruction ?? mockupPrompt).trim();
    if (!order) { setError("Hãy mô tả bạn muốn sửa gì trên hình."); return; }
    const data = await call<{
      mockup_spec: unknown; screens: MockupScreen[]; notes: string[]; unused_keys: string[];
      contract?: { require_keys: boolean; keys: AiContractKey[] };
    }>("/ai/keys/mockup/revise",
      { mockup_spec: mockupSpec, instruction: order, contract: { keys } }, "mockup-revise");
    if (data) {
      setMockupSpec(data.mockup_spec);
      keepUploads(data.screens || []);
      // Sửa hình là sửa CẢ danh sách key — bỏ ô nhập khỏi hình mà key của nó còn nằm trong hợp
      // đồng thì bộ chấm đi tìm một widget không còn trên đề.
      let keyNote = "";
      if (data.contract?.keys?.length) {
        const before = keys.length;
        setKeys(data.contract.keys);
        setRequireKeys(data.contract.require_keys ?? true);
        setKeysAccepted(false);            // key đổi rồi thì phải chấp nhận lại vào Khu vực 0
        keyNote = ` Danh sách Item Key cập nhật theo hình: ${before} → ${data.contract.keys.length} key`
          + " — bấm “Chấp nhận Item Key” để đưa vào Khu vực 0.";
      }
      setUnusedKeys(data.unused_keys || []);
      setMockupPrompt("");
      setInfo("AI đã sửa hình." + keyNote);
    }
  };

  /**
   * Vẽ bổ sung đúng những key đã khai mà hình còn thiếu.
   *
   * <p>Chỉ thị được dựng sẵn từ danh sách key, kèm gợi ý loại thành phần theo tiền tố quy ước
   * (screen.* là một màn hình, action.* là nút…) — AI đoán mò loại widget thì hay vẽ nhầm chỗ.
   */
  const drawMissingKeys = () => {
    if (!unusedKeys.length) return;
    const hint = (key: string) => {
      if (key.startsWith("screen.")) return `${key} (một MÀN HÌNH riêng)`;
      if (key.startsWith("action.")) return `${key} (một nút)`;
      if (key.startsWith("field.")) return `${key} (một ô nhập)`;
      if (key.startsWith("list.")) return `${key} (một danh sách)`;
      if (key.startsWith("item.")) return `${key} (một dòng trong danh sách)`;
      if (key.startsWith("error.")) return `${key} (một dòng báo lỗi)`;
      if (key.startsWith("message.")) return `${key} (một dòng thông báo)`;
      return key;
    };
    reviseMockup("Bổ sung vào hình các thành phần còn thiếu cho những key sau, đặt đúng màn hình "
      + "và đúng vị trí hợp lý: " + unusedKeys.map(hint).join("; ")
      + ". Giữ nguyên toàn bộ thành phần và key đang có.");
  };

  /**
   * Vẽ bổ sung KHÔNG cần AI: tự chèn thành phần cho từng key còn thiếu vào bản mô tả rồi vẽ lại.
   *
   * <p>Loại widget suy từ tiền tố key theo đúng quy ước của engine, nên chèn được chắc chắn đúng
   * kiểu. Không tốn lượt gọi AI, không phụ thuộc mạng, và quan trọng nhất là KHÔNG BAO GIỜ trượt:
   * nhờ AI thì vẫn có lần nó bỏ sót đúng cái key mình đang thiếu.
   */
  const fillMissingKeysLocally = async () => {
    if (!unusedKeys.length) return;
    const spec = JSON.parse(JSON.stringify(mockupSpec ?? {})) as {
      screens?: { id?: string; title?: string; appBar?: string; appBarKey?: string; nodes?: unknown[] }[];
    };
    spec.screens = Array.isArray(spec.screens) ? spec.screens : [];

    const label = (key: string) => key.split(".").slice(1).join(" ") || key;
    for (const key of unusedKeys) {
      if (key.startsWith("screen.")) {
        // Key màn hình gắn vào tiêu đề màn: có sẵn màn cùng tên thì dùng, không thì mở màn mới.
        const id = key.split(".").pop() || "screen";
        const found = spec.screens.find((s) => s.id === id);
        if (found) found.appBarKey = key;
        else spec.screens.push({ id, title: `Màn hình ${label(key)}`, appBar: label(key), appBarKey: key, nodes: [] });
        continue;
      }
      if (!spec.screens.length) spec.screens.push({ id: "home", title: "Màn hình chính", appBar: "App", nodes: [] });
      const screen = spec.screens[0];
      screen.nodes = Array.isArray(screen.nodes) ? screen.nodes : [];
      const type = key.startsWith("field.") ? "textfield"
        : key.startsWith("action.") ? "button"
        : key.startsWith("list.") ? "list"
        : key.startsWith("error.") ? "error"
        : key.startsWith("item.") ? "text"
        : "text";
      screen.nodes.push(type === "list"
        ? { type, label: label(key), key, items: [] }
        : { type, label: label(key), key });
    }

    setMockupSpec(spec);
    const data = await call<{ screens: MockupScreen[] }>("/ai/keys/mockup", { mockup_spec: spec }, "mockup");
    if (data) {
      keepUploads(data.screens || []);
      setUnusedKeys([]);
      setInfo(`Đã vẽ bổ sung ${unusedKeys.length} key vào hình: ${unusedKeys.join(", ")}. `
        + "Sửa lại nhãn/vị trí bằng ô nhắc AI bên dưới nếu cần.");
    }
  };

  const downloadScreenSvg = (screen: MockupScreen) =>
    downloadText(screen.svg, `${screen.id}.svg`, "image/svg+xml;charset=utf-8");

  const downloadScreenPng = async (screen: MockupScreen) => {
    try {
      const shot = await svgToPng(screen.svg);
      const binary = atob(shot.png.split(",")[1] || "");
      const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
      downloadBlob(new Blob([bytes], { type: "image/png" }), `${screen.id}.png`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được ảnh PNG.");
    }
  };

  /** Thay hình AI bằng ảnh giáo viên tự vẽ/chụp — ảnh nhúng thẳng vào SVG nên vẫn tự chứa. */
  const applyUploadedImage = async (file: File) => {
    const id = uploadTarget;
    setUploadTarget(null);
    if (!id) return;
    setError(null);
    try {
      const { svg } = await imageFileToSvg(file);
      setScreens((cur) => cur.map((s) => (s.id === id
        ? { ...s, svg, uploadName: file.name, aiSvg: s.aiSvg ?? s.svg }
        : s)));
      setInfo(`Đã thay hình "${id}" bằng ${file.name}. Bấm "Lưu đề bài + hình" để phát cho sinh viên.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không đọc được ảnh.");
    }
  };

  const revertScreen = (screen: MockupScreen) =>
    setScreens((cur) => cur.map((s) => (s.id === screen.id && s.aiSvg
      ? { ...s, svg: s.aiSvg, uploadName: undefined }
      : s)));

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
      setMissingKeys(data.missing_keys || []);
    }
  };

  /**
   * Testcase đang chọn mà Khu vực 3 CHƯA có. Đây là thứ nút "Chấp nhận" thực sự thêm vào — thêm
   * lại cái đã có chỉ tạo bản trùng, nên hết cái thiếu là nút phải tắt.
   */
  const missingProposals = useMemo(
    () => proposed.filter((item) => item.enabled
      && !(hasItem?.(item.template_id, item.parameters) ?? false)),
    [proposed, hasItem],
  );

  /**
   * Khai ngay các key mà testcase đang dùng nhưng Khu vực 0 chưa có.
   *
   * <p>Thêm vào cả bảng key của bước 3 lẫn Khu vực 0 — hai chỗ lệch nhau thì lần "chấp nhận Item
   * Key" sau lại xoá mất key vừa khai.
   */
  const declareMissingKeys = () => {
    if (!missingKeys.length) return;
    const added: AiContractKey[] = missingKeys.map((key) => ({
      key, label: key, strategy: "key_only", value: "", index: 0,
    }));
    setKeys((cur) => [...cur, ...added.filter((row) => !cur.some((x) => x.key === row.key))]);
    onApplyContract(added, requireKeys);
    setMissingKeys([]);
    setInfo(`Đã khai ${added.length} key vào Khu vực 0: ${added.map((k) => k.key).join(", ")}.`);
  };

  const acceptItems = () => {
    if (!proposed.some((i) => i.enabled)) { setError("Chưa chọn testcase nào để thêm."); return; }
    if (!missingProposals.length) return;         // nút đã tắt, đây chỉ là lưới an toàn
    onApplyItems(missingProposals);
    setInfo(`Đã thêm ${missingProposals.length} testcase vào Khu vực 3 — bạn vẫn sửa tham số được ở đó.`);
  };

  // ── Bước 5: khung starter ──────────────────────────────────────
  const takeStarter = (data: StarterResult) => {
    setStarterFiles(data.files || []);
    setStarterWarnings(data.warnings || []);
    setStarterSpec(data.spec ?? null);
    setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
    setOpenFile(data.files?.[0]?.path ?? null);
    setStarterEdited(false);
  };

  const proposeStarter = async () => {
    const data = await call<StarterResult>(
      "/ai/starter/propose", { de_bai: deBai, contract: { keys } }, "starter");
    if (data) takeStarter(data);
  };

  /**
   * Nhờ AI sửa khung bằng lời. Máy chủ sinh lại TOÀN BỘ file từ bản mô tả đã sửa nên phần giáo
   * viên gõ tay bị thay — hỏi trước cho chắc, mất code vừa gõ là bực nhất.
   */
  const reviseStarter = async () => {
    if (!starterPrompt.trim()) { setError("Hãy mô tả bạn muốn AI sửa gì trong khung starter."); return; }
    if (starterEdited && !confirm(
      "AI sẽ sinh lại toàn bộ khung, phần code bạn vừa sửa tay sẽ bị thay. Tiếp tục?")) return;
    const data = await call<StarterResult>("/ai/starter/revise",
      { de_bai: deBai, spec: starterSpec, instruction: starterPrompt, contract: { keys } },
      "starter-revise");
    if (data) {
      takeStarter(data);
      setStarterPrompt("");
      setInfo("AI đã sửa khung starter. Xem lại code rồi lưu cho sinh viên.");
    }
  };

  const recheckStarter = async () => {
    const data = await call<{ syntax_ok: boolean | null; syntax_message: string }>(
      "/ai/starter/check", { files: starterFiles }, "starter-check");
    if (data) setSyntax({ ok: data.syntax_ok, message: data.syntax_message });
  };

  /**
   * MỘT nút cho khung starter: lưu vào bộ testcase RỒI tải luôn .zip về máy.
   *
   * <p>Trước đây tách hai nút "Lưu khung cho SV" và "Tải khung (.zip)" — cùng một bộ file, khác
   * mỗi chỗ đến, nên ai cũng phải bấm cả hai và không rõ hai nút khác nhau ở đâu. Gộp lại: bấm
   * một cái là bộ testcase có khung để phát, và máy có file để đem đi thử build.
   */
  const saveAndDownloadStarter = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu khung starter."); return; }
    setBusy("starter-save"); setError(null); setInfo(null);
    try {
      const saveRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/starter`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ files: starterFiles.map((f) => ({ name: f.path, content: f.content })) }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được khung starter.");

      // Tải bản ĐANG HIỂN THỊ (đã gồm cả phần giáo viên vừa gõ tay) — cùng nội dung vừa lưu.
      const zipRes = await fetch(`${API_BASE}/ai/starter/download`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exam_id: examId.trim(), files: starterFiles }),
      });
      if (!zipRes.ok) {
        const data = await zipRes.json().catch(() => ({}));
        throw new Error(data?.error || "Đã lưu nhưng không tải được file .zip.");
      }
      downloadBlob(await zipRes.blob(), `${examId.trim()}_starter.zip`);
      setInfo(`Đã lưu khung starter (${saved.files?.length ?? starterFiles.length} file) vào bộ `
        + `${examId.trim()} và tải .zip về máy.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu/tải được khung starter.");
    } finally {
      setBusy(null);
    }
  };

  // ── Lưu bộ phát cho SV ─────────────────────────────────────────
  /**
   * Lưu đề bài + hình vào bộ testcase RỒI tải luôn bản .docx về máy.
   *
   * <p>Trước đây bấm xong chỉ hiện một dòng chữ "đã lưu", muốn cầm đề phải sang trang Kho đề mở
   * tiếp — mà việc ngay sau khi chốt đề bao giờ cũng là đem đề đi phát.
   *
   * <p>Hình đổi SVG → PNG NGAY TRONG TRÌNH DUYỆT trước khi gửi: máy chủ không có thư viện
   * rasterize, còn Word thì không hiển thị SVG ổn định (xem lib/mockup-image.ts).
   */
  const saveHandout = async () => {
    if (!examId.trim()) { setError("Hãy nhập mã bộ testcase trước khi lưu đề bài."); return; }
    const exam = examId.trim();
    setBusy("handout"); setError(null); setInfo(null);
    try {
      const saveRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/handout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ de_bai: deBai, mockups: screens.map((s) => ({ id: s.id, svg: s.svg })) }),
      });
      const saved = await saveRes.json().catch(() => ({}));
      if (!saveRes.ok) throw new Error(saved?.error || "Không lưu được đề bài.");

      const images: { png_base64: string; width: number; height: number }[] = [];
      for (const screen of screens) {
        try {
          const shot = await svgToPng(screen.svg);
          images.push({ png_base64: shot.png, width: shot.width, height: shot.height });
        } catch {
          // Một hình lỗi không được làm hỏng cả bản tải về — bỏ qua đúng hình đó thôi.
        }
      }
      const docxRes = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(exam)}/de-bai/docx`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ images }),
      });
      if (!docxRes.ok) {
        const data = await docxRes.json().catch(() => ({}));
        throw new Error(data?.error || "Đã lưu nhưng không tải được bản .docx.");
      }
      downloadBlob(await docxRes.blob(), `${exam}_de_bai.docx`);
      setInfo(`Đã lưu đề bài + ${screens.length} hình vào bộ ${exam} và tải bản .docx về máy.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không lưu/tải được đề bài.");
    } finally {
      setBusy(null);
    }
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
          {/* Bản nháp: nói rõ đang tiếp tục dở dang, kèm đường lùi về làm lại từ đầu. Không có
              dòng này thì người dùng thấy đề cũ hiện sẵn mà tưởng hệ thống nhớ nhầm bộ khác. */}
          {restoredAt !== null && (
            <div className="flex flex-wrap items-center gap-2 rounded-xl border border-indigo-200 bg-indigo-50 p-3 text-xs text-indigo-800">
              <RotateCcw size={14} className="shrink-0" />
              <span className="font-medium">
                {examId.trim()
                  ? <>Đang tiếp tục bản soạn dở của bộ <span className="font-mono font-bold">{examId.trim()}</span></>
                  : "Đang tiếp tục bản soạn dở (chưa đặt mã bộ testcase)"}
                {" · lưu lúc "}{new Date(restoredAt).toLocaleString("vi-VN")}
              </span>
              {draftTrimmed.length > 0 && (
                <span className="rounded bg-amber-100 px-1.5 py-0.5 font-semibold text-amber-800">
                  Bộ nhớ trình duyệt đầy nên không giữ được {draftTrimmed.join(" và ")} — bấm “Vẽ lại hình” hoặc “Sinh lại khung” khi cần.
                </span>
              )}
              <button onClick={discardDraft}
                className="ml-auto rounded-lg border border-indigo-200 bg-white px-2.5 py-1 font-semibold text-indigo-700 hover:bg-indigo-100">
                Bắt đầu lại
              </button>
            </div>
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
                        // Cắt mọi ký tự KHÔNG phải ASCII in được: copy key từ web/chat rất hay dính
                        // zero-width space (U+200B) hay BOM — mắt không thấy, hãng thì trả 401
                        // "API key is invalid" mà ô key luôn hiện dạng che nên không soi ra được.
                        onChange={(e) => setApiKeyDraft(e.target.value.replace(/[^\x21-\x7E]/g, ""))}
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
                {/* Thứ tự bắt buộc: THỬ trước, LƯU sau. Phép thử chạy trên cấu hình đang gõ và
                    không ghi gì, nên gõ nhầm key cũng không mất key đang dùng được. */}
                <div className="flex flex-wrap items-center gap-2">
                  <button onClick={testAndSaveSettings}
                    disabled={busy !== null || (!settings?.hasApiKey && !apiKeyDraft.trim())}
                    title="Gọi thử một lượt; gọi được mới lưu — thử hỏng thì cấu hình cũ giữ nguyên"
                    className={primaryBtn}>
                    {busy === "settings" ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
                    Kiểm tra &amp; lưu cấu hình
                  </button>
                  {settings?.apiKeyLength ? (
                    <span className="text-[11px] text-slate-400">
                      Key đang lưu: {settings.apiKeyMasked} · {settings.apiKeyLength} ký tự
                    </span>
                  ) : null}
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
              {/* Hai ô này đi thẳng vào mục 1 của khuôn "Yêu cầu kỹ thuật" — trước đây phải nhét
                  vào ô "Kiến thức" nên đề ra hay thiếu hoặc tự bịa công nghệ khác. */}
              <Field label="Kiến trúc & quản lý trạng thái">
                <input value={req.architecture} onChange={(e) => setReq({ ...req, architecture: e.target.value })}
                  placeholder="MVVM + Riverpod (tùy chọn)" className={inputClass} />
              </Field>
              <Field label="Lưu trữ dữ liệu">
                <input value={req.storage} onChange={(e) => setReq({ ...req, storage: e.target.value })}
                  placeholder="SQLite / File / SharedPreferences" className={inputClass} />
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
              {/* Bỏ dòng "Thang điểm AI đề xuất: …": bảng thang điểm đã nằm ngay trong mục 5 của
                  đề bên dưới, nhắc lại thành một dòng dài chỉ làm rối chỗ cần đọc. */}
              {summary && <p className="mb-2 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600">{summary}</p>}
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

              {/* Key đã khai mà hình chưa có: nhờ AI vẽ bổ sung ngay tại chỗ. Chỉ báo suông thì
                  giáo viên phải tự nghĩ ra câu lệnh sửa hình cho đúng mấy key này. */}
              {unusedKeys.length > 0 && (
                <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 p-2.5 text-[11px] leading-relaxed text-amber-800">
                  <Info size={12} className="shrink-0" />
                  <span>
                    {unusedKeys.length} key chưa xuất hiện trên hình:{" "}
                    <strong className="font-mono">{unusedKeys.join(", ")}</strong>
                  </span>
                  <span className="ml-auto flex items-center gap-1.5">
                    {/* Vẽ thẳng: chèn đúng loại widget theo tiền tố key, không gọi AI nên không
                        bao giờ trượt. Nhờ AI thì đặt được vào đúng màn/đúng chỗ hợp lý hơn. */}
                    <button onClick={fillMissingKeysLocally} disabled={busy !== null}
                      title="Chèn ngay thành phần cho các key này rồi vẽ lại — không tốn lượt gọi AI"
                      className="flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-2.5 py-1 font-semibold text-amber-800 hover:bg-amber-100 disabled:opacity-50">
                      {busy === "mockup" ? <Loader2 size={12} className="animate-spin" /> : <Plus size={12} />}
                      Vẽ bổ sung ngay
                    </button>
                    <button onClick={drawMissingKeys} disabled={busy !== null}
                      title="Để AI tự đặt vào đúng màn hình và đúng vị trí hợp lý"
                      className="flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-2.5 py-1 font-semibold text-amber-800 hover:bg-amber-100 disabled:opacity-50">
                      {busy === "mockup-revise"
                        ? <Loader2 size={12} className="animate-spin" />
                        : <Wand2 size={12} />}
                      Nhờ AI vẽ
                    </button>
                  </span>
                </div>
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
                <figure key={s.id} className="mt-4 rounded-xl border border-slate-200 bg-white p-3">
                  <figcaption className="mb-2 flex flex-wrap items-center gap-2">
                    <span className="text-xs font-bold text-slate-600">{s.title}</span>
                    {s.uploadName && (
                      <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-700">
                        Ảnh của bạn · {s.uploadName}
                      </span>
                    )}
                    <span className="ml-auto flex flex-wrap items-center gap-1">
                      <button type="button" onClick={() => downloadScreenSvg(s)} className={miniBtn} title="Tải bản vẽ gốc (.svg)">
                        <Download size={12} /> SVG
                      </button>
                      <button type="button" onClick={() => downloadScreenPng(s)} className={miniBtn} title="Tải ảnh .png để dán vào đề Word">
                        <Download size={12} /> PNG
                      </button>
                      <button type="button" className={miniBtn} title="Dùng ảnh tự vẽ/chụp từ máy thay cho hình AI"
                        onClick={() => { setUploadTarget(s.id); mockupFileRef.current?.click(); }}>
                        <Upload size={12} /> Ảnh của tôi
                      </button>
                      {s.uploadName && s.aiSvg && (
                        <button type="button" onClick={() => revertScreen(s)} className={miniBtn} title="Bỏ ảnh tải lên, quay lại hình AI vẽ">
                          <RotateCcw size={12} /> Hình AI
                        </button>
                      )}
                    </span>
                  </figcaption>
                  {/* SVG do máy chủ dựng, hoặc ảnh của giáo viên đã bọc trong thẻ <image> (chạy ở
                      chế độ tĩnh, không thực thi script) — xem lib/mockup-image.ts. */}
                  <div className="custom-scrollbar overflow-x-auto">
                    <div className="min-w-[720px] [&>svg]:h-auto [&>svg]:max-w-full"
                      dangerouslySetInnerHTML={{ __html: s.svg }} />
                  </div>
                </figure>
              ))}

              {/* Một ô chọn file dùng chung cho mọi hình — uploadTarget nhớ đang thay hình nào. */}
              <input ref={mockupFileRef} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml"
                className="hidden"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";           // chọn lại đúng file đó lần nữa vẫn nhận
                  if (file) applyUploadedImage(file);
                }} />

              {screens.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      value={mockupPrompt}
                      onChange={(e) => setMockupPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseMockup(); } }}
                      placeholder="VD: bỏ ô số điện thoại, thêm màn hình chi tiết, đổi nút Add User thành FAB…"
                      className={`${inputClass} min-w-[240px] flex-1`}
                    />
                    <button onClick={() => reviseMockup()} disabled={busy !== null} className={ghostBtn}>
                      {busy === "mockup-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />}
                      Nhờ AI sửa hình
                    </button>
                  </div>
                </div>
              )}
            </Step>
          )}

          {/* ── Bước 4: testcase ── */}
          {keysAccepted && (
            <Step index={4} icon={ListChecks} title="Bộ testcase đề xuất" done={false}>
              <button onClick={proposeTestcases} disabled={busy !== null} className={primaryBtn}>
                {busy === "testcases" ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
                {proposed.length ? "Đề xuất lại" : "Đề xuất bộ testcase"}
              </button>

              {/* Key thiếu thì khai luôn tại chỗ. Bắt quay lại bước 3, sửa bảng, rồi chấp nhận lại
                  chỉ để thêm một dòng key là ba thao tác cho một việc máy tự làm được. */}
              {missingKeys.length > 0 && (
                <div className="mt-3 flex flex-wrap items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
                  <span>
                    Testcase đang dùng key chưa khai ở Khu vực 0: <strong>{missingKeys.join(", ")}</strong>.
                  </span>
                  <button onClick={declareMissingKeys}
                    className="ml-auto flex items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-2.5 py-1 font-semibold text-amber-800 hover:bg-amber-100">
                    <Plus size={13} /> Khai {missingKeys.length} key này vào Khu vực 0
                  </button>
                </div>
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
                      {!missingProposals.length && proposed.some((i) => i.enabled) && (
                        <span className="ml-1.5 font-semibold text-emerald-600">· đã có đủ trong Khu vực 3</span>
                      )}
                    </span>
                    <button onClick={acceptItems} disabled={!missingProposals.length}
                      title={missingProposals.length
                        ? `Thêm ${missingProposals.length} testcase Khu vực 3 chưa có`
                        : "Khu vực 3 đã có đủ các testcase đang chọn — sửa/bỏ chọn testcase hoặc xoá bên Khu vực 3 thì nút sáng lại"}
                      className={`${primaryBtn} ml-auto`}>
                      <Check size={15} /> Chấp nhận &amp; thêm vào bộ testcase
                      {missingProposals.length > 0 && ` (${missingProposals.length})`}
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
                    <button onClick={saveAndDownloadStarter} disabled={busy !== null || !examId.trim()} className={ghostBtn}
                      title="Lưu khung vào bộ testcase (phát cho SV ở trang Kho đề) và tải .zip về máy">
                      {busy === "starter-save" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                      Lưu &amp; tải khung (.zip)
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

              {/* Trình soạn thảo kiểu IDE: cây file bên trái, code LUÔN hiện bên phải. Kiểu xếp
                  gấp cũ chỉ thấy tên file nên phải bấm từng cái mới biết AI viết gì.
                  (Danh sách "Bỏ hàm/Bỏ thuộc tính…" không hiện nữa — code sinh ra mới là thứ cần
                  nhìn, còn thành phần bị loại thì thêm tay ngay trong khung nhanh hơn đọc cảnh báo.) */}
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
                            setStarterEdited(true);
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

              {starterFiles.length > 0 && (
                <div className="mt-3 rounded-xl border border-slate-200 bg-slate-50 p-3">
                  {/* Chỉ giữ CẢNH BÁO mất code vừa gõ tay — phần hướng dẫn cách dùng đã tự hiện
                      qua ô nhập và nút bên dưới. */}
                  {starterEdited && (
                    <p className="mb-2 text-[11px] font-semibold leading-relaxed text-amber-700">
                      Bạn đang có sửa tay chưa lưu — lượt AI sửa sẽ sinh lại toàn bộ file và thay phần đó.
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <input
                      value={starterPrompt}
                      onChange={(e) => setStarterPrompt(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); reviseStarter(); } }}
                      placeholder="VD: thêm màn hình chi tiết, bỏ lớp repository, đổi User.phone sang String?…"
                      className={`${inputClass} min-w-[240px] flex-1`}
                    />
                    <button onClick={reviseStarter} disabled={busy !== null || !starterSpec} className={ghostBtn}
                      title={starterSpec ? undefined : "Hãy bấm Sinh khung starter trước"}>
                      {busy === "starter-revise" ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />}
                      Nhờ AI sửa khung
                    </button>
                  </div>
                </div>
              )}
            </Step>
          )}

          {/* Đề phát cho SV */}
          {!!deBai && (
            <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-4">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-slate-700">Đề cho sinh viên</p>
              </div>
              <button onClick={saveHandout} disabled={busy !== null || !examId.trim()} className={primaryBtn}>
                {busy === "handout" ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
                Lưu &amp; tải đề (.docx)
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
/** Nút nhỏ trên thanh công cụ của từng hình minh họa. */
const miniBtn =
  "inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50";

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-bold uppercase tracking-wider text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[10px] text-slate-400">{hint}</span>}
    </label>
  );
}

/**
 * Một bước của trợ lý — THU GỌN ĐƯỢC.
 *
 * <p>Cả năm bước mở cùng lúc thì trang dài mấy màn hình: xong bước 2 vẫn phải cuộn qua nguyên đề
 * bài mới tới được bước 3. Bước nào đã chốt (done) thì tự gập lại, bấm tiêu đề là mở lại.
 */
function Step({ index, icon: Icon, title, done, children }: {
  index: number; icon: React.ComponentType<{ size?: number; className?: string }>;
  title: string; done: boolean; children: React.ReactNode;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const wasDone = useRef(done);
  useEffect(() => {
    // Chỉ gập ở ĐÚNG lúc bước chuyển sang xong, không gập lại mỗi lần render — người dùng mở ra
    // xem lại thì phải giữ nguyên trạng thái họ chọn.
    if (done && !wasDone.current) setCollapsed(true);
    wasDone.current = done;
  }, [done]);

  return (
    <div className="rounded-2xl border border-slate-200 p-4">
      <button type="button" onClick={() => setCollapsed((v) => !v)}
        aria-expanded={!collapsed}
        className={`flex w-full items-center gap-2 text-left ${collapsed ? "" : "mb-3"}`}>
        <span className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold ${
          done ? "bg-emerald-100 text-emerald-700" : "bg-indigo-100 text-indigo-700"}`}>
          {done ? <Check size={13} /> : index}
        </span>
        <Icon size={15} className="shrink-0 text-indigo-500" />
        <h3 className="text-sm font-bold text-slate-800">{title}</h3>
        <ChevronDown size={16}
          className={`ml-auto shrink-0 text-slate-400 transition-transform ${collapsed ? "-rotate-90" : ""}`} />
      </button>
      {!collapsed && children}
    </div>
  );
}
