"use client";

import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import { getToken } from "@/lib/auth";
import {
  AlertCircle, CheckCircle2, ChevronRight, Eye, GripVertical,
  Download, Layers, Loader2, Package, Plus, Save, Settings2, Trash2, UploadCloud, X,
} from "lucide-react";

type JsonMap = Record<string, unknown>;

interface Template {
  template_id: string;
  template_version: string;
  engine_type?: string;
  runner?: string;
  skill_code: string;
  skill_name?: string;
  category?: string;
  category_label?: string;
  layer: string;
  name: string;
  description: string;
  difficulty: string;
  weight_default: number;
  parameters_schema: JsonMap;
  expected_template: string;
}

interface TestcaseItem {
  instance_id: string;
  template_id: string;
  template_version: string;
  skill_code: string;
  layer: string;
  name: string;
  description: string;
  difficulty: string;
  enabled: boolean;
  order: number;
  weight: number;
  parameters: JsonMap;
  expected: string;
  expected_custom?: boolean;
  group_id?: string;
  group_name?: string;
  created_by?: string;
  created_at?: string;
}

const DIFF_LABEL: Record<string, string> = {
  basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao",
};

const LAYER_LABEL: Record<string, string> = {
  CONTRACT: "Hợp đồng API", MODEL: "Mô hình dữ liệu", REPOSITORY: "Truy cập dữ liệu", VIEWMODEL: "Trạng thái & xử lý",
  SCREEN: "Giao diện màn hình", BLACKBOX: "Chức năng người dùng", RESPONSIVE: "Tương thích kích thước",
};

const RUNNER_LABEL: Record<string, string> = {
  APP_BOOT: "Mở ứng dụng",
  WIDGET_VISIBLE: "Kiểm tra thành phần hiển thị",
  FORM_REQUIRED_FIELDS: "Kiểm tra ô bắt buộc",
  RESPONSIVE_NO_OVERFLOW: "Kiểm tra giao diện không bị tràn",
  RESPONSIVE_TARGET: "Kiểm tra giao diện ở nhiều hướng",
  NAVIGATION: "Kiểm tra điều hướng màn hình",
  LIST_VISIBLE: "Kiểm tra danh sách hiển thị",
  BUTTON_ACTION: "Kiểm tra thao tác nút bấm",
  WIDGET_DIMENSION: "Kiểm tra kích thước thành phần",
  WIDGET_PADDING: "Kiểm tra khoảng cách bên trong",
  WIDGET_TEXT_STYLE: "Kiểm tra kiểu chữ",
  WIDGET_GAP: "Kiểm tra khoảng cách giữa thành phần",
  WIDGET_TYPE_VISIBLE: "Kiểm tra loại thành phần",
  WIDGET_TEXT_CONTENT: "Kiểm tra nội dung chữ",
  WIDGET_ENABLED: "Kiểm tra trạng thái bật/tắt",
  FORM_VALIDATE_FIELDS: "Kiểm tra biểu mẫu báo lỗi",
  LIST_ITEM_COUNT: "Kiểm tra số lượng mục",
  DIALOG_FLOW: "Kiểm tra luồng hộp thoại",
  FORM_PREFILL: "Kiểm tra biểu mẫu tự điền",
  FORM_SUBMIT: "Kiểm tra gửi biểu mẫu",
  WIDGET_SEMANTICS_LABEL: "Kiểm tra nhãn hỗ trợ trợ năng",
  STATE_REACTIVE_FLOW: "Kiểm tra cập nhật trạng thái",
  GROUP: "Nhóm testcase",
};

const pad = (n: number) => String(n).padStart(2, "0");

function renderExpected(template: string, params: JsonMap) {
  return Object.entries(params).reduce(
    (text, [key, value]) => text.replaceAll(`{${key}}`, String(value)), template,
  );
}

function cloneParams(template: Template): JsonMap {
  return { ...(template.parameters_schema || {}) };
}

function formatParam(value: unknown) {
  if (typeof value === "object" && value !== null) return JSON.stringify(value);
  return String(value ?? "");
}

const PARAMETER_OPTIONS: Record<string, string[]> = {
  targetType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  fromType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  toType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  dimension: ["height", "width"],
  comparison: ["equals", "at_least", "at_most"],
  axis: ["vertical", "horizontal"],
  fontWeight: ["w400", "w500", "w600", "w700", "w800"],
};

const PARAMETER_LABELS: Record<string, string> = {
  widgetKey: "Mã thành phần",
  rootKey: "Mã thành phần gốc",
  fieldKeys: "Mã các ô nhập",
  submitKey: "Mã nút gửi",
  errorKeys: "Mã các lỗi cần hiển thị",
  listKey: "Mã danh sách",
  itemKeys: "Mã các mục trong danh sách",
  openKey: "Mã nút mở",
  destinationKey: "Mã màn hình đích",
  backKey: "Mã nút quay lại",
  homeKey: "Mã màn hình ban đầu",
  buttonKey: "Mã nút bấm",
  resultKey: "Mã kết quả sau thao tác",
  initialKey: "Mã trạng thái ban đầu",
  actionKey: "Mã thao tác",
  updatedKey: "Mã trạng thái sau cập nhật",
  absentKey: "Mã thành phần phải biến mất",
  dialogKey: "Mã hộp thoại",
  decisionKey: "Mã lựa chọn xác nhận",
  targetKey: "Mã thành phần cần kiểm tra",
  targetType: "Loại target",
  dimension: "Chiều đo",
  expected: "Giá trị mong đợi",
  comparison: "Cách so sánh",
  tolerance: "Sai số",
  fromKey: "Key bắt đầu",
  fromType: "Loại bắt đầu",
  toKey: "Key kết thúc",
  toType: "Loại kết thúc",
  axis: "Trục khoảng cách",
  expectedGap: "Khoảng cách mong đợi",
  fontSize: "Cỡ chữ",
  fontWeight: "Độ đậm",
  matchMode: "Cách so khớp",
  expectedEnabled: "Trạng thái bật/tắt mong muốn",
  fieldType: "Loại ô nhập",
  invalidValues: "Dữ liệu không hợp lệ",
  values: "Dữ liệu hợp lệ",
  portraitWidth: "Chiều rộng màn hình dọc",
  portraitHeight: "Chiều cao màn hình dọc",
  landscapeWidth: "Chiều rộng màn hình ngang",
  landscapeHeight: "Chiều cao màn hình ngang",
};

export default function TestcasesPage() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [examId, setExamId] = useState("");
  const [examName, setExamName] = useState("");
  const [teacherNote, setTeacherNote] = useState("");
  const [items, setItems] = useState<TestcaseItem[]>([]);
  const [status, setStatus] = useState("");
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<"draft" | "publish" | null>(null);
  const [message, setMessage] = useState<{ type: "ok" | "error"; text: string } | null>(null);
  const [selectedCategory, setSelectedCategory] = useState("");
  const [search, setSearch] = useState("");
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draggedTemplateId, setDraggedTemplateId] = useState<string | null>(null);
  const [draggedItemId, setDraggedItemId] = useState<string | null>(null);
  const [selectedItemIds, setSelectedItemIds] = useState<string[]>([]);
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [groupDetailsOpen, setGroupDetailsOpen] = useState(false);
  const [clearAllModalOpen, setClearAllModalOpen] = useState(false);
  const [groupNameDraft, setGroupNameDraft] = useState("");
  const [groupModalError, setGroupModalError] = useState("");
  const [examIdCheck, setExamIdCheck] = useState<"idle" | "checking" | "available" | "exists" | "error">("idle");

  useEffect(() => {
    const normalized = examId.trim();
    if (!normalized) {
      setExamIdCheck("idle");
      return;
    }
    if (!/^[A-Z0-9_-]+$/.test(normalized)) {
      setExamIdCheck("error");
      return;
    }
    const controller = new AbortController();
    setExamIdCheck("checking");
    const timer = window.setTimeout(() => {
      fetch(`${API_BASE}/exam-setup/status/${encodeURIComponent(normalized)}`, { signal: controller.signal })
        .then((response) => {
          if (response.status === 404) setExamIdCheck("available");
          else if (response.ok) setExamIdCheck("exists");
          else setExamIdCheck("error");
        })
        .catch((error: unknown) => {
          if (error instanceof DOMException && error.name === "AbortError") return;
          setExamIdCheck("error");
        });
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [examId]);

  useEffect(() => {
    fetch(`${API_BASE}/testcase-templates`)
      .then((r) => r.ok ? r.json() : [])
      .then((templateRows) => {
        const loadedTemplates = Array.isArray(templateRows) ? templateRows as Template[] : [];
        setTemplates(loadedTemplates);
        if (loadedTemplates.length) setSelectedCategory(String(loadedTemplates[0].category || ""));
      })
      .catch(() => setMessage({ type: "error", text: "Không tải được thư viện testcase." }))
      .finally(() => setLoading(false));
  }, []);

  const categories = useMemo(() => {
    const map = new Map<string, { code: string; label: string; count: number }>();
    templates.forEach((t) => {
      const code = t.category || "OTHER";
      const current = map.get(code) || { code, label: t.category_label || code, count: 0 };
      current.count += 1;
      map.set(code, current);
    });
    return [...map.values()];
  }, [templates]);

  const visibleTemplates = useMemo(() => templates.filter((t) => {
    const categoryMatch = !selectedCategory || (t.category || "OTHER") === selectedCategory;
    const query = search.trim().toLowerCase();
    const searchMatch = !query || [t.name, t.description, t.skill_code, t.layer]
      .some((value) => value.toLowerCase().includes(query));
    return categoryMatch && searchMatch;
  }), [templates, selectedCategory, search]);

  const selectedTemplate = templates.find((t) => t.template_id === selectedTemplateId) || null;
  const editingItem = items.find((item) => item.instance_id === editingId) || null;
  const templateMap = useMemo(() => new Map(templates.map((t) => [t.template_id, t])), [templates]);
  const totalWeight = items.reduce((sum, item) => item.enabled ? sum + Number(item.weight || 0) : sum, 0);
  const groupSummaries = useMemo(() => {
    const map = new Map<string, { name: string; count: number; weight: number }>();
    items.forEach((item) => {
      if (!item.group_id) return;
      const current = map.get(item.group_id) || { name: item.group_name || item.group_id, count: 0, weight: 0 };
      current.count += 1;
      if (item.enabled) current.weight += Number(item.weight || 0);
      map.set(item.group_id, current);
    });
    return map;
  }, [items]);
  const selectedGroupItems = useMemo(
    () => items.filter((item) => selectedItemIds.includes(item.instance_id)),
    [items, selectedItemIds],
  );

  const addTemplate = (templateId: string) => {
    const template = templateMap.get(templateId);
    if (!template) return;
    const activeEngine = items.length ? templateMap.get(items[0].template_id)?.engine_type : undefined;
    if (activeEngine && template.engine_type && activeEngine !== template.engine_type) {
      setMessage({ type: "error", text: "Không thể trộn testcase chung với profile layered trong cùng một đề." });
      return;
    }
    if (template.engine_type !== "COMMON_V1" && items.some((item) => item.template_id === templateId)) {
      setMessage({ type: "error", text: `Testcase ${templateId} đã có trong đề; layered grader chỉ chạy mỗi rubric một lần.` });
      return;
    }
    const usedIds = new Set(items.map((item) => item.instance_id));
    let nextNumber = items.length + 1;
    while (usedIds.has(`${examId.trim() || "exam"}_item_${pad(nextNumber)}`)) nextNumber += 1;
    const item: TestcaseItem = {
      instance_id: `${examId.trim() || "exam"}_item_${pad(nextNumber)}`,
      template_id: template.template_id,
      template_version: template.template_version,
      skill_code: template.skill_code,
      layer: template.layer,
      name: template.name,
      description: template.description,
      difficulty: template.difficulty,
      enabled: true,
      order: items.length + 1,
      weight: Number(template.weight_default || 1),
      parameters: cloneParams(template),
      expected: renderExpected(template.expected_template, cloneParams(template)),
      expected_custom: false,
    };
    setItems((current) => [...current, item]);
    setEditingId(item.instance_id);
    setSelectedTemplateId(template.template_id);
    setMessage(null);
  };

  const updateItem = (instanceId: string, patch: Partial<TestcaseItem>) => {
    setItems((current) => current.map((item) => item.instance_id === instanceId ? { ...item, ...patch } : item));
  };

  const updateParameter = (item: TestcaseItem, key: string, value: string) => {
    const template = templateMap.get(item.template_id);
    if (!template) return;
    const original = template.parameters_schema[key];
    let parsed: unknown = value;
    if (typeof original === "number") parsed = value === "" ? 0 : Number(value);
    if (typeof original === "boolean") parsed = value === "true";
    const parameters = { ...item.parameters, [key]: parsed };
    updateItem(item.instance_id, {
      parameters,
      // Chỉ tự sinh lại expected khi giáo viên chưa nhập nội dung riêng.
      expected: item.expected_custom
        ? item.expected
        : renderExpected(template.expected_template, parameters),
    });
  };

  const clearAllItems = () => {
    if (!items.length) return;
    setClearAllModalOpen(true);
  };

  const confirmClearAllItems = () => {
    setItems([]);
    setSelectedItemIds([]);
    setEditingId(null);
    setGroupModalOpen(false);
    setGroupDetailsOpen(false);
    setClearAllModalOpen(false);
    setMessage({ type: "ok", text: "Đã xóa toàn bộ testcase khỏi đề." });
  };

  const toggleItemSelection = (instanceId: string) => {
    setSelectedItemIds((current) => current.includes(instanceId)
      ? current.filter((id) => id !== instanceId)
      : [...current, instanceId]);
  };

  const openGroupModal = () => {
    const selected = items.filter((item) => selectedItemIds.includes(item.instance_id));
    if (selected.length < 2) {
      setMessage({ type: "error", text: "Hãy chọn ít nhất 2 testcase nhỏ để gộp thành một testcase lớn." });
      return;
    }
    if (selected.some((item) => item.group_id)) {
      setMessage({ type: "error", text: "Testcase đã thuộc một nhóm. Hãy tách nhóm cũ trước khi gộp lại." });
      return;
    }
    if (selected.some((item) => templateMap.get(item.template_id)?.engine_type !== "COMMON_V1")) {
      setMessage({ type: "error", text: "Chỉ có thể gộp các testcase dùng chung trong cùng một nhóm." });
      return;
    }
    const defaultName = `Nhóm kiểm tra ${String(items.filter((item) => item.group_id).length + 1).padStart(2, "0")}`;
    setGroupNameDraft(defaultName);
    setGroupModalError("");
    setGroupModalOpen(true);
  };

  const confirmGroup = () => {
    const selected = items.filter((item) => selectedItemIds.includes(item.instance_id));
    const groupName = groupNameDraft.trim();
    if (selected.length < 2) {
      setGroupModalError("Cần ít nhất 2 testcase nhỏ trong nhóm.");
      return;
    }
    if (!groupName) {
      setGroupModalError("Vui lòng nhập tên testcase lớn.");
      return;
    }
    if (groupName.length > 120) {
      setGroupModalError("Tên nhóm không được vượt quá 120 ký tự.");
      return;
    }
    const usedGroupIds = new Set(items.map((item) => item.group_id).filter(Boolean));
    let groupNumber = 1;
    let groupId = `${examId.trim() || "exam"}_group_${pad(groupNumber)}`;
    while (usedGroupIds.has(groupId)) {
      groupNumber += 1;
      groupId = `${examId.trim() || "exam"}_group_${pad(groupNumber)}`;
    }
    const selectedIds = new Set(selectedItemIds);
    setItems((current) => current.map((item) => selectedIds.has(item.instance_id)
      ? { ...item, group_id: groupId, group_name: groupName }
      : item));
    setSelectedItemIds([]);
    setGroupModalOpen(false);
    setMessage({ type: "ok", text: `Tạo nhóm testcase "${groupName}" thành công.` });
  };

  const ungroupItems = (groupId: string) => {
    setItems((current) => current.map((item) => item.group_id === groupId
      ? { ...item, group_id: undefined, group_name: undefined }
      : item));
    setSelectedItemIds((current) => current.filter((id) => !items.some((item) => item.instance_id === id && item.group_id === groupId)));
  };

  const deleteGroup = (groupId: string) => {
    const group = groupSummaries.get(groupId);
    setItems((current) => current.map((item) => item.group_id === groupId
      ? { ...item, group_id: undefined, group_name: undefined }
      : item));
    setSelectedItemIds((current) => current.filter((id) => !items.some((item) => item.instance_id === id && item.group_id === groupId)));
    setMessage({ type: "ok", text: `Đã xóa nhóm "${group?.name || groupId}"; các testcase con vẫn được giữ lại.` });
  };

  const reorderItem = (targetId: string) => {
    if (!draggedItemId || draggedItemId === targetId) return;
    setItems((current) => {
      const sourceIndex = current.findIndex((item) => item.instance_id === draggedItemId);
      const targetIndex = current.findIndex((item) => item.instance_id === targetId);
      if (sourceIndex < 0 || targetIndex < 0) return current;
      const next = [...current];
      const [source] = next.splice(sourceIndex, 1);
      next.splice(targetIndex, 0, source);
      return next.map((item, index) => ({ ...item, order: index + 1 }));
    });
    setDraggedItemId(null);
  };

  const save = async (kind: "draft" | "publish") => {
    if (!examId.trim()) {
      setMessage({ type: "error", text: "Vui lòng nhập mã đề mới trước khi lưu." });
      return;
    }
    if (examIdCheck !== "available") {
      setMessage({ type: "error", text: examIdCheck === "exists"
        ? "Mã đề đã tồn tại. Vui lòng nhập một mã đề mới."
        : "Vui lòng chờ kiểm tra mã đề hoàn tất." });
      return;
    }
    if (!examName.trim()) {
      setMessage({ type: "error", text: "Vui lòng nhập tên đề thi trước khi lưu." });
      return;
    }
    setSaving(kind);
    setMessage(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/testcases/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken() ?? ""}` },
        body: JSON.stringify({ exam_name: examName.trim(), teacher_note: teacherNote.trim(), items }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được cấu hình testcase");
      setStatus(data.status || (kind === "publish" ? "PUBLISHED" : "DRAFT"));
      setVersion(Number(data.version ?? version));
      setItems(Array.isArray(data.items) ? data.items as TestcaseItem[] : items);
      setMessage({ type: "ok", text: data.warning || (kind === "publish"
        ? `Đã tạo và Publish bộ code testcase v${data.version}.`
        : `Đã tạo bộ code testcase Draft v${data.version}.` ) });
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không lưu được cấu hình testcase" });
    } finally {
      setSaving(null);
    }
  };

  const downloadTestcase = async () => {
    if (!examId.trim()) return;
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/download/exam-test`, {
        headers: { Authorization: `Bearer ${getToken() ?? ""}` },
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || "Không tải được gói testcase");
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${examId.trim()}_testcase.zip`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không tải được gói testcase" });
    }
  };

  return (
    <SidebarLayout
      title="Tạo testcase từ template"
      subtitle="Kéo-thả testcase chung theo semantic key → dùng lại cho nhiều đề Flutter"
      activePath="/teacher/testcases"
    >
      <div className="space-y-5">
        <div className="card flex flex-wrap items-end gap-4 p-4">
          <div className="min-w-[220px] flex-1">
            <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Mã đề mới</label>
            <input
              value={examId}
              onChange={(e) => setExamId(e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, ""))}
              placeholder="VD: FLUTTER_PE_30 — chưa tồn tại"
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2.5 font-mono text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
            {examIdCheck === "checking" && <p className="mt-1.5 text-[11px] text-slate-400">Đang kiểm tra mã đề…</p>}
            {examIdCheck === "available" && <p className="mt-1.5 text-[11px] font-semibold text-emerald-600">Mã đề chưa tồn tại, có thể tạo.</p>}
            {examIdCheck === "exists" && <p className="mt-1.5 text-[11px] font-semibold text-rose-600">Mã đề đã tồn tại, hãy chọn mã khác.</p>}
            {examIdCheck === "error" && <p className="mt-1.5 text-[11px] font-semibold text-amber-600">Không kiểm tra được mã đề. Vui lòng thử lại.</p>}
          </div>
          <div className="min-w-[260px] flex-[1.4]">
            <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Tên đề thi</label>
            <input
              value={examName}
              onChange={(e) => setExamName(e.target.value)}
              placeholder="VD: Flutter Practical Exam — Responsive UI"
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2.5 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </div>
          <div className="min-w-[260px] flex-[1.2]">
            <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Ghi chú <span className="font-normal normal-case text-slate-400">(tuỳ chọn)</span></label>
            <input
              value={teacherNote}
              onChange={(e) => setTeacherNote(e.target.value)}
              placeholder="Mô tả ngắn cho giáo viên"
              className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2.5 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
          </div>
          <div className="flex items-center gap-2 pb-0.5 text-xs text-slate-500">
            {version > 0 ? <>
              <span className={`rounded-full px-2.5 py-1 font-bold ${status === "PUBLISHED" ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>{status}</span>
              <span>version {version}</span>
            </> : <span className="text-slate-400">Chưa lưu</span>}
          </div>
          <div className="ml-auto flex gap-2">
            <button onClick={downloadTestcase} disabled={!examId.trim() || !items.length || !!saving} className="flex items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 px-3.5 py-2.5 text-sm font-semibold text-indigo-700 hover:bg-indigo-100 disabled:cursor-not-allowed disabled:opacity-50">
              <Download size={16} /> Tải ZIP code
            </button>
            <button onClick={() => save("draft")} disabled={!!saving || examIdCheck !== "available" || !examName.trim()} className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "draft" ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} Lưu Draft
            </button>
            <button onClick={() => save("publish")} disabled={!!saving || examIdCheck !== "available" || !examName.trim()} className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "publish" ? <Loader2 size={16} className="animate-spin" /> : <UploadCloud size={16} />} Publish snapshot
            </button>
          </div>
        </div>

        {message && (
          <div className={`flex items-start gap-2 rounded-xl border px-4 py-3 text-sm ${message.type === "ok" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-rose-200 bg-rose-50 text-rose-700"}`}>
            {message.type === "ok" ? <CheckCircle2 size={17} className="mt-0.5 shrink-0" /> : <AlertCircle size={17} className="mt-0.5 shrink-0" />}
            <span>{message.text}</span>
            <button className="ml-auto" onClick={() => setMessage(null)}><X size={15} /></button>
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[280px_minmax(360px,1fr)_minmax(360px,1fr)]">
          {/* Khu vực 1: khung kiến thức */}
          <section className="card overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <p className="eyebrow">Khu vực 1</p>
              <h2 className="mt-1 text-sm font-bold text-slate-800">Khung kiến thức</h2>
            </div>
            <div className="p-2">
              {loading ? <div className="p-5 text-center text-sm text-slate-400"><Loader2 className="mx-auto animate-spin" size={20} /></div> : categories.map((category) => (
                <button
                  key={category.code}
                  onClick={() => setSelectedCategory(category.code)}
                  className={`mb-1 flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-left text-sm transition-colors ${selectedCategory === category.code ? "bg-indigo-50 font-semibold text-indigo-700 ring-1 ring-indigo-100" : "text-slate-600 hover:bg-slate-50"}`}
                >
                  <Layers size={15} className="shrink-0" />
                  <span className="min-w-0 flex-1 break-words whitespace-normal leading-5" title={category.label}>{category.label}</span>
                  <span className="rounded-full bg-slate-100 px-1.5 text-[10px] text-slate-500">{category.count}</span>
                  <ChevronRight size={13} className="shrink-0 text-slate-300" />
                </button>
              ))}
              {!loading && categories.length === 0 && <p className="p-4 text-center text-xs text-slate-400">Chưa có template.</p>}
            </div>
            {groupSummaries.size > 0 && <div className="border-t border-slate-100 p-3"><div className="flex items-center justify-between gap-2"><p className="text-xs font-bold text-indigo-800">Các nhóm testcase</p><span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[10px] font-semibold text-indigo-600">{groupSummaries.size} nhóm</span></div><div className="mt-2 space-y-2">{Array.from(groupSummaries.entries()).map(([groupId, group]) => <div key={groupId} className="rounded-lg border border-indigo-100 bg-indigo-50/50 px-3 py-2"><div className="flex items-start justify-between gap-2"><div className="min-w-0"><p className="truncate text-xs font-bold text-slate-700">{group.name}</p><p className="mt-0.5 truncate font-mono text-[10px] text-slate-400">{groupId}</p></div><button onClick={() => deleteGroup(groupId)} className="flex shrink-0 items-center gap-1 rounded-lg px-1.5 py-1 text-[10px] font-semibold text-rose-600 hover:bg-rose-50" title="Xóa nhóm, giữ testcase con"><Trash2 size={12} /> Xóa</button></div><p className="mt-1 text-[10px] text-slate-500">{group.count} testcase con · {group.weight.toFixed(2)} điểm</p></div>)}</div></div>}
          </section>

          {/* Khu vực 2: thư viện template */}
          <section className="card min-w-0 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex items-center justify-between gap-3">
                <div><p className="eyebrow">Khu vực 2</p><h2 className="mt-1 text-sm font-bold text-slate-800">Testcase có sẵn</h2></div>
                <span className="text-xs text-slate-400">{visibleTemplates.length} template</span>
              </div>
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Tìm theo tên, skill, layer..." className="mt-3 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
            </div>
            <div
              className="custom-scrollbar max-h-[calc(100vh-295px)] min-h-[360px] space-y-2 overflow-y-auto p-3"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => { e.preventDefault(); if (draggedTemplateId) addTemplate(draggedTemplateId); setDraggedTemplateId(null); }}
            >
              {visibleTemplates.map((template) => (
                <div
                  key={template.template_id}
                  draggable
                  onDragStart={() => { setDraggedTemplateId(template.template_id); setSelectedTemplateId(template.template_id); }}
                  onClick={() => setSelectedTemplateId(template.template_id)}
                  className={`group cursor-grab rounded-xl border p-3 transition-all active:cursor-grabbing ${selectedTemplateId === template.template_id ? "border-indigo-300 bg-indigo-50/50 shadow-sm" : "border-slate-200 bg-white hover:border-indigo-200 hover:shadow-sm"}`}
                >
                  <div className="flex items-start gap-2">
                    <GripVertical size={16} className="mt-0.5 shrink-0 text-slate-300" />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <h3 className="text-sm font-semibold text-slate-800">{template.name}</h3>
                        {template.engine_type === "COMMON_V1" && <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-bold text-emerald-700">Dùng chung</span>}
                        <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[template.layer] || template.layer}</span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[template.difficulty] || template.difficulty}</span>
                      </div>
                      <p className="mt-1 line-clamp-3 text-xs leading-relaxed text-slate-500">{template.description}</p>
                      <div className="mt-2 flex items-center justify-between gap-2">
                        <span className="truncate font-mono text-[10px] text-indigo-500">{template.skill_code}</span>
                        <span className="shrink-0 text-[11px] font-semibold text-slate-500">{template.weight_default} điểm mặc định</span>
                      </div>
                    </div>
                  </div>
                  <div className="mt-2 flex justify-end gap-1 border-t border-slate-100 pt-2">
                    <button onClick={(e) => { e.stopPropagation(); setSelectedTemplateId(template.template_id); }} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100 hover:text-slate-700"><Eye size={13} /> Xem chi tiết</button>
                    <button onClick={(e) => { e.stopPropagation(); addTemplate(template.template_id); }} className="flex items-center gap-1 rounded-md bg-indigo-600 px-2 py-1 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={13} /> Thêm vào đề</button>
                  </div>
                </div>
              ))}
              {!loading && visibleTemplates.length === 0 && <div className="p-8 text-center text-sm text-slate-400">Không có testcase phù hợp.</div>}
            </div>
            {selectedTemplate && (
              <div className="border-t border-slate-100 bg-slate-50/60 p-4">
                <div className="mb-1 flex items-center justify-between gap-2"><p className="eyebrow">Chi tiết template</p><button onClick={() => setSelectedTemplateId(null)}><X size={15} className="text-slate-400" /></button></div>
                <p className="text-sm font-bold text-slate-800">{selectedTemplate.name}</p>
                <p className="mt-1 text-xs leading-relaxed text-slate-500">{selectedTemplate.description}</p>
                {(selectedTemplate.runner || selectedTemplate.layer) && <p className="mt-2 text-[11px] text-emerald-700">Loại kiểm tra: {RUNNER_LABEL[selectedTemplate.runner || ""] || LAYER_LABEL[selectedTemplate.layer] || "Kiểm tra theo yêu cầu"}</p>}
                <p className="mt-2 rounded-lg bg-white p-2 text-xs text-slate-600"><span className="font-semibold text-slate-700">Expected tự sinh:</span> {renderExpected(selectedTemplate.expected_template, selectedTemplate.parameters_schema)}</p>
              </div>
            )}
          </section>

          {/* Khu vực 3: testcase instance của đề */}
          <section className="card min-w-0 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="eyebrow">Khu vực 3</p><h2 className="mt-1 text-sm font-bold text-slate-800">Testcase trong đề</h2></div><div className="flex items-center gap-2"><span className="rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700">{items.length} mục</span>{selectedItemIds.length >= 2 && <button onClick={openGroupModal} className="rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700">Gộp thành testcase lớn</button>}{items.length > 0 && <button onClick={clearAllItems} className="flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-100" title="Xóa toàn bộ testcase"><Trash2 size={13} /> Xóa tất cả</button>}</div></div>
              <div className="mt-3 flex items-center justify-between text-xs"><span className="text-slate-500">Tổng trọng số</span><strong className="text-indigo-700">{totalWeight.toFixed(2)}</strong></div>
            </div>
            <div
              className="custom-scrollbar max-h-[calc(100vh-295px)] min-h-[360px] space-y-2 overflow-y-auto p-3"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => { e.preventDefault(); if (draggedTemplateId) addTemplate(draggedTemplateId); setDraggedTemplateId(null); }}
            >
              {items.length === 0 ? (
                <div className="flex min-h-[330px] flex-col items-center justify-center rounded-xl border-2 border-dashed border-slate-200 p-8 text-center" onDragOver={(e) => e.preventDefault()}>
                  <Package size={28} className="mb-3 text-slate-300" />
                  <p className="text-sm font-semibold text-slate-600">Kéo testcase vào đây</p>
                  <p className="mt-1 text-xs text-slate-400">Hoặc bấm “Thêm vào đề” để tránh bỏ sót thao tác.</p>
                </div>
              ) : items.map((item) => (
                <div
                  key={item.instance_id}
                  draggable
                  onDragStart={() => setDraggedItemId(item.instance_id)}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => { e.preventDefault(); reorderItem(item.instance_id); }}
                  className={`rounded-xl border p-3 transition-colors ${editingId === item.instance_id ? "border-indigo-300 bg-indigo-50/40" : "border-slate-200 bg-white"} ${!item.enabled ? "opacity-60" : ""}`}
                >
                  <div className="flex items-start gap-2">
                    <GripVertical size={16} className="mt-0.5 shrink-0 cursor-grab text-slate-300" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-start justify-between gap-2"><div className="min-w-0"><p className="text-sm font-semibold text-slate-800">{item.name}</p>{item.group_id && <div className="mt-1.5 rounded-lg border border-indigo-100 bg-indigo-50/70 px-2.5 py-2 text-[10px] text-indigo-700"><p className="font-bold">Testcase lớn: {groupSummaries.get(item.group_id)?.name || item.group_name || item.group_id}</p><p className="mt-0.5">{groupSummaries.get(item.group_id)?.count || 0} testcase nhỏ · {Number(groupSummaries.get(item.group_id)?.weight || 0).toFixed(2)} điểm · một assert fail sẽ làm cả nhóm fail</p><p className="mt-0.5 font-mono text-indigo-400">{item.group_id}</p></div>}</div><span className="shrink-0 rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[10px] text-indigo-600">#{item.order}</span></div>
                      <p className="mt-1 truncate font-mono text-[10px] text-slate-400">{item.instance_id}</p>
                      <div className="mt-2 flex flex-wrap items-center gap-1.5"><span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[item.layer] || item.layer}</span><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[item.difficulty] || item.difficulty}</span><span className="text-[11px] font-semibold text-slate-500">{Number(item.weight).toFixed(2)} điểm</span></div>
                    </div>
                  </div>
                  <div className="mt-2 flex items-center justify-between border-t border-slate-100 pt-2">
                    <div className="flex items-center gap-3"><label className="flex items-center gap-1.5 text-xs text-slate-500"><input type="checkbox" checked={item.enabled} onChange={(e) => updateItem(item.instance_id, { enabled: e.target.checked })} /> Enabled</label><label className="flex items-center gap-1.5 text-xs text-indigo-600"><input type="checkbox" checked={selectedItemIds.includes(item.instance_id)} onChange={() => toggleItemSelection(item.instance_id)} /> Chọn nhóm</label></div>
                    <div className="flex gap-1">{item.group_id && <button onClick={() => ungroupItems(item.group_id!)} className="rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100">Tách nhóm</button>}<button onClick={() => setEditingId(editingId === item.instance_id ? null : item.instance_id)} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-indigo-600 hover:bg-indigo-50"><Settings2 size={13} /> Cấu hình</button><button onClick={() => { setSelectedItemIds((current) => current.filter((id) => id !== item.instance_id)); setItems((current) => current.filter((x) => x.instance_id !== item.instance_id).map((x, i) => ({ ...x, order: i + 1 }))); }} className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600"><Trash2 size={14} /></button></div>
                  </div>
                  <label className="mt-3 block border-t border-slate-100 pt-3 text-xs font-semibold text-slate-600">
                    Expected khi testcase pass
                    <textarea
                      rows={2}
                      value={item.expected}
                      onChange={(e) => updateItem(item.instance_id, { expected: e.target.value, expected_custom: true })}
                      placeholder="Mô tả kết quả mong muốn khi testcase đạt"
                      className="mt-1.5 w-full resize-y rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal leading-relaxed outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                    />
                  </label>
                  {editingId === item.instance_id && (
                    <div className="mt-3 space-y-3 border-t border-indigo-100 pt-3">
                      <div className="grid grid-cols-2 gap-2"><label className="text-xs text-slate-500">Độ khó<select value={item.difficulty} onChange={(e) => updateItem(item.instance_id, { difficulty: e.target.value })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="basic">Cơ bản</option><option value="intermediate">Trung bình</option><option value="advanced">Nâng cao</option></select></label><label className="text-xs text-slate-500">Điểm<input type="number" min="0" step="0.5" value={item.weight} onChange={(e) => updateItem(item.instance_id, { weight: Number(e.target.value) })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /></label></div>
                      <div><p className="mb-1 text-xs font-semibold text-slate-600">Thông số template</p><div className="grid grid-cols-2 gap-2">{Object.keys(item.parameters || {}).map((key) => { const template = templateMap.get(item.template_id); const schemaValue = template?.parameters_schema?.[key]; const isNumber = typeof schemaValue === "number"; const options = PARAMETER_OPTIONS[key]; return <label key={key} className="text-[11px] text-slate-500">{PARAMETER_LABELS[key] || key}{options ? <select value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs">{options.map((option) => <option key={option} value={option}>{option}</option>)}</select> : <input type={isNumber ? "number" : "text"} value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" />}</label>; })}</div></div>
                      <p className="text-[10px] text-slate-400">Expected trên sẽ được lưu vào kết quả chấm; actual chỉ xuất hiện sau khi grader chạy bài sinh viên.</p>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </section>
        </div>

        {typeof document !== "undefined" && createPortal(
          <>
            {groupModalOpen && (
              <div className="fixed inset-0 z-[60] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="group-modal-title" onClick={() => setGroupModalOpen(false)}>
                <div className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex items-start gap-3"><div className="rounded-xl bg-indigo-100 p-2 text-indigo-600"><Layers size={20} /></div><div><h3 id="group-modal-title" className="text-base font-bold text-slate-800">Đặt tên nhóm testcase</h3><p className="mt-1 text-xs leading-relaxed text-slate-500">Nhóm sẽ chạy các testcase con cùng nhau và chỉ pass khi tất cả assert đều đạt.</p></div></div>
                    <button onClick={() => setGroupModalOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={17} /></button>
                  </div>
                  <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50 p-3"><div className="flex items-center justify-between gap-2"><p className="text-xs font-bold text-slate-700">Testcase con trong nhóm</p>{selectedGroupItems.length > 4 && <button onClick={() => { setGroupModalOpen(false); setGroupDetailsOpen(true); }} className="text-[11px] font-bold text-indigo-600 hover:text-indigo-800">Xem toàn bộ ({selectedGroupItems.length})</button>}</div><div className="mt-2 space-y-2">{selectedGroupItems.slice(0, 4).map((item) => { const template = templateMap.get(item.template_id); return <div key={item.instance_id} className="flex items-start justify-between gap-3 rounded-lg bg-white px-3 py-2"><div className="min-w-0"><p className="truncate text-xs font-semibold text-slate-700">{item.name}</p><p className="mt-0.5 truncate font-mono text-[10px] text-slate-400">{item.instance_id}</p></div><span className="shrink-0 rounded-md bg-indigo-50 px-1.5 py-1 text-[10px] font-semibold text-indigo-600">{RUNNER_LABEL[template?.runner || ""] || LAYER_LABEL[template?.layer || ""] || "Kiểm tra theo yêu cầu"}</span></div>; })}</div></div>
                  <label className="mt-4 block text-xs font-semibold text-slate-600">Tên testcase lớn<input autoFocus value={groupNameDraft} onChange={(e) => { setGroupNameDraft(e.target.value); setGroupModalError(""); }} onKeyDown={(e) => { if (e.key === "Enter") confirmGroup(); }} placeholder="VD: Luồng tạo mới hợp lệ" className="mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" /></label>
                  {groupModalError && <p className="mt-2 text-xs font-semibold text-rose-600">{groupModalError}</p>}
                  <div className="mt-5 flex justify-end gap-2"><button onClick={() => setGroupModalOpen(false)} className="rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">Hủy</button><button onClick={confirmGroup} className="rounded-xl bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700">Tạo nhóm</button></div>
                </div>
              </div>
            )}
            {groupDetailsOpen && (
              <div className="fixed inset-0 z-[70] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-0 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="group-details-title" onClick={() => { setGroupDetailsOpen(false); setGroupModalOpen(true); }}>
                <div className="flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-5 py-4">
                    <div className="flex min-w-0 items-center gap-3"><div className="rounded-xl bg-indigo-100 p-2 text-indigo-600"><Layers size={20} /></div><div className="min-w-0"><p className="eyebrow">Chi tiết nhóm testcase</p><h2 id="group-details-title" className="truncate text-lg font-bold text-slate-800">{groupNameDraft || "Nhóm testcase"}</h2></div></div><button onClick={() => { setGroupDetailsOpen(false); setGroupModalOpen(true); }} className="flex shrink-0 items-center gap-2 rounded-xl border border-slate-200 px-3 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50"><X size={16} /> Quay lại</button>
                  </header>
                  <main className="custom-scrollbar min-h-0 space-y-4 overflow-y-auto bg-slate-50 p-5"><div className="rounded-2xl border border-indigo-100 bg-indigo-50/70 p-4 text-sm text-indigo-800"><span className="font-bold">{selectedGroupItems.length} testcase con</span> · Tất cả phải đạt thì nhóm mới pass. Danh sách dưới đây hiển thị đầy đủ loại runner, mã instance và expected của từng testcase.</div><div className="space-y-3">{selectedGroupItems.map((item, index) => { const template = templateMap.get(item.template_id); return <article key={item.instance_id} className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"><div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="rounded-md bg-indigo-100 px-2 py-1 text-[11px] font-bold text-indigo-700">#{index + 1}</span><h3 className="text-sm font-bold text-slate-800">{item.name}</h3></div><p className="mt-1 font-mono text-[11px] text-slate-400">{item.instance_id}</p></div><span className="rounded-lg bg-indigo-50 px-2.5 py-1.5 font-mono text-[11px] font-bold text-indigo-700">{template?.runner || item.template_id}</span></div><p className="mt-3 text-xs leading-relaxed text-slate-600">{item.description}</p><p className="mt-3 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600"><span className="font-semibold text-slate-700">Expected:</span> {item.expected}</p></article>; })}</div></main>
                </div>
              </div>
            )}
            {clearAllModalOpen && (
              <div className="fixed inset-0 z-[80] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="clear-all-modal-title" onClick={() => setClearAllModalOpen(false)}>
                <div className="w-full max-w-md overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-start justify-between gap-4 p-5">
                    <div className="flex items-start gap-3"><div className="rounded-xl bg-rose-100 p-2 text-rose-600"><Trash2 size={20} /></div><div><h3 id="clear-all-modal-title" className="text-base font-bold text-slate-800">Xóa toàn bộ testcase?</h3><p className="mt-1 text-xs leading-relaxed text-slate-500">Bạn sắp xóa {items.length} testcase khỏi đề hiện tại.</p></div></div>
                    <button onClick={() => setClearAllModalOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={17} /></button>
                  </div>
                  <div className="mx-5 rounded-xl border border-rose-100 bg-rose-50 p-3 text-xs leading-relaxed text-rose-700">Thao tác này chỉ xóa danh sách đang chỉnh sửa và chưa ghi đè dữ liệu cho đến khi bạn bấm Lưu Draft hoặc Publish. Bạn có muốn tiếp tục không?</div>
                  <div className="flex justify-end gap-2 p-5"><button onClick={() => setClearAllModalOpen(false)} className="rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">Hủy</button><button onClick={confirmClearAllItems} className="flex items-center gap-1.5 rounded-xl bg-rose-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-rose-700"><Trash2 size={15} /> Xóa tất cả</button></div>
                </div>
              </div>
            )}
          </>,
          document.body,
        )}
      </div>
    </SidebarLayout>
  );
}
