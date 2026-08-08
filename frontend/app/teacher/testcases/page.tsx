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
type EngineMode = "TODO_USER_V12" | "COMMON_V1";

interface SkillOption {
  code: string;
  name?: string;
  deprecated?: boolean;
  testable?: string;
}

interface Template {
  template_id: string;
  template_version: string;
  engine_type?: string;
  execution_key?: string;
  fixed_contract?: boolean;
  runner?: string;
  skill_code: string;
  skill_name?: string;
  category?: string;
  category_label?: string;
  testcase_group?: string;
  testcase_group_label?: string;
  layer: string;
  name: string;
  description: string;
  difficulty: string;
  weight_default: number;
  parameters_schema: JsonMap;
  expected_template: string;
  custom?: boolean;
  created_by?: string;
  created_at?: string;
}

interface TestcasePackScope {
  scope_id: "LOGIC" | "WIDGET" | "BEHAVIOR";
  name: string;
  description: string;
  template_ids: string[];
  testcase_count: number;
}

interface TestcasePack {
  pack_id: string;
  pack_version: string;
  engine_type: EngineMode;
  profile_id: string;
  name: string;
  description: string;
  starter_file: string;
  testcase_count: number;
  default_weight: number;
  template_ids: string[];
  scopes: TestcasePackScope[];
}

interface TestcaseItem {
  instance_id: string;
  template_id: string;
  template_version: string;
  engine_type?: string;
  execution_key?: string;
  skill_code: string;
  layer: string;
  testcase_group?: string;
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
  setup_steps?: SetupStep[];
}

interface SetupStep {
  type: "tap" | "enter_text" | "expect_visible" | "expect_absent" | "wait_for_visible";
  key: string;
  value?: string;
  timeout_ms?: number;
}
type SourceContractType = "model" | "repository" | "provider" | "screen" | "helper" | "service";
interface SourceContract { type: SourceContractType; path: string; symbols: string; }
interface PersistenceConfig { enabled: boolean; storage_kind: "none" | "sqlite" | "api" | "shared_preferences"; reload_key: string; notes: string; reset_steps: SetupStep[]; }
interface GoldenConfig { enabled: boolean; portrait_asset: string; landscape_asset: string; threshold: number; }

interface SuiteConfig {
  suite_version: number;
  name: string;
  context: string;
  fixture_name: string;
  fixture_description: string;
  strict_semantic_keys: boolean;
  ready_key: string;
  required_keys: string;
  boot_timeout_ms: number;
  step_timeout_ms: number;
  setup_steps: SetupStep[];
  profile: "COMMON_UI" | "FLUTTER_LAYERED" | "PERSISTENCE" | "REPOSITORY_SQLITE" | "GOLDEN_RESPONSIVE" | "TODO_STARTER_V12";
  reset_strategy: "APP_RESTART" | "FIXTURE_STEPS" | "CLEAR_STORAGE" | "PERSISTENCE_PHASE";
  source_contracts: SourceContract[];
  persistence: PersistenceConfig;
  golden: GoldenConfig;
}

const DIFF_LABEL: Record<string, string> = {
  basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao",
};

const LAYER_LABEL: Record<string, string> = {
  CONTRACT: "Hợp đồng API", MODEL: "Mô hình dữ liệu", REPOSITORY: "Truy cập dữ liệu", VIEWMODEL: "Trạng thái & xử lý",
  SCREEN: "Giao diện màn hình", BLACKBOX: "Chức năng người dùng", RESPONSIVE: "Tương thích kích thước",
};

const TESTCASE_GROUP_ORDER = ["ALL", "LOGIC", "WIDGET", "BEHAVIOR"] as const;
const TESTCASE_GROUP_LABEL: Record<string, string> = {
  ALL: "Toàn bộ testcase",
  LOGIC: "Testcase Logic",
  WIDGET: "Testcase Widget",
  BEHAVIOR: "Testcase Behavior",
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

const ENGINE_LABEL: Record<string, string> = {
  TODO_USER_V12: "Bộ testcase chấm theo khung template mẫu",
  COMMON_V1: "Bộ testcase 3 tầng chấm theo Key",
};

const SKILL_LABEL: Record<string, string> = {
  UI_SCAFFOLD_APPBAR: "Khung ứng dụng, thanh tiêu đề và thanh điều hướng",
  UI_TEXT_INPUT: "Ô nhập dữ liệu và biểu mẫu",
  UI_BUTTONS_SELECTION: "Nút bấm và lựa chọn",
  UI_CONTAINER_ROW_COLUMN: "Bố cục Container, Row và Column",
  UI_TEXT_IMAGE_ICON: "Chữ, hình ảnh và biểu tượng",
  ADVUI_LISTVIEW: "Danh sách cuộn",
  ADVUI_GRIDVIEW: "Lưới và bố cục nhiều cột",
  ADVUI_EXPANDED_LAYOUTBUILDER: "Bố cục co giãn theo kích thước màn hình",
  NAV_NAVIGATOR_PUSH_POP: "Mở màn hình và quay lại",
  STATE_SETSTATE_STATEFUL: "Cập nhật trạng thái bằng setState",
  STATE_RIVERPOD: "Quản lý trạng thái bằng Riverpod",
  DART_CLASSES_OOP: "Lớp và đối tượng trong Dart",
  ASYNC_FUTURE_ASYNC_AWAIT: "Tác vụ bất đồng bộ",
  ASYNC_STREAMS_STREAMBUILDER: "Luồng dữ liệu và StreamBuilder",
};

const pad = (n: number) => String(n).padStart(2, "0");

type ParameterRole = "target" | "input" | "assertion" | "option";

const PARAMETER_ROLE_LABEL: Record<ParameterRole, string> = {
  target: "Đối tượng kiểm tra",
  input: "Dữ liệu đầu vào",
  assertion: "Điều kiện pass",
  option: "Tùy chọn runner",
};

const PARAMETER_ROLE_STYLE: Record<ParameterRole, string> = {
  target: "border-cyan-200 bg-cyan-50/70 text-cyan-800",
  input: "border-amber-200 bg-amber-50/70 text-amber-800",
  assertion: "border-emerald-200 bg-emerald-50/70 text-emerald-800",
  option: "border-slate-200 bg-slate-50 text-slate-700",
};

const INPUT_PARAMETER_KEYS = new Set([
  "argumentsJson", "invalidValues", "values", "expectedValues",
]);
const ASSERTION_PARAMETER_KEYS = new Set([
  "absentKey", "destinationKey", "errorKeys", "expected", "expectedCount", "expectedEnabled",
  "expectedGap", "expectedLabel", "expectedText", "expectedType", "expectedValue", "fontSize",
  "fontWeight", "homeKey", "resultKey", "updatedKey",
  "left", "top", "right", "bottom", "portraitWidth", "portraitHeight",
  "landscapeWidth", "landscapeHeight",
]);
const OPTION_PARAMETER_KEYS = new Set([
  "axis", "comparison", "dimension", "fieldType", "fromType", "matchMode",
  "targetType", "toType", "tolerance",
]);

function parameterRole(key: string, runner?: string): ParameterRole {
  if (INPUT_PARAMETER_KEYS.has(key)) return "input";
  if (ASSERTION_PARAMETER_KEYS.has(key) || (runner === "LIST_VISIBLE" && key === "itemKeys")) return "assertion";
  if (OPTION_PARAMETER_KEYS.has(key)) return "option";
  return "target";
}

function runnerContract(item: TestcaseItem, template?: Template) {
  const runner = String(template?.runner || "");
  const p = item.parameters || {};
  if (runner === "LIST_VISIBLE") return {
    input: "Không có dữ liệu nhập mặc định. Có thể dùng bước setup để mở màn hình hoặc seed trạng thái trước khi kiểm tra.",
    target: `Tìm đúng một danh sách mang ValueKey(${formatParam(p.listKey)}).`,
    pass: `Từng key trong itemKeys (${formatParam(p.itemKeys)}) đều phải xuất hiện đúng trên giao diện.`,
  };
  if (runner === "FORM_VALIDATE_FIELDS") return {
    input: `Nhập invalidValues (${formatParam(p.invalidValues)}) lần lượt vào fieldKeys (${formatParam(p.fieldKeys)}).`,
    target: `Bấm nút ${formatParam(p.submitKey)} sau khi nhập dữ liệu.`,
    pass: `Tất cả errorKeys (${formatParam(p.errorKeys)}) phải xuất hiện.`,
  };
  if (runner === "DIRECT_FUNCTION") return {
    input: `Giải mã argumentsJson ${formatParam(p.argumentsJson)} và truyền vào hàm ${formatParam(p.functionName)}.`,
    target: `Import ${formatParam(p.functionPath)} và gọi đúng hàm top-level đã khai báo.`,
    pass: `Actual phải ${formatParam(p.matchMode)} expectedValue=${formatParam(p.expectedValue)} (${formatParam(p.expectedType)}).`,
  };
  if (runner === "BUTTON_ACTION") return {
    input: "Không có dữ liệu nhập mặc định; setup có thể tạo trạng thái cần thiết trước khi bấm.",
    target: `Tìm và bấm widget mang key ${formatParam(p.buttonKey)}.`,
    pass: `Widget kết quả mang key ${formatParam(p.resultKey)} phải xuất hiện.`,
  };
  return {
    input: "Các trường ở nhóm Dữ liệu đầu vào và bước enter_text là dữ liệu được đưa vào testcase.",
    target: "Các semantic key cho runner biết cần tìm widget nào trong bài sinh viên.",
    pass: "Các trường Điều kiện pass được chuyển thành expect; ô mô tả rubric không tham gia so sánh.",
  };
}

function dartQuote(value: unknown) {
  return JSON.stringify(String(value ?? ""));
}

function csvValues(value: unknown) {
  return String(value ?? "").split(",").map((part) => part.trim()).filter(Boolean);
}

function setupStepHint(type: SetupStep["type"]) {
  switch (type) {
    case "tap": return "Key phải là semantic key của nút/thao tác cần bấm; bước này không có dữ liệu nhập.";
    case "enter_text": return "Key phải là semantic key của ô nhập; Value là dữ liệu đầu vào được đưa vào ô đó.";
    case "expect_visible": return "Key phải là semantic key của thành phần bắt buộc xuất hiện; thiếu key sẽ fail.";
    case "expect_absent": return "Key phải là semantic key của thành phần bắt buộc không xuất hiện.";
    case "wait_for_visible": return "Key phải là semantic key cần chờ; timeout chỉ là thời gian chờ, không phải expected output.";
  }
}

function setupCode(step: SetupStep) {
  const finder = `find.byKey(const ValueKey(${dartQuote(step.key)}))`;
  switch (step.type) {
    case "tap": return [`expect(${finder}, findsOneWidget);`, `await tester.tap(${finder});`, "await tester.pumpAndSettle();"];
    case "enter_text": return [`expect(${finder}, findsOneWidget);`, `await tester.enterText(${finder}, ${dartQuote(step.value)});`, "await tester.pumpAndSettle();"];
    case "expect_visible": return [`expect(${finder}, findsOneWidget);`];
    case "expect_absent": return [`expect(${finder}, findsNothing);`];
    case "wait_for_visible": return [`await waitForVisible(tester, ${dartQuote(step.key)}, ${step.timeout_ms || 2000});`];
  }
}

function testcaseCodePreview(item: TestcaseItem, template?: Template) {
  const runner = String(template?.runner || "");
  const p = item.parameters || {};
  const lines = [
    `testWidgets(${dartQuote(item.instance_id)}, (tester) async {`,
    "  await bootStudentApp(tester);",
  ];
  for (const step of item.setup_steps || []) {
    for (const line of setupCode(step)) lines.push(`  ${line}`);
  }
  const finder = (key: unknown) => `find.byKey(const ValueKey(${dartQuote(key)}))`;
  const expectVisible = (key: unknown) => `  expect(${finder(key)}, findsOneWidget);`;
  switch (runner) {
    case "LIST_VISIBLE":
      lines.push(expectVisible(p.listKey));
      for (const key of csvValues(p.itemKeys)) lines.push(expectVisible(key));
      break;
    case "WIDGET_VISIBLE":
    case "WIDGET_TYPE_VISIBLE":
      lines.push(expectVisible(p.widgetKey ?? p.targetKey));
      if (p.targetType) lines.push(`  expect(widgetTypeOf(${finder(p.widgetKey ?? p.targetKey)}), ${dartQuote(p.targetType)});`);
      break;
    case "BUTTON_ACTION":
      lines.push(`  await tester.tap(${finder(p.buttonKey)});`, "  await tester.pumpAndSettle();", expectVisible(p.resultKey));
      break;
    case "NAVIGATION":
      lines.push(`  await tester.tap(${finder(p.openKey)});`, "  await tester.pumpAndSettle();", expectVisible(p.destinationKey));
      break;
    case "FORM_VALIDATE_FIELDS": {
      const fields = csvValues(p.fieldKeys); const values = csvValues(p.invalidValues);
      fields.forEach((key, index) => lines.push(`  await tester.enterText(${finder(key)}, ${dartQuote(values[index])});`));
      lines.push(`  await tester.tap(${finder(p.submitKey)});`, "  await tester.pumpAndSettle();");
      for (const key of csvValues(p.errorKeys)) lines.push(expectVisible(key));
      break;
    }
    case "FORM_SUBMIT": {
      const fields = csvValues(p.fieldKeys); const values = csvValues(p.values);
      fields.forEach((key, index) => lines.push(`  await tester.enterText(${finder(key)}, ${dartQuote(values[index])});`));
      lines.push(`  await tester.tap(${finder(p.submitKey)});`, "  await tester.pumpAndSettle();");
      if (p.resultKey) lines.push(expectVisible(p.resultKey));
      break;
    }
    case "LIST_ITEM_COUNT":
      lines.push(`  final list = ${finder(p.listKey)};`, "  expect(list, findsOneWidget);", `  expect(countItems(list, ${JSON.stringify(csvValues(p.itemKeys))}), ${formatParam(p.expectedCount)});`);
      break;
    case "WIDGET_TEXT_CONTENT":
      lines.push(`  final actual = tester.widget<Text>(${finder(p.targetKey)}).data;`, `  expect(actual, ${p.matchMode === "contains" ? `contains(${dartQuote(p.expectedText)})` : dartQuote(p.expectedText)});`);
      break;
    case "DIRECT_FUNCTION":
      lines.splice(1, 1);
      lines.push(`  final actual = await ${formatParam(p.functionName)}(...jsonDecode(${dartQuote(p.argumentsJson)}));`, `  expect(actual, ${dartQuote(p.expectedValue)});`);
      break;
    default:
      lines.push(`  await runCommonCase(${dartQuote(runner)}, ${JSON.stringify(p)});`);
  }
  lines.push("});");
  return lines.join("\n");
}

function renderExpected(template: string, params: JsonMap) {
  return Object.entries(params).reduce(
    (text, [key, value]) => text.replaceAll(`{${key}}`, String(value)), template,
  );
}

function cloneParams(template: Template): JsonMap {
  return { ...(template.parameters_schema || {}) };
}

function emptySuite(): SuiteConfig {
  return {
    suite_version: 1,
    name: "Khung testcase mới",
    context: "",
    fixture_name: "",
    fixture_description: "",
    strict_semantic_keys: true,
    ready_key: "",
    required_keys: "",
    boot_timeout_ms: 3000,
    step_timeout_ms: 2000,
    setup_steps: [],
    profile: "COMMON_UI",
    reset_strategy: "APP_RESTART",
    source_contracts: [],
    persistence: { enabled: false, storage_kind: "none", reload_key: "", notes: "", reset_steps: [] },
    golden: { enabled: false, portrait_asset: "", landscape_asset: "", threshold: 0.01 },
  };
}

function todoStarterSuite(): SuiteConfig {
  return {
    ...emptySuite(),
    name: "User CRUD starter V12",
    context: "fixed_todo_contract",
    strict_semantic_keys: false,
    profile: "TODO_STARTER_V12",
    required_keys: "",
    source_contracts: [],
    setup_steps: [],
    persistence: { enabled: false, storage_kind: "none", reload_key: "", notes: "", reset_steps: [] },
    golden: { enabled: false, portrait_asset: "", landscape_asset: "", threshold: 0.01 },
  };
}

function formatParam(value: unknown) {
  if (typeof value === "object" && value !== null) return JSON.stringify(value);
  return String(value ?? "");
}

function testcaseGroup(template: Template) {
  if (template.testcase_group && TESTCASE_GROUP_LABEL[template.testcase_group]) {
    return template.testcase_group;
  }
  const runner = String(template.runner || "").toUpperCase();
  const layer = String(template.layer || "").toUpperCase();
  if (["APP_BOOT", "NAVIGATION", "BUTTON_ACTION", "WIDGET_ENABLED", "DIALOG_FLOW", "FORM_PREFILL", "FORM_SUBMIT"].includes(runner)) return "BEHAVIOR";
  if (["FORM_REQUIRED_FIELDS", "FORM_VALIDATE_FIELDS", "LIST_ITEM_COUNT", "STATE_REACTIVE_FLOW"].includes(runner)) return "LOGIC";
  if (layer === "RESPONSIVE" || runner.startsWith("WIDGET_") || runner === "LIST_VISIBLE") return "WIDGET";
  return "LOGIC";
}

const PARAMETER_OPTIONS: Record<string, string[]> = {
  targetType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  fromType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  toType: ["any", "form", "image", "text", "input", "button", "padding", "container"],
  dimension: ["height", "width"],
  comparison: ["equals", "at_least", "at_most"],
  axis: ["vertical", "horizontal"],
  fontWeight: ["w400", "w500", "w600", "w700", "w800"],
  expectedType: ["string", "bool", "int", "double", "json", "null"],
  matchMode: ["equals", "contains"],
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
  functionPath: "Đường dẫn file chứa hàm",
  functionName: "Tên hàm top-level cần gọi",
  argumentsJson: "Đối số truyền vào (JSON array)",
  expectedType: "Kiểu output cần nhận",
  expectedValue: "Output chuẩn để pass",
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
  const [templatePacks, setTemplatePacks] = useState<TestcasePack[]>([]);
  const [engineMode, setEngineMode] = useState<EngineMode>("TODO_USER_V12");
  const [skillOptions, setSkillOptions] = useState<SkillOption[]>([]);
  const [examId, setExamId] = useState("");
  const [examName, setExamName] = useState("");
  const [teacherNote, setTeacherNote] = useState("");
  const [items, setItems] = useState<TestcaseItem[]>([]);
  const [suite, setSuite] = useState<SuiteConfig>(todoStarterSuite);
  const [status, setStatus] = useState("");
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<"draft" | "publish" | null>(null);
  const [message, setMessage] = useState<{ type: "ok" | "error"; text: string } | null>(null);
  const [selectedCategory, setSelectedCategory] = useState("ALL");
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
  const [examIdCheck, setExamIdCheck] = useState<"idle" | "checking" | "available" | "editable" | "exists" | "error">("idle");
  const [newTemplateOpen, setNewTemplateOpen] = useState(false);
  const [newTemplateSaving, setNewTemplateSaving] = useState(false);
  const [newTemplateError, setNewTemplateError] = useState("");
  const [newTemplate, setNewTemplate] = useState({
    template_id: "", name: "", description: "", skill_code: "UI_TEXT_INPUT", layer: "SCREEN",
    testcase_group: "LOGIC", difficulty: "basic", weight_default: "1", runner: "FORM_VALIDATE_FIELDS",
    parameters_schema: '{"fieldKeys":"field.name,field.email","invalidValues":"invalid-name,invalid-email","submitKey":"action.save","errorKeys":"error.name,error.email","fieldType":"input"}',
    expected_template: "Khi nhập dữ liệu không hợp lệ, các ô nhập phải hiển thị lỗi tương ứng.",
  });

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewFiles, setPreviewFiles] = useState<Array<{ name: string; content: string }>>([]);
  const [previewFile, setPreviewFile] = useState(0);
  const [previewLoading, setPreviewLoading] = useState(false);

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
      const headers = { Authorization: `Bearer ${getToken() ?? ""}` };
      fetch(`${API_BASE}/exam-setup/status/${encodeURIComponent(normalized)}`, { signal: controller.signal, headers })
        .then(async (response) => {
          if (response.status === 404) {
            setExamIdCheck("available");
            return;
          }
          if (!response.ok) {
            setExamIdCheck("error");
            return;
          }
          const exam = await response.json().catch(() => ({}));
          const configResponse = await fetch(
            `${API_BASE}/exam-setup/${encodeURIComponent(normalized)}/testcases`,
            { signal: controller.signal, headers },
          );
          const config = configResponse.ok ? await configResponse.json().catch(() => ({})) : {};
          if (configResponse.ok && config.created_by && Array.isArray(config.items)) {
            setExamName(String(exam.examName || normalized));
            setTeacherNote(String(exam.teacherNote || ""));
            setItems(config.items as TestcaseItem[]);
            const loadedEngine = String(config.engine_type || config.items[0]?.engine_type || "");
            if (loadedEngine === "TODO_USER_V12" || loadedEngine === "COMMON_V1") {
              setEngineMode(loadedEngine);
            }
            if (config.suite && typeof config.suite === "object") {
              const loaded = config.suite as Partial<SuiteConfig>;
              setSuite({
                ...(loadedEngine === "TODO_USER_V12" ? todoStarterSuite() : emptySuite()),
                ...loaded,
                required_keys: Array.isArray(loaded.required_keys)
                  ? loaded.required_keys.join(", ")
                  : String(loaded.required_keys || ""),
                setup_steps: Array.isArray(loaded.setup_steps) ? loaded.setup_steps : [],
              });
            }
            setStatus(String(config.status || "DRAFT"));
            setVersion(Number(config.version || 0));
            setExamIdCheck("editable");
          } else {
            setExamIdCheck("exists");
          }
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
    const headers = { Authorization: `Bearer ${getToken() ?? ""}` };
    Promise.all([
      fetch(`${API_BASE}/testcase-templates`, { headers }).then((r) => r.ok ? r.json() : []),
      fetch(`${API_BASE}/testcase-templates/packs`, { headers }).then((r) => r.ok ? r.json() : []),
      fetch(`${API_BASE}/syllabus/skills?testable=auto`, { headers }).then((r) => r.ok ? r.json() : []),
    ])
      .then(([templateRows, packRows, skillRows]) => {
        const loadedTemplates = Array.isArray(templateRows) ? templateRows as Template[] : [];
        const loadedPacks = Array.isArray(packRows) ? packRows as TestcasePack[] : [];
        const loadedSkills = Array.isArray(skillRows) ? (skillRows as SkillOption[]).filter((skill) => !skill.deprecated) : [];
        setTemplates(loadedTemplates);
        setTemplatePacks(loadedPacks);
        setSkillOptions(loadedSkills);
        setSelectedCategory("ALL");
      })
      .catch(() => setMessage({ type: "error", text: "Không tải được thư viện testcase hoặc syllabus." }))
      .finally(() => setLoading(false));
  }, []);

  const templatesForEngine = useMemo(
    () => templates.filter((template) => (template.engine_type || "COMMON_V1") === engineMode),
    [templates, engineMode],
  );

  const categories = useMemo(() => {
    const counts = new Map<string, number>(TESTCASE_GROUP_ORDER.map((code) => [code, 0]));
    counts.set("ALL", templatesForEngine.length);
    templatesForEngine.forEach((template) => {
      const code = testcaseGroup(template);
      counts.set(code, (counts.get(code) || 0) + 1);
    });
    return TESTCASE_GROUP_ORDER.map((code) => ({
      code,
      label: TESTCASE_GROUP_LABEL[code],
      count: counts.get(code) || 0,
    }));
  }, [templatesForEngine]);

  const visibleTemplates = useMemo(() => templatesForEngine.filter((t) => {
    const categoryMatch = selectedCategory === "ALL" || testcaseGroup(t) === selectedCategory;
    const query = search.trim().toLowerCase();
    const skillLabel = SKILL_LABEL[t.skill_code] || t.skill_name || t.skill_code;
    const searchMatch = !query || [t.name, t.description, t.skill_code, skillLabel, t.layer,
      ENGINE_LABEL[t.engine_type || ""] || ""]
      .some((value) => value.toLowerCase().includes(query));
    return categoryMatch && searchMatch;
  }), [templatesForEngine, selectedCategory, search]);

  const selectedTemplate = templates.find((t) => t.template_id === selectedTemplateId) || null;
  const templateMap = useMemo(() => new Map(templates.map((t) => [t.template_id, t])), [templates]);
  const activeEngine = items.length
    ? (items[0].engine_type || templateMap.get(items[0].template_id)?.engine_type)
    : engineMode;
  const activePack = templatePacks.find((pack) => pack.engine_type === engineMode) || null;
  const selectedPackCount = activePack
    ? activePack.template_ids.filter((id) => items.some((item) => item.template_id === id)).length
    : 0;
  const supportsGrouping = activeEngine === "COMMON_V1";
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

  const selectEngineMode = (next: EngineMode) => {
    if (next === engineMode) return;
    if (items.length > 0) {
      setMessage({ type: "error", text: "Hãy xóa các testcase đang chọn trước khi đổi kiểu starter/engine." });
      return;
    }
    setEngineMode(next);
    setSelectedCategory("ALL");
    setSelectedTemplateId(null);
    setSearch("");
    setSuite(next === "TODO_USER_V12" ? todoStarterSuite() : emptySuite());
    setMessage(null);
  };

  const openNewTemplate = () => {
    if (engineMode !== "COMMON_V1") {
      setMessage({ type: "error", text: "Bộ chấm theo khung template mẫu dùng contract cố định. Chỉ bộ testcase 3 tầng chấm theo Key mới cho phép tạo runner tùy chỉnh." });
      return;
    }
    setNewTemplateError("");
    setNewTemplateOpen(true);
  };

  const changeNewTemplateRunner = (runner: string) => {
    const builtIn = templates.find((template) => template.runner === runner);
    setNewTemplate((current) => ({
      ...current,
      runner,
      parameters_schema: builtIn ? JSON.stringify(builtIn.parameters_schema || {}, null, 2) : current.parameters_schema,
      testcase_group: builtIn ? testcaseGroup(builtIn) : current.testcase_group,
      layer: builtIn?.layer || current.layer,
      skill_code: builtIn?.skill_code || current.skill_code,
    }));
  };

  const saveNewTemplate = async () => {
    setNewTemplateSaving(true);
    setNewTemplateError("");
    try {
      let schema: unknown;
      try { schema = JSON.parse(newTemplate.parameters_schema); }
      catch { throw new Error("Schema tham số phải là JSON object hợp lệ."); }
      if (!schema || typeof schema !== "object" || Array.isArray(schema))
        throw new Error("Schema tham số phải là JSON object.");
      const response = await fetch(`${API_BASE}/testcase-templates`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken() ?? ""}` },
        body: JSON.stringify({ ...newTemplate, weight_default: Number(newTemplate.weight_default), parameters_schema: schema }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || "Không tạo được template mới.");
      const created = data as Template;
      setTemplates((current) => [...current, created]);
      setSelectedTemplateId(created.template_id);
      setNewTemplateOpen(false);
      setMessage({ type: "ok", text: `Đã tạo template "${created.name}". Bạn có thể thêm template này vào đề ngay.` });
    } catch (error) {
      setNewTemplateError(error instanceof Error ? error.message : "Không tạo được template mới.");
    } finally {
      setNewTemplateSaving(false);
    }
  };

  const addTemplate = (templateId: string) => {
    const template = templateMap.get(templateId);
    if (!template) return;
    const templateEngine = (template.engine_type || "COMMON_V1") as EngineMode;
    if (templateEngine !== engineMode || (items.length > 0 && activeEngine !== templateEngine)) {
      setMessage({ type: "error", text: "Không thể trộn testcase của hai engine trong cùng một đề." });
      return;
    }
    if (templateEngine === "TODO_USER_V12" && items.some((item) => item.template_id === templateId)) {
      setMessage({ type: "error", text: "Testcase cố định này đã có trong đề." });
      return;
    }
    const usedIds = new Set(items.map((item) => item.instance_id));
    let nextNumber = items.length + 1;
    while (usedIds.has(`${examId.trim() || "exam"}_item_${pad(nextNumber)}`)) nextNumber += 1;
    const fixedId = template.execution_key || template.template_id;
    const item: TestcaseItem = {
      instance_id: templateEngine === "TODO_USER_V12" ? fixedId : `${examId.trim() || "exam"}_item_${pad(nextNumber)}`,
      template_id: template.template_id,
      template_version: template.template_version,
      engine_type: templateEngine,
      execution_key: fixedId,
      skill_code: template.skill_code,
      layer: template.layer,
      testcase_group: testcaseGroup(template),
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

  const addPackTemplates = (templateIds: string[], label: string) => {
    if (engineMode !== "TODO_USER_V12") return;
    const existingIds = new Set(items.map((item) => item.template_id));
    const missing = templateIds
      .map((id) => templateMap.get(id))
      .filter((template): template is Template => Boolean(template)
        && template?.engine_type === "TODO_USER_V12"
        && !existingIds.has(template.template_id));
    if (missing.length === 0) {
      setMessage({ type: "ok", text: `${label} đã được chọn đủ, không có tiêu chí nào cần thêm.` });
      return;
    }
    const created = missing.map((template, offset): TestcaseItem => {
      const fixedId = template.execution_key || template.template_id;
      return {
        instance_id: fixedId,
        template_id: template.template_id,
        template_version: template.template_version,
        engine_type: "TODO_USER_V12",
        execution_key: fixedId,
        skill_code: template.skill_code,
        layer: template.layer,
        testcase_group: testcaseGroup(template),
        name: template.name,
        description: template.description,
        difficulty: template.difficulty,
        enabled: true,
        order: items.length + offset + 1,
        weight: Number(template.weight_default || 0),
        parameters: {},
        expected: template.expected_template,
        expected_custom: false,
        setup_steps: [],
      };
    });
    setItems((current) => [...current, ...created].map((item, index) => ({ ...item, order: index + 1 })));
    setEditingId(created[created.length - 1]?.instance_id || null);
    setMessage({ type: "ok", text: `Đã chọn thêm ${missing.length} tiêu chí từ ${label}. Các tiêu chí đã có được giữ nguyên.` });
  };

  const removePackTemplates = (templateIds: string[], label: string) => {
    const removeIds = new Set(templateIds);
    const removedCount = items.filter((item) => removeIds.has(item.template_id)).length;
    if (removedCount === 0) {
      setMessage({ type: "ok", text: `${label} chưa có tiêu chí nào trong đề.` });
      return;
    }
    setItems((current) => current
      .filter((item) => !removeIds.has(item.template_id))
      .map((item, index) => ({ ...item, order: index + 1 })));
    setSelectedItemIds((current) => current.filter((id) => !items.some(
      (item) => item.instance_id === id && removeIds.has(item.template_id),
    )));
    setEditingId(null);
    setMessage({ type: "ok", text: `Đã bỏ ${removedCount} tiêu chí của ${label}. Bạn có thể chọn lại bất cứ lúc nào trước khi lưu.` });
  };

  const focusContractTests = (query: string) => {
    setSelectedCategory("ALL");
    setSearch(query);
    setSelectedTemplateId(null);
    window.setTimeout(() => document.getElementById("testcase-library")?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
  };

  const updateItem = (instanceId: string, patch: Partial<TestcaseItem>) => {
    setItems((current) => current.map((item) => item.instance_id === instanceId ? { ...item, ...patch } : item));
  };

  const updateSuite = (patch: Partial<SuiteConfig>) => {
    setSuite((current) => ({ ...current, ...patch }));
  };

  const addSuiteStep = () => {
    updateSuite({
      setup_steps: [...suite.setup_steps, { type: "expect_visible", key: "screen.home" }],
    });
  };

  const updateSuiteStep = (index: number, patch: Partial<SetupStep>) => {
    updateSuite({
      setup_steps: suite.setup_steps.map((step, stepIndex) => stepIndex === index ? { ...step, ...patch } : step),
    });
  };

  const removeSuiteStep = (index: number) => {
    updateSuite({ setup_steps: suite.setup_steps.filter((_, stepIndex) => stepIndex !== index) });
  };
  const addSourceContract = () => updateSuite({ source_contracts: [...suite.source_contracts, { type: "model", path: "lib/models/model.dart", symbols: "Model" }] });
  const updateSourceContract = (index: number, patch: Partial<SourceContract>) => updateSuite({ source_contracts: suite.source_contracts.map((contract, i) => i === index ? { ...contract, ...patch } : contract) });
  const removeSourceContract = (index: number) => updateSuite({ source_contracts: suite.source_contracts.filter((_, i) => i !== index) });

  const openPreview = async () => {
    if (!examId.trim() || version === 0) {
      setMessage({ type: "error", text: "Hãy lưu Draft hoặc Publish trước khi xem file sinh." });
      return;
    }
    setPreviewOpen(true);
    setPreviewLoading(true);
    try {
      const response = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/testcase`, {
        headers: { Authorization: `Bearer ${getToken() ?? ""}` },
      });
      const data = await response.json().catch(() => []);
      if (!response.ok) throw new Error(data.error || "Không đọc được file testcase");
      setPreviewFiles(Array.isArray(data) ? data : []);
      setPreviewFile(0);
    } catch (error) {
      setPreviewFiles([]);
      setMessage({ type: "error", text: error instanceof Error ? error.message : "Không đọc được file testcase" });
    } finally {
      setPreviewLoading(false);
    }
  };

  const addItemSetupStep = (item: TestcaseItem) => {
    updateItem(item.instance_id, { setup_steps: [...(item.setup_steps || []), { type: "expect_visible", key: "screen.home" }] });
  };

  const updateItemSetupStep = (item: TestcaseItem, index: number, patch: Partial<SetupStep>) => {
    updateItem(item.instance_id, { setup_steps: (item.setup_steps || []).map((step, stepIndex) => stepIndex === index ? { ...step, ...patch } : step) });
  };

  const removeItemSetupStep = (item: TestcaseItem, index: number) => {
    updateItem(item.instance_id, { setup_steps: (item.setup_steps || []).filter((_, stepIndex) => stepIndex !== index) });
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
    if (new Set(selected.map((item) => item.skill_code)).size > 1
      || new Set(selected.map((item) => item.layer)).size > 1
      || new Set(selected.map((item) => item.testcase_group)).size > 1) {
      setMessage({ type: "error", text: "Các testcase trong một nhóm phải cùng skill, layer và loại kiểm tra để không làm sai rubric." });
      return;
    }
    const selectedRunners = selected.map((item) => templateMap.get(item.template_id)?.runner || "");
    if (selectedRunners.includes("DIRECT_FUNCTION") && selectedRunners.some((runner) => runner !== "DIRECT_FUNCTION")) {
      setMessage({ type: "error", text: "Không được trộn DIRECT_FUNCTION với testcase khởi động UI trong cùng nhóm." });
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
    if (examIdCheck !== "available" && examIdCheck !== "editable") {
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
        body: JSON.stringify({ exam_name: examName.trim(), teacher_note: teacherNote.trim(), suite, items }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được cấu hình testcase");
      setStatus(data.status || (kind === "publish" ? "PUBLISHED" : "DRAFT"));
      setExamIdCheck("editable");
      setVersion(Number(data.version ?? version));
      if (data.suite && typeof data.suite === "object") {
        const loadedSuite = data.suite as Partial<SuiteConfig>;
        setSuite({ ...(engineMode === "TODO_USER_V12" ? todoStarterSuite() : emptySuite()), ...loadedSuite, required_keys: Array.isArray(loadedSuite.required_keys) ? loadedSuite.required_keys.join(", ") : String(loadedSuite.required_keys || ""), setup_steps: Array.isArray(loadedSuite.setup_steps) ? loadedSuite.setup_steps : [] });
      }
      setItems(Array.isArray(data.items) ? data.items as TestcaseItem[] : items);
      const previewResponse = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/testcase`, { headers: { Authorization: `Bearer ${getToken() ?? ""}` } });
      const generatedFiles = previewResponse.ok ? await previewResponse.json().catch(() => []) : [];
      if (Array.isArray(generatedFiles)) {
        setPreviewFiles(generatedFiles);
        const examTestIndex = generatedFiles.findIndex((file: { name?: string }) => file.name?.endsWith("exam_test.dart"));
        setPreviewFile(examTestIndex >= 0 ? examTestIndex : 0);
      }
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
      subtitle="Chọn chấm theo khung template mẫu hoặc chấm 3 tầng theo Key"
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
            {examIdCheck === "editable" && <p className="mt-1.5 text-[11px] font-semibold text-indigo-600">Đã tải cấu hình hiện tại; bạn có thể tiếp tục sửa Draft hoặc Publish phiên bản mới.</p>}
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
            <button onClick={openPreview} disabled={version === 0 || !!saving} className="flex items-center gap-2 rounded-lg border border-cyan-200 bg-cyan-50 px-3.5 py-2.5 text-sm font-semibold text-cyan-700 hover:bg-cyan-100 disabled:cursor-not-allowed disabled:opacity-50">
              <Eye size={16} /> Xem file sinh
            </button>
            <button onClick={() => save("draft")} disabled={!!saving || !["available", "editable"].includes(examIdCheck) || !examName.trim()} className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "draft" ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} Lưu Draft
            </button>
            <button onClick={() => save("publish")} disabled={!!saving || !["available", "editable"].includes(examIdCheck) || !examName.trim()} className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "publish" ? <Loader2 size={16} className="animate-spin" /> : <UploadCloud size={16} />} Publish snapshot
            </button>
          </div>
        </div>

        <section className="card p-4">
          <div className="mb-3">
            <p className="eyebrow">Cách chấm</p>
            <h2 className="mt-1 text-sm font-bold text-slate-800">Chọn contract phù hợp với template phát cho sinh viên</h2>
          </div>
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <button type="button" onClick={() => selectEngineMode("TODO_USER_V12")} className={`rounded-xl border p-4 text-left transition ${engineMode === "TODO_USER_V12" ? "border-indigo-400 bg-indigo-50 ring-2 ring-indigo-100" : "border-slate-200 hover:border-indigo-200"}`}>
              <div className="flex items-center justify-between gap-3"><strong className="text-sm text-slate-800">Bộ testcase chấm theo khung template mẫu</strong><span className="rounded-full bg-emerald-100 px-2 py-1 text-[10px] font-bold text-emerald-700">TEMPLATE MẪU</span></div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Mỗi đề phải có pack riêng khớp với starter đã phát. Sinh viên giữ public contract và hoàn thành TODO trong khung code đó.</p>
            </button>
            <button type="button" onClick={() => selectEngineMode("COMMON_V1")} className={`rounded-xl border p-4 text-left transition ${engineMode === "COMMON_V1" ? "border-indigo-400 bg-indigo-50 ring-2 ring-indigo-100" : "border-slate-200 hover:border-indigo-200"}`}>
              <div className="flex items-center justify-between gap-3"><strong className="text-sm text-slate-800">Bộ testcase 3 tầng chấm theo Key</strong><span className="rounded-full bg-amber-100 px-2 py-1 text-[10px] font-bold text-amber-700">LOGIC – WIDGET – BEHAVIOR</span></div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Dùng contract Key để tìm widget và chấm ba tầng. Phù hợp khi sinh viên được tự do xây dựng cấu trúc UI hơn.</p>
            </button>
          </div>
          {items.length > 0 && <p className="mt-3 text-[11px] font-semibold text-amber-600">Kiểu chấm được khóa khi đề đã có testcase. Xóa tất cả testcase nếu cần đổi engine.</p>}
        </section>

        {engineMode === "TODO_USER_V12" ? (
        <section className="card overflow-hidden">
          <div className="border-b border-slate-100 bg-emerald-50/70 px-4 py-3">
            <p className="eyebrow">Contract starter đã khóa</p>
            <div className="mt-1 flex flex-wrap items-center justify-between gap-3"><h2 className="text-sm font-bold text-slate-800">{activePack?.name || "Bộ testcase chấm theo khung template mẫu"}</h2>{activePack && <div className="flex flex-wrap gap-2">{selectedPackCount < activePack.testcase_count ? <button type="button" onClick={() => addPackTemplates(activePack.template_ids, activePack.name)} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white hover:bg-emerald-700">Chọn {activePack.testcase_count - selectedPackCount} tiêu chí còn thiếu</button> : <span className="rounded-lg bg-emerald-100 px-3 py-2 text-xs font-bold text-emerald-700">Đã chọn đủ {activePack.testcase_count}/{activePack.testcase_count}</span>}{selectedPackCount > 0 && <button type="button" onClick={() => removePackTemplates(activePack.template_ids, activePack.name)} className="rounded-lg border border-rose-200 bg-white px-3 py-2 text-xs font-bold text-rose-600 hover:bg-rose-50">Bỏ toàn bộ pack</button>}</div>}</div>
            <p className="mt-1 text-xs text-slate-500">Chọn tiêu chí nghĩa là đưa tiêu chí đó vào cột “Testcase trong đề” để tính điểm khi chấm. Việc này không thay đổi starter của sinh viên.</p>
          </div>
          {activePack && <div className="border-b border-slate-100 p-4">
            <div className="flex flex-wrap items-center gap-2 text-[10px]"><span className="rounded bg-indigo-100 px-2 py-1 font-bold text-indigo-700">PACK {activePack.pack_version}</span><span className="rounded bg-slate-100 px-2 py-1 font-mono text-slate-600">{activePack.pack_id}</span><span className="text-slate-500">Starter: {activePack.starter_file}</span><span className="ml-auto font-bold text-indigo-700">{activePack.default_weight} điểm mặc định</span></div>
            <p className="mt-2 text-xs leading-relaxed text-slate-600">{activePack.description}</p><p className="mt-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-[10px] font-semibold leading-relaxed text-amber-800">Pack này chỉ dùng cho đúng đề User CRUD và đúng starter file ghi phía trên. Đề khác phải tạo pack template mẫu khác, không áp dụng máy móc 48 tiêu chí này.</p>
            <div className="mt-3 grid grid-cols-1 gap-2 md:grid-cols-3">{activePack.scopes.map((scope) => {
              const selectedCount = scope.template_ids.filter((id) => items.some((item) => item.template_id === id)).length;
              const complete = selectedCount === scope.testcase_count;
              return <div key={scope.scope_id} className={`rounded-lg border p-3 ${complete ? "border-emerald-200 bg-emerald-50/50" : "border-indigo-100 bg-indigo-50/40"}`}><div className="flex items-center justify-between gap-2"><p className="text-xs font-bold text-indigo-800">{scope.name}</p><span className={`text-[10px] font-semibold ${complete ? "text-emerald-600" : "text-indigo-600"}`}>{selectedCount}/{scope.testcase_count} đã chọn</span></div><p className="mt-1 min-h-8 text-[10px] leading-relaxed text-slate-500">{scope.description}</p>{complete ? <button type="button" onClick={() => removePackTemplates(scope.template_ids, scope.name)} className="mt-2 w-full rounded-md border border-rose-200 bg-white px-2 py-1.5 text-[10px] font-bold text-rose-600 hover:bg-rose-50">Bỏ tầng {scope.scope_id}</button> : <button type="button" onClick={() => addPackTemplates(scope.template_ids, scope.name)} className="mt-2 w-full rounded-md border border-indigo-200 bg-white px-2 py-1.5 text-[10px] font-bold text-indigo-700 hover:bg-indigo-100">Chọn thêm {scope.testcase_count - selectedCount} tiêu chí</button>}</div>;
            })}</div>
          </div>}
          <div className="border-b border-slate-100 px-4 pt-4"><p className="text-xs font-bold text-slate-700">Các phần code mà pack sẽ chấm</p><p className="mt-1 text-[10px] text-slate-500">Đây không phải các trường cần nhập. Bấm vào một phần để lọc và xem các tiêu chí chấm tương ứng ở thư viện phía dưới.</p></div>
          <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-2 xl:grid-cols-4">
            {[
              ["Model", "lib/models/user_model.dart", "UserModel, copyWith, fromMap, toMap", "Model"],
              ["SQLite", "lib/database/database_service.dart", "DatabaseService, SqliteDatabaseService", "SQLite"],
              ["Repository", "lib/repositories/user_repository.dart", "UserRepository, SqliteUserRepository", "Repository"],
              ["State", "lib/viewmodels/user_view_model.dart", "Riverpod providers và UserViewModel", "ViewModel"],
              ["Danh sách", "lib/screens/user_list_screen.dart", "UserListScreen / HomeScreen", "danh sách"],
              ["Chi tiết", "lib/screens/user_detail_screen.dart", "UserDetailScreen", "chi tiết"],
              ["Khởi động", "lib/main.dart", "ProviderScope và MaterialApp", "khởi động"],
              ["Giao diện", "Behavior/Text/Semantics", "Không bắt buộc Widget Key", "giao diện"],
            ].map(([title, path, symbols, query]) => <button type="button" key={title} onClick={() => focusContractTests(query)} className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-left transition hover:border-indigo-300 hover:bg-indigo-50"><div className="flex items-center justify-between gap-2"><p className="text-xs font-bold text-slate-700">{title}</p><span className="text-[9px] font-bold text-indigo-600">Xem tiêu chí ↓</span></div><p className="mt-1 break-all font-mono text-[10px] text-indigo-600">{path}</p><p className="mt-1 text-[10px] leading-relaxed text-slate-500">{symbols}</p></button>)}
          </div>
          <div className="border-t border-slate-100 px-4 py-3 text-xs text-slate-500"><strong className="text-slate-700">Lưu ý:</strong> không đổi tên/xóa các public symbol starter. Sinh viên vẫn tự thiết kế phần UI bên trong hai screen, miễn luồng Add/Edit/Delete/Detail và validation hoạt động.</div>
        </section>
        ) : (
        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/70 px-4 py-3">
            <div>
              <p className="eyebrow">Khung bộ testcase</p>
              <h2 className="mt-1 text-sm font-bold text-slate-800">Ngữ cảnh, fixture và setup dùng chung</h2>
              <p className="mt-1 text-xs text-slate-500">Mỗi testcase sẽ khởi động lại app rồi chạy khung này trước khi kiểm tra riêng.</p>
            </div>
            <label className="flex items-center gap-2 text-xs font-semibold text-slate-600">
              <input type="checkbox" checked={suite.strict_semantic_keys} onChange={(e) => updateSuite({ strict_semantic_keys: e.target.checked })} />
              Bắt buộc semantic key chính xác
            </label>
          </div>
          <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-2 xl:grid-cols-4">
            <label className="text-xs font-semibold text-slate-600">Tên khung<input value={suite.name} onChange={(e) => updateSuite({ name: e.target.value })} placeholder="Todo CRUD cơ bản" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Ngữ cảnh<input value={suite.context} onChange={(e) => updateSuite({ context: e.target.value })} placeholder="todo_crud" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Tên fixture<input value={suite.fixture_name} onChange={(e) => updateSuite({ fixture_name: e.target.value })} placeholder="one_existing_todo" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Key báo sẵn sàng<input value={suite.ready_key} onChange={(e) => updateSuite({ ready_key: e.target.value })} placeholder="screen.home.ready" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600 md:col-span-2">Mô tả fixture<textarea rows={2} value={suite.fixture_description} onChange={(e) => updateSuite({ fixture_description: e.target.value })} placeholder="Dữ liệu ban đầu mà starter phải hiển thị trước khi chạy testcase." className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600 md:col-span-2">Các key bắt buộc<input value={suite.required_keys} onChange={(e) => updateSuite({ required_keys: e.target.value })} placeholder="screen.home, list.items, action.add" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Phân tách bằng dấu phẩy. Bỏ trống nếu chỉ muốn testcase tự khai báo target.</span></label>
            <label className="text-xs font-semibold text-slate-600">Chờ khởi động (ms)<input type="number" min={100} max={30000} step={100} value={suite.boot_timeout_ms} onChange={(e) => updateSuite({ boot_timeout_ms: Number(e.target.value) })} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Chờ mỗi bước (ms)<input type="number" min={100} max={30000} step={100} value={suite.step_timeout_ms} onChange={(e) => updateSuite({ step_timeout_ms: Number(e.target.value) })} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
          </div>
          <div className="border-t border-slate-100 px-4 py-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                  <label className="text-xs font-semibold text-slate-600">Profile<select value={suite.profile} onChange={(e) => updateSuite({ profile: e.target.value as SuiteConfig["profile"] })} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal"><option value="COMMON_UI">Common UI</option><option value="FLUTTER_LAYERED">Flutter layered</option><option value="PERSISTENCE" disabled>Persistence (chưa có runner)</option><option value="REPOSITORY_SQLITE" disabled>Repository + SQLite (dùng grading adapter/DIRECT_FUNCTION)</option><option value="GOLDEN_RESPONSIVE" disabled>Golden responsive (chưa có runner)</option></select></label>
                  <label className="text-xs font-semibold text-slate-600">Reset strategy<select value={suite.reset_strategy} onChange={(e) => updateSuite({ reset_strategy: e.target.value as SuiteConfig["reset_strategy"] })} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal"><option value="APP_RESTART">App restart</option><option value="FIXTURE_STEPS">Fixture steps</option><option value="CLEAR_STORAGE" disabled>Clear storage (chưa hỗ trợ)</option><option value="PERSISTENCE_PHASE" disabled>Persistence phase (chưa hỗ trợ)</option></select></label>
            </div>
            <div className="mt-4 flex items-center justify-between gap-2"><div><p className="text-xs font-bold text-slate-700">Source contracts</p><p className="text-[10px] text-slate-400">Dùng path trong lib/ và tên Dart identifier chính xác.</p></div><button onClick={addSourceContract} type="button" className="flex items-center gap-1 rounded-md border border-indigo-200 px-2.5 py-1.5 text-xs font-semibold text-indigo-600 hover:bg-indigo-50"><Plus size={13} /> Thêm contract</button></div>
            {suite.source_contracts.length === 0 ? <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-xs text-slate-400">Chưa khai báo symbol bắt buộc.</p> : <div className="mt-3 space-y-2">{suite.source_contracts.map((contract, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-slate-200 bg-slate-50 p-2 md:grid-cols-[150px_minmax(180px,1fr)_minmax(180px,1fr)_auto]"><select value={contract.type} onChange={(e) => updateSourceContract(index, { type: e.target.value as SourceContractType })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="model">Model</option><option value="repository">Repository</option><option value="provider">Provider</option><option value="screen">Screen</option><option value="helper">Helper</option><option value="service">Service</option></select><input value={contract.path} onChange={(e) => updateSourceContract(index, { path: e.target.value })} placeholder="lib/models/user.dart" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" /><input value={contract.symbols} onChange={(e) => updateSourceContract(index, { symbols: e.target.value })} placeholder="User, UserStatus" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" /><button type="button" onClick={() => removeSourceContract(index)} className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa contract"><Trash2 size={14} /></button></div>)}</div>}
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
                  <div className="rounded-md border border-slate-200 bg-slate-50 p-3 opacity-70"><label className="flex items-center gap-2 text-xs font-semibold text-slate-600"><input type="checkbox" checked={suite.persistence.enabled} disabled={!suite.persistence.enabled} onChange={() => updateSuite({ persistence: { ...suite.persistence, enabled: false } })} /> Persistence sau reload · chưa có runner</label><p className="mt-2 text-[10px] leading-relaxed text-slate-500">Hiện hãy chấm Repository/SQLite qua grading adapter và DIRECT_FUNCTION. Nếu đề cũ đang bật, bỏ dấu tích trước khi Publish.</p></div>
                  <div className="rounded-md border border-slate-200 bg-slate-50 p-3 opacity-70"><label className="flex items-center gap-2 text-xs font-semibold text-slate-600"><input type="checkbox" checked={suite.golden.enabled} disabled={!suite.golden.enabled} onChange={() => updateSuite({ golden: { ...suite.golden, enabled: false } })} /> Golden image · chưa có runner</label><p className="mt-2 text-[10px] leading-relaxed text-slate-500">Nếu cấu hình cũ đang bật, bỏ dấu tích; chức năng chỉ được bật lại khi engine có phép so sánh golden thật.</p></div>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2"><div><p className="text-xs font-bold text-slate-700">Setup UI dùng chung</p><p className="mt-0.5 text-[10px] text-slate-400">Whitelist: tap, nhập text, chờ/kiểm tra key. Không chạy Dart code tùy ý.</p></div><button onClick={addSuiteStep} type="button" className="flex items-center gap-1 rounded-md bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={13} /> Thêm bước</button></div>
            {suite.setup_steps.length === 0 ? <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-xs text-slate-400">Chưa có setup chung. Fixture phải ở đúng trạng thái sau khi app khởi động.</p> : <div className="mt-3 space-y-2">{suite.setup_steps.map((step, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-slate-200 bg-slate-50 p-2 md:grid-cols-[170px_minmax(160px,1fr)_minmax(160px,1fr)_auto]"><p className="text-[10px] leading-relaxed text-slate-500 md:col-span-full">{setupStepHint(step.type)}</p><select value={step.type} onChange={(e) => updateSuiteStep(index, { type: e.target.value as SetupStep["type"] })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="tap">Tap key</option><option value="enter_text">Nhập text</option><option value="expect_visible">Bắt buộc thấy key</option><option value="expect_absent">Bắt buộc ẩn key</option><option value="wait_for_visible">Chờ key xuất hiện</option></select><input value={step.key} onChange={(e) => updateSuiteStep(index, { key: e.target.value })} placeholder="action.add" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" />{step.type === "enter_text" ? <input value={step.value || ""} onChange={(e) => updateSuiteStep(index, { value: e.target.value })} placeholder="Giá trị nhập" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /> : <div />}{step.type === "wait_for_visible" ? <input type="number" min={100} max={30000} value={step.timeout_ms || suite.step_timeout_ms} onChange={(e) => updateSuiteStep(index, { timeout_ms: Number(e.target.value) })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /> : <div /> }<button onClick={() => removeSuiteStep(index)} type="button" className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa bước"><Trash2 size={14} /></button></div>)}</div>}
          </div>
        </section>
        )}

        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-slate-50 px-4 py-3">
            <div><p className="eyebrow">File testcase đang sinh</p><h2 className="mt-1 text-sm font-bold text-slate-800">exam_test.dart</h2><p className="mt-1 text-xs text-slate-500">{engineMode === "TODO_USER_V12" ? "Bản engine V9 cố định sẽ được ghép với skills_matrix theo các testcase đã chọn." : "Bấm Lưu Draft để sinh lại file từ cấu hình semantic hiện tại."}</p></div>
            <button type="button" onClick={openPreview} disabled={version === 0 || previewLoading} className="flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 hover:bg-slate-100 disabled:opacity-50"><Eye size={14} /> Xem đủ 3 file</button>
          </div>
          {version === 0 ? <div className="p-6 text-center text-sm text-slate-400">Chưa có file. Hãy nhập mã đề, tên đề và bấm Lưu Draft.</div> : previewLoading ? <div className="flex justify-center p-8 text-slate-400"><Loader2 className="animate-spin" size={20} /></div> : <pre className="custom-scrollbar max-h-[520px] overflow-auto whitespace-pre bg-slate-900 p-4 text-[11px] leading-relaxed text-slate-100">{previewFiles.find((file) => file.name.endsWith("exam_test.dart"))?.content || "Không đọc được exam_test.dart. Hãy lưu lại Draft."}</pre>}
        </section>

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
              <h2 className="mt-1 text-sm font-bold text-slate-800">Nhóm testcase</h2>
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
            {supportsGrouping && groupSummaries.size > 0 && <div className="border-t border-slate-100 p-3"><div className="flex items-center justify-between gap-2"><p className="text-xs font-bold text-indigo-800">Các nhóm testcase</p><span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[10px] font-semibold text-indigo-600">{groupSummaries.size} nhóm</span></div><div className="mt-2 space-y-2">{Array.from(groupSummaries.entries()).map(([groupId, group]) => <div key={groupId} className="rounded-lg border border-indigo-100 bg-indigo-50/50 px-3 py-2"><div className="flex items-start justify-between gap-2"><div className="min-w-0"><p className="truncate text-xs font-bold text-slate-700">{group.name}</p><p className="mt-0.5 truncate font-mono text-[10px] text-slate-400">{groupId}</p></div><button onClick={() => deleteGroup(groupId)} className="flex shrink-0 items-center gap-1 rounded-lg px-1.5 py-1 text-[10px] font-semibold text-rose-600 hover:bg-rose-50" title="Xóa nhóm, giữ testcase con"><Trash2 size={12} /> Xóa</button></div><p className="mt-1 text-[10px] text-slate-500">{group.count} testcase con · {group.weight.toFixed(2)} điểm</p></div>)}</div></div>}
          </section>

          {/* Khu vực 2: thư viện template */}
          <section id="testcase-library" className="card min-w-0 scroll-mt-4 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex items-center justify-between gap-3">
                <div><p className="eyebrow">Khu vực 2</p><h2 className="mt-1 text-sm font-bold text-slate-800">Thư viện testcase</h2><p className="mt-1 text-xs text-slate-500">{engineMode === "TODO_USER_V12" ? "48 tiêu chí riêng của khung template mẫu User CRUD V12." : "Thư viện testcase 3 tầng chấm theo Key."}</p></div>
                <div className="flex items-center gap-2"><span className="text-xs text-slate-400">{visibleTemplates.length} template</span>{engineMode === "COMMON_V1" && <button type="button" onClick={openNewTemplate} className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-2.5 py-2 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={14} /> Tạo template mới</button>}</div>
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
                        <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${template.custom ? "bg-amber-100 text-amber-700" : "bg-emerald-100 text-emerald-700"}`} title={template.custom ? `Tạo bởi ${template.created_by || "giảng viên"}` : ENGINE_LABEL[template.engine_type || ""] || template.engine_type}>{template.custom ? "Tự tạo" : template.fixed_contract ? "Template mẫu" : "Chấm theo Key"}</span>
                        <span className="rounded bg-cyan-100 px-1.5 py-0.5 text-[10px] font-bold text-cyan-700">{TESTCASE_GROUP_LABEL[testcaseGroup(template)]}</span>
                        <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[template.layer] || template.layer}</span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[template.difficulty] || template.difficulty}</span>
                      </div>
                      <p className="mt-1 line-clamp-3 text-xs leading-relaxed text-slate-500">{template.description}</p>
                      <div className="mt-2 flex items-center justify-between gap-2">
                        <span className="truncate text-[10px] text-indigo-600" title={template.skill_code}>{SKILL_LABEL[template.skill_code] || template.skill_name || template.skill_code}</span>
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
                <p className="mt-1 text-[11px] text-slate-500">Bộ testcase: {ENGINE_LABEL[selectedTemplate.engine_type || ""] || selectedTemplate.engine_type || "Không xác định"}</p>
                <p className="mt-1 text-[11px] text-slate-500">Chủ đề: {SKILL_LABEL[selectedTemplate.skill_code] || selectedTemplate.skill_name || selectedTemplate.skill_code}</p>
                <p className="mt-2 rounded-lg bg-white p-2 text-xs text-slate-600"><span className="font-semibold text-slate-700">Expected tự sinh:</span> {renderExpected(selectedTemplate.expected_template, selectedTemplate.parameters_schema)}</p>
              </div>
            )}
          </section>

          {/* Khu vực 3: testcase instance của đề */}
          <section className="card min-w-0 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="eyebrow">Khu vực 3</p><h2 className="mt-1 text-sm font-bold text-slate-800">Testcase trong đề</h2></div><div className="flex items-center gap-2"><span className="rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700">{items.length} mục</span>{supportsGrouping && selectedItemIds.length >= 2 && <button onClick={openGroupModal} className="rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700">Gộp thành testcase lớn</button>}{items.length > 0 && <button onClick={clearAllItems} className="flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-100" title="Xóa toàn bộ testcase"><Trash2 size={13} /> Xóa tất cả</button>}</div></div>
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
                    <div className="flex items-center gap-3"><label className="flex items-center gap-1.5 text-xs text-slate-500"><input type="checkbox" checked={item.enabled} onChange={(e) => updateItem(item.instance_id, { enabled: e.target.checked })} /> Đang bật</label>{supportsGrouping && <label className="flex items-center gap-1.5 text-xs text-indigo-600"><input type="checkbox" checked={selectedItemIds.includes(item.instance_id)} onChange={() => toggleItemSelection(item.instance_id)} /> Chọn nhóm</label>}</div>
                    <div className="flex gap-1">{item.group_id && <button onClick={() => ungroupItems(item.group_id!)} className="rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100">Tách nhóm</button>}<button onClick={() => setEditingId(editingId === item.instance_id ? null : item.instance_id)} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-indigo-600 hover:bg-indigo-50"><Settings2 size={13} /> Cấu hình</button><button onClick={() => { setSelectedItemIds((current) => current.filter((id) => id !== item.instance_id)); setItems((current) => current.filter((x) => x.instance_id !== item.instance_id).map((x, i) => ({ ...x, order: i + 1 }))); }} className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600"><Trash2 size={14} /></button></div>
                  </div>
                  <label className="mt-3 block border-t border-slate-100 pt-3 text-xs font-semibold text-slate-600">
                    Mô tả rubric khi testcase đạt
                    <textarea
                      rows={2}
                      value={item.expected}
                      onChange={(e) => updateItem(item.instance_id, { expected: e.target.value, expected_custom: true })}
                      placeholder="Mô tả để hiển thị trong rubric/kết quả chấm"
                      className="mt-1.5 w-full resize-y rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal leading-relaxed outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                    />
                  </label>
                  {editingId === item.instance_id && (
                    <div className="mt-3 space-y-3 border-t border-indigo-100 pt-3">
                      <div className="grid grid-cols-2 gap-2"><label className="text-xs text-slate-500">Độ khó<select value={item.difficulty} onChange={(e) => updateItem(item.instance_id, { difficulty: e.target.value })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="basic">Cơ bản</option><option value="intermediate">Trung bình</option><option value="advanced">Nâng cao</option></select></label><label className="text-xs text-slate-500">Điểm<input type="number" min="0" step="0.5" value={item.weight} onChange={(e) => updateItem(item.instance_id, { weight: Number(e.target.value) })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /></label></div>
                      {engineMode === "TODO_USER_V12" ? (
                        <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-[11px] leading-relaxed text-emerald-800">
                          <p className="font-bold">Testcase cố định: {item.execution_key || item.instance_id}</p>
                          <p className="mt-1">Logic assert đã nằm trong engine V9. Cấu hình này chỉ cho phép bật/tắt, đổi độ khó, trọng số và mô tả rubric; không sinh Key, setup step hay grading adapter.</p>
                        </div>
                      ) : (<>
                      <div>
                        <div className="mb-2"><p className="text-xs font-semibold text-slate-700">Cấu hình runner</p><p className="mt-0.5 text-[10px] leading-relaxed text-slate-400">Mỗi trường được phân theo vai trò. Semantic key xác định widget trong bài sinh viên; dữ liệu setup chạy trước; điều kiện pass được chuyển thành assertion.</p></div>
                        {(() => { const contract = runnerContract(item, templateMap.get(item.template_id)); return <div className="mb-3 grid grid-cols-1 gap-2"><div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2"><p className="text-[10px] font-bold text-amber-800">1. Dữ liệu đầu vào</p><p className="mt-0.5 text-[10px] leading-relaxed text-amber-700">{contract.input}</p></div><div className="rounded-lg border border-cyan-200 bg-cyan-50 px-3 py-2"><p className="text-[10px] font-bold text-cyan-800">2. Đối tượng được tìm</p><p className="mt-0.5 text-[10px] leading-relaxed text-cyan-700">{contract.target}</p></div><div className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2"><p className="text-[10px] font-bold text-emerald-800">3. Testcase pass khi</p><p className="mt-0.5 text-[10px] leading-relaxed text-emerald-700">{contract.pass}</p></div></div>; })()}
                        <div className="space-y-2">{(["target", "input", "assertion", "option"] as ParameterRole[]).map((role) => { const keys = Object.keys(item.parameters || {}).filter((key) => parameterRole(key, templateMap.get(item.template_id)?.runner) === role); if (!keys.length) return null; return <section key={role} className={`rounded-lg border p-2.5 ${PARAMETER_ROLE_STYLE[role]}`}><div className="mb-2 flex items-center justify-between gap-2"><p className="text-[11px] font-bold">{PARAMETER_ROLE_LABEL[role]}</p><span className="text-[9px] opacity-70">{keys.length} trường</span></div><div className="grid grid-cols-1 gap-2 sm:grid-cols-2">{keys.map((key) => { const template = templateMap.get(item.template_id); const schemaValue = template?.parameters_schema?.[key]; const isNumber = typeof schemaValue === "number"; const options = PARAMETER_OPTIONS[key]; return <label key={key} className="text-[11px] font-medium"><span>{PARAMETER_LABELS[key] || key}</span><span className="ml-1 font-mono text-[9px] opacity-60">{key}</span>{options ? <select value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700">{options.map((option) => <option key={option} value={option}>{option}</option>)}</select> : <input type={isNumber ? "number" : "text"} value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700" />}</label>; })}</div></section>; })}</div>
                      </div>
                      <div className="border-t border-indigo-100 pt-3"><div className="flex items-center justify-between gap-2"><div><p className="text-xs font-semibold text-slate-600">Chuẩn bị dữ liệu và trạng thái</p><p className="text-[10px] leading-relaxed text-slate-400">“Thêm bước” không tạo thêm field cho runner. Key chỉ định widget; riêng bước Nhập text mới dùng Value làm dữ liệu đầu vào. Bước expect cũng có thể làm testcase fail.</p></div><button type="button" onClick={() => addItemSetupStep(item)} className="flex items-center gap-1 rounded-md border border-indigo-200 px-2 py-1 text-[10px] font-semibold text-indigo-600 hover:bg-indigo-50"><Plus size={12} /> Thêm bước</button></div>{(item.setup_steps || []).length > 0 && <div className="mt-2 space-y-2">{(item.setup_steps || []).map((step, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-indigo-100 bg-white p-2 md:grid-cols-[150px_minmax(140px,1fr)_minmax(130px,1fr)_auto]"><p className="text-[10px] leading-relaxed text-indigo-600 md:col-span-full">{setupStepHint(step.type)}</p><select value={step.type} onChange={(e) => updateItemSetupStep(item, index, { type: e.target.value as SetupStep["type"] })} className="rounded border border-slate-200 px-1.5 py-1 text-[10px]"><option value="tap">Tap key</option><option value="enter_text">Nhập text</option><option value="expect_visible">Bắt buộc thấy</option><option value="expect_absent">Bắt buộc ẩn</option><option value="wait_for_visible">Chờ xuất hiện</option></select><input value={step.key} onChange={(e) => updateItemSetupStep(item, index, { key: e.target.value })} placeholder="action.open" className="rounded border border-slate-200 px-1.5 py-1 font-mono text-[10px]" />{step.type === "enter_text" ? <input value={step.value || ""} onChange={(e) => updateItemSetupStep(item, index, { value: e.target.value })} placeholder="Giá trị" className="rounded border border-slate-200 px-1.5 py-1 text-[10px]" /> : <div />}{step.type === "wait_for_visible" ? <input type="number" min={100} max={30000} value={step.timeout_ms || suite.step_timeout_ms} onChange={(e) => updateItemSetupStep(item, index, { timeout_ms: Number(e.target.value) })} className="rounded border border-slate-200 px-1.5 py-1 text-[10px]" /> : <div /> }<button type="button" onClick={() => removeItemSetupStep(item, index)} className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa bước"><Trash2 size={12} /></button></div>)}</div>}</div>
                      <div className="overflow-hidden rounded-lg border border-slate-700 bg-slate-950"><div className="flex items-center justify-between border-b border-slate-700 px-3 py-2"><div><p className="text-[11px] font-bold text-slate-100">Code kiểm tra tương đương</p><p className="mt-0.5 text-[9px] text-slate-400">Chỉ đọc, cập nhật ngay khi sửa setup, semantic key, input hoặc expected.</p></div><span className="rounded bg-slate-800 px-2 py-1 font-mono text-[9px] text-cyan-300">{templateMap.get(item.template_id)?.runner}</span></div><pre className="custom-scrollbar max-h-80 overflow-auto whitespace-pre p-3 text-[10px] leading-relaxed text-slate-100">{testcaseCodePreview(item, templateMap.get(item.template_id))}</pre></div>
                      <p className="text-[10px] text-slate-400">Ô mô tả kết quả chỉ đi vào rubric và báo cáo. Các assertion trong code preview mới quyết định testcase pass/fail.</p>
                      </>)}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </section>
        </div>

        {typeof document !== "undefined" && createPortal(
          <>
            {previewOpen && (
              <div className="fixed inset-0 z-[55] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4" role="dialog" aria-modal="true" onClick={() => setPreviewOpen(false)}>
                <div className="flex max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex items-center justify-between border-b border-slate-200 px-5 py-3"><div><p className="eyebrow">Generated testcase</p><h2 className="text-sm font-bold text-slate-800">{examId} · profile {suite.profile}</h2></div><button onClick={() => setPreviewOpen(false)} className="rounded-md p-1 text-slate-400 hover:bg-slate-100" aria-label="Đóng"><X size={18} /></button></header>
                  {previewLoading ? <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={22} className="animate-spin" /></div> : previewFiles.length === 0 ? <p className="p-10 text-center text-sm text-slate-500">Chưa đọc được file sinh.</p> : <div className="flex min-h-0 flex-1 flex-col"><div className="flex gap-1 overflow-x-auto border-b border-slate-200 bg-slate-50 px-3 py-2">{previewFiles.map((file, index) => <button key={file.name} onClick={() => setPreviewFile(index)} className={`shrink-0 rounded-md px-3 py-1.5 font-mono text-xs ${index === previewFile ? "bg-indigo-100 font-bold text-indigo-700" : "text-slate-500 hover:bg-white"}`}>{file.name}</button>)}</div><pre className="custom-scrollbar min-h-0 flex-1 overflow-auto bg-slate-900 p-4 text-[11px] leading-relaxed text-slate-100">{previewFiles[previewFile]?.content}</pre></div>}
                </div>
              </div>
            )}
            {newTemplateOpen && (
              <div className="fixed inset-0 z-[90] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4" role="dialog" aria-modal="true" onClick={() => !newTemplateSaving && setNewTemplateOpen(false)}>
                <div className="max-h-[92vh] w-full max-w-3xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-start justify-between gap-4"><div><p className="eyebrow">Thư viện template</p><h2 className="mt-1 text-lg font-bold text-slate-800">Tạo testcase template mới</h2><p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">Bạn đang tạo một khung tái sử dụng từ runner đã có. Logic Repository/SQLite có thể nối qua template grading adapter; persistence qua reload hoặc golden image chỉ được bật khi có runner thực thi tương ứng.</p></div><button type="button" onClick={() => setNewTemplateOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100" aria-label="Đóng"><X size={18} /></button></div>
                  <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2">
                    <label className="text-xs font-semibold text-slate-600">Mã template <span className="font-normal text-slate-400">(để trống để tự sinh)</span><input value={newTemplate.template_id} onChange={(e) => setNewTemplate((v) => ({ ...v, template_id: e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "") }))} placeholder="CUSTOM_EMAIL_INVALID" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600">Tên template<input value={newTemplate.name} onChange={(e) => setNewTemplate((v) => ({ ...v, name: e.target.value }))} placeholder="Kiểm tra email không hợp lệ" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Mô tả<textarea rows={2} value={newTemplate.description} onChange={(e) => setNewTemplate((v) => ({ ...v, description: e.target.value }))} placeholder="Mô tả điều kiện và mục đích kiểm tra" className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600">Skill code<select value={newTemplate.skill_code} onChange={(e) => setNewTemplate((v) => ({ ...v, skill_code: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 font-mono text-xs">{skillOptions.length === 0 && <option value={newTemplate.skill_code}>{newTemplate.skill_code}</option>}{skillOptions.map((skill) => <option key={skill.code} value={skill.code}>{skill.code} · {skill.name || SKILL_LABEL[skill.code] || skill.code}</option>)}</select><span className="mt-1 block text-[10px] font-normal text-slate-400">Chỉ hiển thị skill đang bật và có thể chấm tự động.</span></label>
                    <label className="text-xs font-semibold text-slate-600">Runner<select value={newTemplate.runner} onChange={(e) => changeNewTemplateRunner(e.target.value)} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs">{Object.entries(RUNNER_LABEL).filter(([key]) => key !== "GROUP").map(([key, label]) => <option key={key} value={key}>{key} · {label}</option>)}</select></label>
                    <label className="text-xs font-semibold text-slate-600">Layer<select value={newTemplate.layer} onChange={(e) => setNewTemplate((v) => ({ ...v, layer: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs">{Object.entries(LAYER_LABEL).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
                    <label className="text-xs font-semibold text-slate-600">Nhóm<select value={newTemplate.testcase_group} onChange={(e) => setNewTemplate((v) => ({ ...v, testcase_group: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs"><option value="LOGIC">Testcase Logic</option><option value="WIDGET">Testcase Widget</option><option value="BEHAVIOR">Testcase Behavior</option></select></label>
                    <label className="text-xs font-semibold text-slate-600">Độ khó<select value={newTemplate.difficulty} onChange={(e) => setNewTemplate((v) => ({ ...v, difficulty: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs"><option value="basic">Cơ bản</option><option value="intermediate">Trung bình</option><option value="advanced">Nâng cao</option></select></label>
                    <label className="text-xs font-semibold text-slate-600">Trọng số mặc định<input type="number" min={0} max={100} step={0.5} value={newTemplate.weight_default} onChange={(e) => setNewTemplate((v) => ({ ...v, weight_default: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Expected template<textarea rows={2} value={newTemplate.expected_template} onChange={(e) => setNewTemplate((v) => ({ ...v, expected_template: e.target.value }))} className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Đây là mô tả rubric. Kết quả pass/fail thật do runner và parameters_schema quyết định.</span></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Schema tham số mặc định (JSON object)<textarea rows={8} value={newTemplate.parameters_schema} onChange={(e) => setNewTemplate((v) => ({ ...v, parameters_schema: e.target.value }))} spellCheck={false} className="mt-1.5 w-full resize-y rounded-md border border-slate-200 bg-slate-900 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-100" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Các key phải đúng với runner đã chọn. Không nhập Dart code tại đây.</span></label>
                  </div>
                  {newTemplateError && <p className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-700">{newTemplateError}</p>}
                  <div className="mt-5 flex justify-end gap-2"><button type="button" disabled={newTemplateSaving} onClick={() => setNewTemplateOpen(false)} className="rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">Hủy</button><button type="button" disabled={newTemplateSaving} onClick={saveNewTemplate} className="flex items-center gap-1.5 rounded-xl bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">{newTemplateSaving ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Lưu template</button></div>
                </div>
              </div>
            )}
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
                  <main className="custom-scrollbar min-h-0 space-y-4 overflow-y-auto bg-slate-50 p-5"><div className="rounded-2xl border border-indigo-100 bg-indigo-50/70 p-4 text-sm text-indigo-800"><span className="font-bold">{selectedGroupItems.length} testcase con</span> · Tất cả phải đạt thì nhóm mới pass. Danh sách dưới đây hiển thị đầy đủ loại kiểm tra, mã instance và expected của từng testcase.</div><div className="space-y-3">{selectedGroupItems.map((item, index) => { const template = templateMap.get(item.template_id); return <article key={item.instance_id} className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"><div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="rounded-md bg-indigo-100 px-2 py-1 text-[11px] font-bold text-indigo-700">#{index + 1}</span><h3 className="text-sm font-bold text-slate-800">{item.name}</h3></div><p className="mt-1 font-mono text-[11px] text-slate-400">{item.instance_id}</p></div><span className="rounded-lg bg-indigo-50 px-2.5 py-1.5 text-[11px] font-bold text-indigo-700">{RUNNER_LABEL[template?.runner || ""] || LAYER_LABEL[template?.layer || item.layer] || "Kiểm tra theo yêu cầu"}</span></div><p className="mt-3 text-xs leading-relaxed text-slate-600">{item.description}</p><p className="mt-3 rounded-xl bg-slate-50 p-3 text-xs leading-relaxed text-slate-600"><span className="font-semibold text-slate-700">Expected:</span> {item.expected}</p></article>; })}</div></main>
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
