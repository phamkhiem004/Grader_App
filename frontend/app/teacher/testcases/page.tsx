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
type EngineMode = "STARTER_KEY_HYBRID_V1" | "TEMPLATE_CONTRACT_V1" | "COMMON_V1";

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
  hybrid_target?: "STARTER_CONTRACT" | "SEMANTIC_KEY";
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
  contract_bindings?: Record<string, string>;
  expected_template: string;
  custom?: boolean;
  created_by?: string;
  created_at?: string;
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
  contract_overrides?: string[];
  expected: string;
  expected_custom?: boolean;
  group_id?: string;
  group_name?: string;
  created_by?: string;
  created_at?: string;
  setup_steps?: SetupStep[];
}

type ContractValueKind = "text" | "path" | "identifier" | "csv" | "json" | "number";
interface TemplateContractField {
  id: string;
  key: string;
  label: string;
  value: string;
  kind: ContractValueKind;
}
interface TemplateContractSection {
  id: string;
  name: string;
  fields: TemplateContractField[];
}
interface TemplateContractDraft {
  version: 5;
  sections: TemplateContractSection[];
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
interface SemanticKeyDefinition { symbol: string; value: string; group: string; description: string; }
interface SemanticKeyContract { source_path: string; class_name: string; keys: SemanticKeyDefinition[]; }

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
  profile: "COMMON_UI" | "FLUTTER_LAYERED" | "PERSISTENCE" | "REPOSITORY_SQLITE" | "GOLDEN_RESPONSIVE" | "TODO_STARTER_V12" | "TEMPLATE_CONTRACT_V1" | "STARTER_KEY_HYBRID_V1";
  reset_strategy: "APP_RESTART" | "FIXTURE_STEPS" | "CLEAR_STORAGE" | "PERSISTENCE_PHASE";
  source_contracts: SourceContract[];
  persistence: PersistenceConfig;
  golden: GoldenConfig;
  key_contract: SemanticKeyContract;
  template_contract?: TemplateContractDraft;
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
  TEMPLATE_SOURCE_SYMBOLS: "Kiểm tra file và symbol",
  TEMPLATE_SOURCE_TERMS: "Kiểm tra wiring/import trong source",
  TEMPLATE_MODEL_FIELDS: "Kiểm tra field của model",
  TEMPLATE_MODEL_COPY_WITH: "Kiểm tra method tạo bản sao",
  TEMPLATE_MODEL_MAPPING: "Kiểm tra mapping của model",
  TEMPLATE_SQLITE_SCHEMA: "Kiểm tra schema SQLite",
  TEMPLATE_REPOSITORY_METHODS: "Kiểm tra method Repository",
  TEMPLATE_FORM_FIELDS: "Kiểm tra ô nhập theo label/hint",
  TEMPLATE_BUTTONS: "Kiểm tra nút theo nội dung",
  TEMPLATE_FORM_ACTION: "Nhập form và kiểm tra kết quả",
  TEMPLATE_FORM_VALIDATION: "Kiểm tra validation theo label",
  TEMPLATE_UI_WORKFLOW: "Chạy luồng UI nhiều bước",
  TEMPLATE_TEXT_VISIBLE: "Kiểm tra nội dung hiển thị",
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
  WIDGET_RELATIONSHIP: "Kiểm tra quan hệ và thứ tự widget",
  RESPONSIVE_PAIR_LAYOUT: "Kiểm tra hai vùng responsive",
  WIDGET_PROPERTY: "Kiểm tra thuộc tính widget",
  KEY_WORKFLOW: "Chạy workflow nhiều bước theo Key",
  FORM_FOCUS_FLOW: "Kiểm tra thứ tự focus bàn phím",
  RESPONSIVE_LAYOUT_CASES: "Kiểm tra nhiều breakpoint adaptive",
  PROJECT_FILE_CONTRACT: "Kiểm tra file project theo contract",
  DIRECT_FUNCTION_THROWS: "Kiểm tra hàm ném exception",
  DIRECT_STREAM_EVENTS: "Kiểm tra chuỗi event của Stream",
  STARTER_CALL_SEQUENCE: "Chạy chuỗi API nghiệp vụ starter",
  PROCESS_PERSISTENCE_SEQUENCE: "Kiểm tra persistence qua process mới",
  GROUP: "Nhóm testcase",
};

const ENGINE_LABEL: Record<string, string> = {
  STARTER_KEY_HYBRID_V1: "Starter TODO cho Logic/SQLite + Key cho UI",
  TEMPLATE_CONTRACT_V1: "Bộ testcase chấm theo khung template mẫu",
  TODO_USER_V12: "Pack User CRUD V12 cũ",
  COMMON_V1: "Bộ testcase 3 tầng chấm theo Key",
};

const SKILL_LABEL: Record<string, string> = {
  DART_VARIABLES_TYPES: "Biến, kiểu dữ liệu và collection Dart",
  DART_NULL_SAFETY: "Null safety",
  DART_CONTROL_FLOW: "Rẽ nhánh và vòng lặp Dart",
  DART_FUNCTIONS: "Hàm Dart",
  DART_CLASSES_OOP: "Lớp và đối tượng trong Dart",
  DART_ENUMS_MIXINS_EXT: "Enum, mixin và extension",
  DART_EXCEPTIONS: "Ngoại lệ và xử lý lỗi",
  PROJ_PUBSPEC_DEPENDENCIES: "pubspec và dependency",
  PROJ_FOLDER_STRUCTURE: "Cấu trúc dự án",
  PROJ_STATELESS_VS_STATEFUL: "StatelessWidget và StatefulWidget",
  UI_SCAFFOLD_APPBAR: "Khung ứng dụng, thanh tiêu đề và thanh điều hướng",
  UI_CONTAINER_ROW_COLUMN: "Bố cục Container, Row và Column",
  UI_TEXT_IMAGE_ICON: "Chữ, hình ảnh và biểu tượng",
  UI_BUTTONS_SELECTION: "Nút bấm và lựa chọn",
  UI_TEXT_INPUT: "Ô nhập dữ liệu và biểu mẫu",
  UI_DRAWER_SNACKBAR: "Drawer và Snackbar",
  THEME_COLORS_COLORSCHEME: "Màu sắc và ColorScheme",
  THEME_TYPOGRAPHY_FONTS: "Typography và font",
  THEME_LIGHT_DARK: "Theme sáng/tối",
  STATE_SETSTATE_STATEFUL: "Cập nhật trạng thái bằng setState",
  STATE_INHERITED_WIDGET: "InheritedWidget",
  STATE_PROVIDER: "Provider",
  STATE_RIVERPOD: "Quản lý trạng thái bằng Riverpod",
  STATE_BLOC_OTHER: "BLoC và thư viện state khác",
  STATE_IMMUTABLE: "State bất biến",
  ADVUI_LISTVIEW: "Danh sách cuộn",
  ADVUI_GRIDVIEW: "Lưới và bố cục nhiều cột",
  ADVUI_STACK_INDEXEDSTACK: "Stack và IndexedStack",
  ADVUI_EXPANDED_LAYOUTBUILDER: "Bố cục co giãn theo kích thước màn hình",
  ADVUI_TABLE_CARD: "Table và Card",
  ADVUI_BOTTOMSHEET: "BottomSheet",
  ADVUI_SLIVERS: "Sliver và CustomScrollView",
  NAV_NAVIGATOR_PUSH_POP: "Mở màn hình và quay lại",
  NAV_NAMED_ROUTES: "Named routes",
  NAV_GOROUTER_AUTOROUTE: "GoRouter và AutoRoute",
  NAV_BOTTOM_NAVIGATION: "Bottom navigation",
  NAV_DEEP_LINKING: "Deep linking",
  ANIM_IMPLICIT: "Implicit animation",
  ANIM_TWEEN: "Tween animation",
  ANIM_EXPLICIT_CONTROLLER: "AnimationController",
  ANIM_HERO: "Hero animation",
  ANIM_PACKAGES: "Package animation",
  ASYNC_FUTURE_ASYNC_AWAIT: "Tác vụ bất đồng bộ",
  ASYNC_EVENT_LOOP: "Event loop và microtask",
  ASYNC_FUTUREBUILDER: "FutureBuilder và AsyncSnapshot",
  ASYNC_STREAMS_STREAMBUILDER: "Luồng dữ liệu và StreamBuilder",
  ASYNC_ISOLATES: "Isolate và Port",
  RESPONSIVE_BREAKPOINTS: "Breakpoint responsive và orientation",
  RESPONSIVE_ADAPTIVE_NAV: "Điều hướng và bố cục adaptive",
  FORM_STRUCTURE_VALIDATION: "FormState và validation đồng bộ",
  FORM_FOCUS_KEYBOARD: "Focus và keyboard action",
  FORM_ASYNC_VALIDATION: "Async validation và chống submit lặp",
  API_HTTP_REST: "HTTP và REST request/response",
  API_JSON_MODEL: "JSON mapping sang model",
  API_ASYNC_STATES: "Loading, empty, error, retry và data state",
  API_SERVICE_LAYER: "Service layer cho HTTP",
  STORAGE_SHARED_PREFERENCES: "SharedPreferences key-value",
  STORAGE_JSON_FILE: "JSON asset và JSON file local",
  STORAGE_SQLITE_CRUD: "SQLite schema, query và CRUD",
  STORAGE_PERSISTENCE_SYNC: "Persistence và offline-first",
  AUTH_LOGIN_SIGNUP: "Login, signup và protected flow",
  AUTH_TOKEN_SESSION: "Token, auto-login và logout session",
  AUTH_FIREBASE_GOOGLE: "Firebase và Google Sign-In",
  NOTIFICATION_LOCAL: "Local notification",
  TEST_UNIT: "Unit test",
  TEST_WIDGET: "Widget test",
  TEST_INTEGRATION_NAV: "Navigation và integration test",
  DEBUG_DEVTOOLS: "DevTools và bằng chứng debug",
  PERF_REBUILDS: "Giảm rebuild và công việc trong build",
  PERF_LIST_IMAGE: "Tối ưu list và image",
  DEPLOY_SIZE_RELEASE: "Size analysis và release deployment",
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
  "argumentsJson", "invalidValues", "values", "expectedValues", "inputValues", "stepsJson",
  "casesJson", "filesJson", "seedStepsJson", "verifyStepsJson",
]);
const ASSERTION_PARAMETER_KEYS = new Set([
  "absentKey", "destinationKey", "errorKeys", "expected", "expectedCount", "expectedEnabled",
  "expectedGap", "expectedLabel", "expectedText", "expectedType", "expectedValue", "fontSize",
  "fontWeight", "homeKey", "resultKey", "updatedKey",
  "left", "top", "right", "bottom", "portraitWidth", "portraitHeight",
  "landscapeWidth", "landscapeHeight",
  "expectedTexts",
  "errorTexts", "forbiddenTerms", "minimumOccurrences", "portraitExpectedTexts",
  "landscapeExpectedTexts", "requireNewResult", "requireNewErrors", "requireNewDestination",
  "hideDestinationAfterBack", "requireNewDialog", "requireNewUpdatedState", "requirePrefillTransition",
  "descendantKeys", "secondKey",
  "expectedEventsJson", "expectedException", "messageContains", "property", "actions",
]);
const OPTION_PARAMETER_KEYS = new Set([
  "axis", "comparison", "dimension", "fieldType", "fromType", "matchMode",
  "targetType", "toType", "tolerance", "scopeType", "scopeIndex", "scopeAnchorText",
  "resultScopeType", "resultScopeIndex", "resultScopeAnchorText", "textMatchMode",
  "resultTextMatchMode", "errorTextMatchMode", "symbolTypes", "schemaMethod", "readyTimeoutMs",
  "ancestorType", "descendantTypes", "orderedAxis", "alignment", "width", "height",
  "typeMatchMode", "timeoutMs", "dismissAfterLast", "fixtureNamespace",
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
  const formScope = formatParam(p.formIndex)
    ? ` trong Form #${formatParam(p.formIndex)}`
    : formatParam(p.formAnchorText)
      ? ` trong Form chứa "${formatParam(p.formAnchorText)}"`
      : " trong Form duy nhất chứa đủ các field đã khai báo";
  const uiScope = formatParam(p.scopeType)
    ? ` trong ${formatParam(p.scopeType)}${formatParam(p.scopeIndex) ? ` #${formatParam(p.scopeIndex)}` : ""}${formatParam(p.scopeAnchorText) ? ` chứa "${formatParam(p.scopeAnchorText)}"` : ""}`
    : "";
  if (runner.startsWith("TEMPLATE_")) return {
    input: runner === "TEMPLATE_FORM_ACTION"
      ? `Nhập inputValues (${formatParam(p.inputValues)}) theo fieldLabels (${formatParam(p.fieldLabels)}).`
      : runner === "TEMPLATE_FORM_VALIDATION"
        ? `Nhập nguyên văn invalidValues (${formatParam(p.invalidValues)}) theo fieldLabels (${formatParam(p.fieldLabels)}); chỉ <empty> có nghĩa đặc biệt.`
      : "Không dùng dữ liệu của pack cố định; mọi giá trị đều lấy từ contract đang nhập.",
    target: p.sourcePath
      ? `Kiểm tra ${formatParam(p.className || p.symbols || p.tableName)} trong file ${formatParam(p.sourcePath)}.`
      : `Tìm thành phần UI theo label/text${p.fieldLabels ? formScope : uiScope}: ${formatParam(p.fieldLabels || p.buttonLabels || p.actionLabel || p.expectedTexts)}.`,
    pass: `Các điều kiện trong contract phải đầy đủ; expected hiển thị là ${item.expected}.`,
  };
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
  if (runner === "DIRECT_FUNCTION_THROWS") return {
    input: `Giải mã argumentsJson ${formatParam(p.argumentsJson)} và truyền vào hàm ${formatParam(p.functionName)}.`,
    target: `Import trực tiếp ${formatParam(p.functionPath)}; không dùng grading adapter.`,
    pass: `Hàm phải ném ${formatParam(p.expectedException)}${p.messageContains ? ` với message chứa ${formatParam(p.messageContains)}` : ""}.`,
  };
  if (runner === "DIRECT_STREAM_EVENTS") return {
    input: `Gọi ${formatParam(p.functionName)} với ${formatParam(p.argumentsJson)} và lấy số event đúng bằng expectedEventsJson.`,
    target: `Import trực tiếp ${formatParam(p.functionPath)}; hàm phải trả về Stream.`,
    pass: `Event phải đúng giá trị và thứ tự ${formatParam(p.expectedEventsJson)} trong ${formatParam(p.timeoutMs)}ms.`,
  };
  if (runner === "PROJECT_FILE_CONTRACT") return {
    input: "filesJson khai báo độc lập path, requiredTerms, forbiddenTerms và minBytes cho từng file.",
    target: "Chỉ đọc đường dẫn project an toàn đã whitelist; không thực thi source như một bằng chứng hành vi.",
    pass: "Mọi file phải tồn tại và thỏa toàn bộ terms đã cấu hình.",
  };
  if (runner === "KEY_WORKFLOW") return {
    input: "stepsJson chứa từng bước enter_text/tap/pump/wait; dữ liệu và Key thay đổi theo đề.",
    target: "Mỗi bước trỏ trực tiếp tới semantic Key công khai, không dò mơ hồ theo vị trí hoặc text.",
    pass: "Tất cả expect visible/absent/text/enabled/property trong chuỗi phải đạt.",
  };
  if (runner === "STARTER_CALL_SEQUENCE") return {
    input: "Mỗi bước trong stepsJson khai báo functionName, arguments và expected riêng.",
    target: `Import trực tiếp ${formatParam(p.sourcePath)} thuộc starter; không gọi grading adapter.`,
    pass: "Các hàm được gọi tuần tự trong cùng testcase và mọi kết quả đều phải đúng.",
  };
  if (runner === "PROCESS_PERSISTENCE_SEQUENCE") return {
    input: "seedStepsJson ghi fixture; verifyStepsJson chỉ đọc và đối chiếu ở Flutter process mới.",
    target: `Import trực tiếp ${formatParam(p.sourcePath)}; starter dùng GRADER_FIXTURE_ID=${formatParam(p.fixtureNamespace)} để chọn storage cô lập.`,
    pass: "Cả pha seed và pha verify đều phải pass; state singleton/in-memory sẽ không sống sang pha verify.",
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
  if (Array.isArray(value)) return value.map((part) => String(part ?? "").trim());
  const text = String(value ?? "").trim();
  if (text.startsWith("[")) {
    try {
      const parsed = JSON.parse(text);
      if (Array.isArray(parsed)) return parsed.map((part) => String(part ?? "").trim());
    } catch {
      return [];
    }
  }
  return text.split(",").map((part) => part.trim()).filter(Boolean);
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
  if (runner.startsWith("TEMPLATE_")) {
    return [
      `testWidgets(${dartQuote(item.instance_id)}, (tester) async {`,
      `  await runTemplateContractCase(tester, ${dartQuote(runner)}, ${JSON.stringify(p)});`,
      "});",
    ].join("\n");
  }
  if (runner === "STARTER_CALL_SEQUENCE") {
    return [
      `test(${dartQuote(item.instance_id)}, () async {`,
      `  await runStarterCallSequence(${dartQuote(p.sourcePath)}, ${dartQuote(p.stepsJson)});`,
      "});",
    ].join("\n");
  }
  if (runner === "PROCESS_PERSISTENCE_SEQUENCE") {
    return [
      `// Grader chạy testcase này hai lần với hai Flutter process độc lập.`,
      `test(${dartQuote(item.instance_id)}, () async {`,
      `  final phase = Platform.environment['GRADER_PERSISTENCE_PHASE'];`,
      `  await runStarterCallSequence(${dartQuote(p.sourcePath)}, phase == 'seed' ? ${dartQuote(p.seedStepsJson)} : ${dartQuote(p.verifyStepsJson)});`,
      "});",
    ].join("\n");
  }
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
    case "DIRECT_FUNCTION_THROWS":
      lines.splice(1, 1);
      lines.push(`  expect(() => ${formatParam(p.functionName)}(...jsonDecode(${dartQuote(p.argumentsJson)})), throwsA(isA<${formatParam(p.expectedException)}>()));`);
      break;
    case "DIRECT_STREAM_EVENTS":
      lines.splice(1, 1);
      lines.push(`  final events = await ${formatParam(p.functionName)}(...jsonDecode(${dartQuote(p.argumentsJson)})).take(jsonDecode(${dartQuote(p.expectedEventsJson)}).length).toList();`, `  expect(events, jsonDecode(${dartQuote(p.expectedEventsJson)}));`);
      break;
    case "PROJECT_FILE_CONTRACT":
      lines.splice(1, 1);
      lines.push(`  await verifyProjectFiles(${dartQuote(p.filesJson)});`);
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

type ItemProgressState = "ready" | "attention" | "disabled";
interface ItemProgress {
  state: ItemProgressState;
  issues: string[];
}

const REQUIRED_RUNNER_PARAMETERS: Record<string, string[]> = {
  TEMPLATE_SOURCE_SYMBOLS: ["sourcePath", "symbols"],
  TEMPLATE_SOURCE_TERMS: ["sourcePath"],
  TEMPLATE_MODEL_FIELDS: ["sourcePath", "className", "fields"],
  TEMPLATE_MODEL_COPY_WITH: ["sourcePath", "className", "copyMethod", "fields"],
  TEMPLATE_MODEL_MAPPING: ["sourcePath", "className", "toMapMethod", "fromMapMethod", "columns"],
  TEMPLATE_SQLITE_SCHEMA: ["sourcePath", "tableName", "columns"],
  TEMPLATE_REPOSITORY_METHODS: ["sourcePath", "className", "methods"],
  TEMPLATE_FORM_FIELDS: ["fieldLabels"],
  TEMPLATE_BUTTONS: ["buttonLabels"],
  TEMPLATE_FORM_ACTION: ["fieldLabels", "inputValues", "actionLabel", "expectedTexts"],
  TEMPLATE_FORM_VALIDATION: ["fieldLabels", "invalidValues", "actionLabel", "errorTexts"],
  TEMPLATE_TEXT_VISIBLE: ["expectedTexts"],
  TEMPLATE_UI_WORKFLOW: ["stepsJson"],
  WIDGET_VISIBLE: ["widgetKey"],
  FORM_REQUIRED_FIELDS: ["fieldKeys", "submitKey", "errorKeys"],
  NAVIGATION: ["openKey", "destinationKey"],
  LIST_VISIBLE: ["listKey", "itemKeys"],
  BUTTON_ACTION: ["buttonKey", "resultKey"],
  STATE_REACTIVE_FLOW: ["initialKey", "actionKey", "updatedKey", "absentKey"],
  DIRECT_FUNCTION: ["functionPath", "functionName", "argumentsJson", "expectedType", "matchMode"],
  DIRECT_FUNCTION_THROWS: ["functionPath", "functionName", "argumentsJson", "expectedException", "typeMatchMode"],
  DIRECT_STREAM_EVENTS: ["functionPath", "functionName", "argumentsJson", "expectedEventsJson", "timeoutMs"],
  STARTER_CALL_SEQUENCE: ["sourcePath", "stepsJson"],
  PROCESS_PERSISTENCE_SEQUENCE: ["sourcePath", "fixtureNamespace", "seedStepsJson", "verifyStepsJson"],
  PROJECT_FILE_CONTRACT: ["filesJson"],
  WIDGET_PROPERTY: ["targetKey", "targetType", "property", "expectedType", "expectedValue"],
  KEY_WORKFLOW: ["stepsJson"],
  FORM_FOCUS_FLOW: ["fieldKeys", "actions", "dismissAfterLast"],
  RESPONSIVE_LAYOUT_CASES: ["casesJson"],
  WIDGET_RELATIONSHIP: ["ancestorKey", "descendantKeys", "orderedAxis"],
  RESPONSIVE_PAIR_LAYOUT: ["width", "height", "firstKey", "secondKey", "alignment"],
  RESPONSIVE_NO_OVERFLOW: ["portraitWidth", "portraitHeight", "landscapeWidth", "landscapeHeight"],
  RESPONSIVE_TARGET: ["portraitWidth", "portraitHeight", "landscapeWidth", "landscapeHeight", "targetKey"],
};

function isBlankParameter(value: unknown) {
  return value === null || value === undefined || (typeof value === "string" && value.trim() === "");
}

function testcaseProgress(item: TestcaseItem, template?: Template): ItemProgress {
  if (!item.enabled) return { state: "disabled", issues: [] };
  const issues: string[] = [];
  const runner = String(template?.runner || "");
  const required = new Set(REQUIRED_RUNNER_PARAMETERS[runner] || []);

  // Tham số có giá trị mặc định trong template được xem là bắt buộc. Các trường mặc định
  // rỗng (rootKey, forbiddenTerms...) vẫn là tùy chọn nếu runner không khai báo bắt buộc.
  Object.entries(template?.parameters_schema || {}).forEach(([key, defaultValue]) => {
    if (runner === "TEMPLATE_SOURCE_TERMS" && ["requiredTerms", "forbiddenTerms"].includes(key)) return;
    if (!isBlankParameter(defaultValue)) required.add(key);
  });
  required.forEach((key) => {
    if (isBlankParameter(item.parameters?.[key])) {
      issues.push(`Thiếu ${PARAMETER_LABELS[key] || key}`);
    }
  });

  if (runner === "TEMPLATE_SOURCE_TERMS"
      && isBlankParameter(item.parameters.requiredTerms)
      && isBlankParameter(item.parameters.forbiddenTerms)) {
    issues.push("Cần ít nhất một nội dung source bắt buộc hoặc bị cấm");
  }
  if (["TEMPLATE_FORM_ACTION", "TEMPLATE_FORM_VALIDATION"].includes(runner)) {
    const labels = csvValues(item.parameters.fieldLabels);
    const values = csvValues(runner === "TEMPLATE_FORM_ACTION"
      ? item.parameters.inputValues : item.parameters.invalidValues);
    if (labels.length !== values.length) issues.push("Số ô nhập và số giá trị thử chưa khớp");
    if (runner === "TEMPLATE_FORM_VALIDATION") {
      const errors = csvValues(item.parameters.errorTexts);
      const errorFields = csvValues(item.parameters.errorFieldLabels);
      if (errorFields.length > 0 && errorFields.length !== errors.length) {
        issues.push("Số field chứa lỗi và số thông báo lỗi chưa khớp");
      }
    }
  }
  if (["TEMPLATE_UI_WORKFLOW", "KEY_WORKFLOW"].includes(runner) && !isBlankParameter(item.parameters.stepsJson)) {
    try {
      const steps = JSON.parse(String(item.parameters.stepsJson));
      if (!Array.isArray(steps) || steps.length === 0) issues.push("Workflow phải có ít nhất một bước");
    } catch {
      issues.push("Workflow chưa phải JSON hợp lệ");
    }
  }
  for (const [runnerName, field, label] of [
    ["RESPONSIVE_LAYOUT_CASES", "casesJson", "Danh sách viewport"],
    ["PROJECT_FILE_CONTRACT", "filesJson", "Danh sách file contract"],
  ] as const) {
    if (runner === runnerName && !isBlankParameter(item.parameters[field])) {
      try {
        const rows = JSON.parse(String(item.parameters[field]));
        if (!Array.isArray(rows) || rows.length === 0) issues.push(`${label} phải có ít nhất một phần tử`);
      } catch {
        issues.push(`${label} chưa phải JSON hợp lệ`);
      }
    }
  }
  if (["STARTER_CALL_SEQUENCE", "PROCESS_PERSISTENCE_SEQUENCE"].includes(runner)) {
    const fields = runner === "PROCESS_PERSISTENCE_SEQUENCE"
      ? ["seedStepsJson", "verifyStepsJson"] : ["stepsJson"];
    for (const field of fields) {
      if (isBlankParameter(item.parameters[field])) continue;
      try {
        const steps = JSON.parse(String(item.parameters[field]));
        if (!Array.isArray(steps) || steps.length === 0) {
          issues.push(`${PARAMETER_LABELS[field] || field} phải có ít nhất một bước`);
        } else if (steps.some((step) => !step || typeof step !== "object" || !String(step.functionName || "").trim())) {
          issues.push(`Mỗi bước trong ${PARAMETER_LABELS[field] || field} phải có functionName`);
        }
      } catch {
        issues.push(`${PARAMETER_LABELS[field] || field} chưa phải JSON hợp lệ`);
      }
    }
  }
  for (const step of item.setup_steps || []) {
    if (!step.key.trim()) {
      issues.push("Có bước chuẩn bị chưa nhập key");
      break;
    }
  }
  if (!item.expected.trim()) issues.push("Chưa có mô tả rubric khi pass");
  if (!Number.isFinite(Number(item.weight)) || Number(item.weight) <= 0) issues.push("Trọng số phải lớn hơn 0");

  return issues.length ? { state: "attention", issues } : { state: "ready", issues: [] };
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
    key_contract: { source_path: "lib/grading/app_keys.dart", class_name: "AppKeys", keys: [] },
  };
}

function templateContractSuite(): SuiteConfig {
  return {
    ...emptySuite(),
    name: "Contract template của đề",
    context: "dynamic_template_contract",
    strict_semantic_keys: false,
    profile: "TEMPLATE_CONTRACT_V1",
    required_keys: "",
    source_contracts: [],
    setup_steps: [],
    persistence: { enabled: false, storage_kind: "none", reload_key: "", notes: "", reset_steps: [] },
    golden: { enabled: false, portrait_asset: "", landscape_asset: "", threshold: 0.01 },
  };
}

function hybridStarterKeySuite(): SuiteConfig {
  return {
    ...templateContractSuite(),
    name: "Starter TODO + semantic Key",
    context: "starter_key_hybrid",
    strict_semantic_keys: true,
    profile: "STARTER_KEY_HYBRID_V1",
  };
}

function suiteForEngine(engine: EngineMode): SuiteConfig {
  if (engine === "STARTER_KEY_HYBRID_V1") return hybridStarterKeySuite();
  return engine === "TEMPLATE_CONTRACT_V1" ? templateContractSuite() : emptySuite();
}

function usesStarterContract(engine?: string) {
  return engine === "TEMPLATE_CONTRACT_V1" || engine === "STARTER_KEY_HYBRID_V1";
}

function usesSemanticKeys(engine?: string) {
  return engine === "COMMON_V1" || engine === "STARTER_KEY_HYBRID_V1";
}

function runnerUsesStarterContract(engine: EngineMode, runner?: string) {
  return engine === "TEMPLATE_CONTRACT_V1"
    || (engine === "STARTER_KEY_HYBRID_V1"
      && (String(runner || "").startsWith("TEMPLATE_")
        || runner === "DIRECT_FUNCTION"
        || runner === "DIRECT_FUNCTION_THROWS"
        || runner === "DIRECT_STREAM_EVENTS"
        || runner === "PROJECT_FILE_CONTRACT"
        || runner === "STARTER_CALL_SEQUENCE"
        || runner === "PROCESS_PERSISTENCE_SEQUENCE"));
}

function hasContractBindings(template?: Template): template is Template {
  return Boolean(template && Object.keys(template.contract_bindings || {}).length > 0);
}

const DEFAULT_CONTRACT_SECTIONS: Array<Omit<TemplateContractSection, "fields"> & { fields: Array<Omit<TemplateContractField, "id" | "value">> }> = [
  { id: "app", name: "Ứng dụng / project", fields: [
    { key: "app.rootKey", label: "Root Key (nếu starter có công bố)", kind: "text" },
    { key: "app.readyText", label: "Nội dung báo ứng dụng đã sẵn sàng", kind: "text" },
    { key: "app.readyTimeoutMs", label: "Thời gian chờ sẵn sàng (ms)", kind: "number" },
    { key: "source.path", label: "File contract tổng quát", kind: "path" },
    { key: "source.symbols", label: "Các symbol bắt buộc", kind: "csv" },
    { key: "source.symbolTypes", label: "Loại tương ứng của các symbol", kind: "csv" },
    { key: "source.requiredTerms", label: "Nội dung source bắt buộc", kind: "csv" },
    { key: "source.forbiddenTerms", label: "Nội dung source không được có", kind: "csv" },
    { key: "project.filesJson", label: "Contract nhiều file project (JSON)", kind: "json" },
  ] },
  { id: "model", name: "Model / dữ liệu", fields: [
    { key: "model.path", label: "File model", kind: "path" },
    { key: "model.class", label: "Class model", kind: "identifier" },
    { key: "model.fields", label: "Các field (field:type)", kind: "csv" },
    { key: "model.copyMethod", label: "Method tạo bản sao", kind: "identifier" },
    { key: "model.toMapMethod", label: "Method chuyển thành Map", kind: "identifier" },
    { key: "model.fromMapMethod", label: "Method tạo từ Map", kind: "identifier" },
  ] },
  { id: "storage", name: "Lưu trữ / API", fields: [
    { key: "storage.path", label: "File storage/database", kind: "path" },
    { key: "storage.schemaMethod", label: "Method tạo schema cần kiểm tra", kind: "identifier" },
    { key: "storage.table", label: "Tên bảng/collection", kind: "text" },
    { key: "storage.columns", label: "Các cột/JSON keys", kind: "csv" },
    { key: "storage.requiredTerms", label: "Dấu hiệu source bắt buộc", kind: "csv" },
    { key: "storage.forbiddenTerms", label: "Dấu hiệu source không được có", kind: "csv" },
  ] },
  { id: "service", name: "Repository / service", fields: [
    { key: "service.path", label: "File repository/service", kind: "path" },
    { key: "service.class", label: "Class repository/service", kind: "identifier" },
    { key: "service.methods", label: "Các method công khai", kind: "csv" },
  ] },
  { id: "logic", name: "API nghiệp vụ có sẵn trong starter", fields: [
    { key: "logic.path", label: "File chứa hàm nghiệp vụ", kind: "path" },
    { key: "logic.function", label: "Hàm top-level cần kiểm tra", kind: "identifier" },
  ] },
  { id: "state", name: "State / ViewModel", fields: [
    { key: "state.path", label: "File state/ViewModel", kind: "path" },
    { key: "state.class", label: "Class state/ViewModel", kind: "identifier" },
    { key: "state.methods", label: "Các method/state bắt buộc", kind: "csv" },
    { key: "state.requiredTerms", label: "Provider/import/annotation bắt buộc", kind: "csv" },
  ] },
  { id: "ui", name: "Màn hình / form", fields: [
    { key: "ui.fieldLabels", label: "Label/hint các ô", kind: "csv" },
    { key: "ui.formIndex", label: "Vị trí Form (bắt đầu từ 1)", kind: "number" },
    { key: "ui.formAnchorText", label: "Nội dung nhận diện bên trong Form", kind: "text" },
    { key: "ui.scopeType", label: "Loại vùng UI cần kiểm tra", kind: "text" },
    { key: "ui.scopeIndex", label: "Vị trí vùng UI (bắt đầu từ 1)", kind: "number" },
    { key: "ui.scopeAnchorText", label: "Nội dung nhận diện trong vùng UI", kind: "text" },
    { key: "ui.resultScopeType", label: "Loại vùng hiển thị kết quả", kind: "text" },
    { key: "ui.resultScopeIndex", label: "Vị trí vùng kết quả", kind: "number" },
    { key: "ui.resultScopeAnchorText", label: "Nội dung nhận diện vùng kết quả", kind: "text" },
    { key: "ui.textMatchMode", label: "Cách so khớp nội dung", kind: "text" },
    { key: "ui.minimumOccurrences", label: "Số lần xuất hiện tối thiểu", kind: "number" },
    { key: "ui.buttonLabels", label: "Nội dung các nút", kind: "csv" },
    { key: "ui.expectedTexts", label: "Nội dung phải xuất hiện", kind: "csv" },
    { key: "ui.absentTexts", label: "Nội dung không được xuất hiện", kind: "csv" },
    { key: "ui.errorFieldLabels", label: "Field chứa từng thông báo lỗi", kind: "csv" },
    { key: "ui.errorTextMatchMode", label: "Cách so khớp thông báo lỗi", kind: "text" },
  ] },
  { id: "behavior", name: "Dữ liệu và luồng hành vi", fields: [
    { key: "behavior.inputValues", label: "Dữ liệu nhập thử", kind: "csv" },
    { key: "behavior.actionLabel", label: "Nút thực hiện", kind: "text" },
    { key: "behavior.errorTexts", label: "Thông báo validation", kind: "csv" },
    { key: "behavior.requireNewResult", label: "Kết quả phải được tạo mới sau action", kind: "text" },
    { key: "behavior.requireNewErrors", label: "Lỗi phải xuất hiện mới sau submit", kind: "text" },
    { key: "behavior.stepsJson", label: "Các bước luồng (JSON)", kind: "json" },
    { key: "behavior.keyStepsJson", label: "Các bước luồng theo semantic Key (JSON)", kind: "json" },
  ] },
  { id: "responsive", name: "Responsive", fields: [
    { key: "responsive.portraitWidth", label: "Rộng điện thoại", kind: "number" },
    { key: "responsive.portraitHeight", label: "Cao điện thoại", kind: "number" },
    { key: "responsive.landscapeWidth", label: "Rộng máy tính/tablet", kind: "number" },
    { key: "responsive.landscapeHeight", label: "Cao máy tính/tablet", kind: "number" },
    { key: "responsive.portraitExpectedTexts", label: "Nội dung cần thấy ở điện thoại", kind: "csv" },
    { key: "responsive.landscapeExpectedTexts", label: "Nội dung cần thấy ở màn hình rộng", kind: "csv" },
    { key: "responsive.casesJson", label: "Các breakpoint và marker Key (JSON)", kind: "json" },
  ] },
];

const contractId = () => `contract_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

function emptyTemplateContract(): TemplateContractDraft {
  return {
    version: 5,
    sections: DEFAULT_CONTRACT_SECTIONS.map((section) => ({
      id: section.id,
      name: section.name,
      fields: section.fields.map((field) => ({ ...field, id: contractId(), value: "" })),
    })),
  };
}

const CONTRACT_V3_ADDITION_KEYS = new Set([
  "app.readyText", "app.readyTimeoutMs", "source.symbolTypes", "storage.schemaMethod",
  "ui.formIndex", "ui.formAnchorText", "ui.scopeType", "ui.scopeIndex", "ui.scopeAnchorText",
  "ui.resultScopeType", "ui.resultScopeIndex", "ui.resultScopeAnchorText", "ui.textMatchMode",
  "ui.minimumOccurrences", "behavior.requireNewResult", "behavior.requireNewErrors",
  "responsive.portraitExpectedTexts", "responsive.landscapeExpectedTexts",
]);

const CONTRACT_V4_ADDITION_KEYS = new Set([
  "ui.errorFieldLabels", "ui.errorTextMatchMode",
]);

const CONTRACT_V5_ADDITION_KEYS = new Set([
  "project.filesJson", "behavior.keyStepsJson", "responsive.casesJson",
]);

const CONTRACT_HYBRID_ADDITION_KEYS = new Set([
  "logic.path", "logic.function",
]);

const LEGACY_CONTRACT_KEYS: Record<string, string> = {
  modelPath: "model.path", modelClass: "model.class", modelFields: "model.fields",
  databasePath: "storage.path", tableName: "storage.table", columns: "storage.columns",
  repositoryPath: "service.path", repositoryClass: "service.class", repositoryMethods: "service.methods",
  fieldLabels: "ui.fieldLabels", buttonLabels: "ui.buttonLabels",
  inputValues: "behavior.inputValues", expectedTexts: "ui.expectedTexts",
};

function normalizeTemplateContract(raw: unknown): TemplateContractDraft {
  const base = emptyTemplateContract();
  if (!raw || typeof raw !== "object") return base;
  const object = raw as Record<string, unknown>;
  const incomingVersion = Number(object.version || 2);
  const incoming = Array.isArray(object.sections) ? object.sections : [];
  if (incoming.length > 0) {
    const sections = incoming.flatMap((value) => {
      if (!value || typeof value !== "object") return [];
      const section = value as Record<string, unknown>;
      const fields = Array.isArray(section.fields) ? section.fields.flatMap((fieldValue) => {
        if (!fieldValue || typeof fieldValue !== "object") return [];
        const field = fieldValue as Record<string, unknown>;
        const key = String(field.key || "").trim();
        if (!key) return [];
        const kind = String(field.kind || "text") as ContractValueKind;
        return [{ id: String(field.id || contractId()), key, label: String(field.label || key), value: String(field.value || ""), kind }];
      }) : [];
      return [{ id: String(section.id || contractId()), name: String(section.name || "Nhóm contract"), fields }];
    });
    const existingKeys = new Set(sections.flatMap((section) => section.fields.map((field) => field.key)));
    if (incomingVersion < 3) {
      base.sections.forEach((baseSection) => {
        const additions = baseSection.fields.filter((field) =>
          CONTRACT_V3_ADDITION_KEYS.has(field.key) && !existingKeys.has(field.key));
        if (!additions.length) return;
        const target = sections.find((section) => section.id === baseSection.id);
        if (target) target.fields.push(...additions);
        else sections.push({ ...baseSection, fields: additions });
        additions.forEach((field) => existingKeys.add(field.key));
      });
    }
    if (incomingVersion < 4) {
      base.sections.forEach((baseSection) => {
        const additions = baseSection.fields.filter((field) =>
          CONTRACT_V4_ADDITION_KEYS.has(field.key) && !existingKeys.has(field.key));
        if (!additions.length) return;
        const target = sections.find((section) => section.id === baseSection.id);
        if (target) target.fields.push(...additions);
        else sections.push({ ...baseSection, fields: additions });
        additions.forEach((field) => existingKeys.add(field.key));
      });
    }
    if (incomingVersion < 5) {
      base.sections.forEach((baseSection) => {
        const additions = baseSection.fields.filter((field) =>
          CONTRACT_V5_ADDITION_KEYS.has(field.key) && !existingKeys.has(field.key));
        if (!additions.length) return;
        const target = sections.find((section) => section.id === baseSection.id);
        if (target) target.fields.push(...additions);
        else sections.push({ ...baseSection, fields: additions });
        additions.forEach((field) => existingKeys.add(field.key));
      });
    }
    base.sections.forEach((baseSection) => {
      const additions = baseSection.fields.filter((field) =>
        CONTRACT_HYBRID_ADDITION_KEYS.has(field.key) && !existingKeys.has(field.key));
      if (!additions.length) return;
      const target = sections.find((section) => section.id === baseSection.id);
      if (target) target.fields.push(...additions);
      else sections.push({ ...baseSection, fields: additions });
      additions.forEach((field) => existingKeys.add(field.key));
    });
    return { version: 5, sections };
  }
  const values = new Map(Object.entries(LEGACY_CONTRACT_KEYS).map(([oldKey, newKey]) => [newKey, String(object[oldKey] || "")]));
  return { ...base, sections: base.sections.map((section) => ({ ...section, fields: section.fields.map((field) => ({ ...field, value: values.get(field.key) || "" })) })) };
}

function normalizeSemanticKeyContract(raw: unknown): SemanticKeyContract {
  const fallback: SemanticKeyContract = {
    source_path: "lib/grading/app_keys.dart",
    class_name: "AppKeys",
    keys: [],
  };
  if (!raw || typeof raw !== "object") return fallback;
  const value = raw as Record<string, unknown>;
  const keys = Array.isArray(value.keys) ? value.keys.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const key = entry as Record<string, unknown>;
    const semanticValue = String(key.value || "").trim();
    if (!semanticValue) return [];
    return [{
      symbol: String(key.symbol || "").trim(),
      value: semanticValue,
      group: String(key.group || "Khác").trim() || "Khác",
      description: String(key.description || "").trim(),
    }];
  }) : [];
  return {
    source_path: String(value.source_path || fallback.source_path),
    class_name: String(value.class_name || fallback.class_name),
    keys,
  };
}

const SEMANTIC_KEY_PATTERN = /^[a-z][a-z0-9_-]*(?:\.[a-z0-9_-]+)+$/;

function isSemanticKeyParameter(parameter: string) {
  return /(?:^|_)(?:key|keys)$/i.test(parameter)
    || /(?:Key|Keys)$/.test(parameter);
}

function isSemanticKeyListParameter(parameter: string) {
  return /keys$/i.test(parameter) && !/key$/i.test(parameter);
}

function applyTemplateContract(
  template: Template,
  current: JsonMap,
  contract: TemplateContractDraft,
  resetMissing = false,
): JsonMap {
  const next = { ...current };
  const values = new Map<string, string>();
  contract.sections.forEach((section) => section.fields.forEach((field) => {
    if (field.key.trim() && field.value.trim()) values.set(field.key.trim(), field.value.trim());
  }));
  for (const parameter of Object.keys(template.parameters_schema || {})) {
    const contractKey = template.contract_bindings?.[parameter] || parameter;
    const value = values.get(contractKey);
    if (value !== undefined) next[parameter] = value;
    else if (resetMissing) next[parameter] = template.parameters_schema[parameter];
  }
  return next;
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
  if (["APP_BOOT", "NAVIGATION", "BUTTON_ACTION", "WIDGET_ENABLED", "DIALOG_FLOW", "FORM_PREFILL", "FORM_SUBMIT", "KEY_WORKFLOW", "FORM_FOCUS_FLOW"].includes(runner)) return "BEHAVIOR";
  if (["FORM_REQUIRED_FIELDS", "FORM_VALIDATE_FIELDS", "LIST_ITEM_COUNT", "STATE_REACTIVE_FLOW", "PROJECT_FILE_CONTRACT", "DIRECT_FUNCTION_THROWS", "DIRECT_STREAM_EVENTS", "PROCESS_PERSISTENCE_SEQUENCE"].includes(runner)) return "LOGIC";
  if (layer === "RESPONSIVE" || runner.startsWith("WIDGET_") || runner === "LIST_VISIBLE") return "WIDGET";
  return "LOGIC";
}

const SEMANTIC_TARGET_TYPE_OPTIONS = [
  "any", "form", "image", "text", "input", "button", "dialog", "icon",
  "checkbox", "switch", "slider", "radio", "chip", "dropdown", "padding",
  "container", "list", "grid", "scrollable", "hero", "materialapp", "safearea",
  "scaffold", "card", "listtile", "row", "column", "stack", "indexedstack",
  "expanded", "layoutbuilder", "table", "bottomsheet", "customscrollview",
  "sliverlist", "slivergrid", "bottomnavigationbar", "navigationbar",
  "futurebuilder", "streambuilder", "animatedcontainer", "animatedopacity",
  "animatedbuilder", "inheritedwidget",
];

const PARAMETER_OPTIONS: Record<string, string[]> = {
  targetType: SEMANTIC_TARGET_TYPE_OPTIONS,
  fromType: SEMANTIC_TARGET_TYPE_OPTIONS,
  toType: SEMANTIC_TARGET_TYPE_OPTIONS,
  ancestorType: SEMANTIC_TARGET_TYPE_OPTIONS,
  dimension: ["height", "width"],
  comparison: ["equals", "at_least", "at_most"],
  axis: ["vertical", "horizontal"],
  orderedAxis: ["none", "vertical", "horizontal"],
  alignment: ["column", "row"],
  fontWeight: ["w400", "w500", "w600", "w700", "w800"],
  expectedType: ["string", "bool", "int", "double", "json", "null"],
  matchMode: ["equals", "contains"],
  typeMatchMode: ["equals", "contains"],
  property: ["enabled", "obscureText", "readOnly", "keyboardType", "textInputAction", "autovalidateMode", "value", "selected", "min", "max", "divisions", "maxLines", "minLines", "maxLength", "scrollDirection", "crossAxisCount", "heroTag", "themeMode"],
  scopeType: ["", "screen", "form", "dialog", "list", "appbar", "bottomsheet"],
  resultScopeType: ["", "screen", "form", "dialog", "list", "appbar", "bottomsheet"],
  textMatchMode: ["contains", "exact"],
  resultTextMatchMode: ["contains", "exact"],
  errorTextMatchMode: ["contains", "exact"],
  requireNewResult: ["true", "false"],
  requireNewErrors: ["true", "false"],
  requireNewDestination: ["true", "false"],
  hideDestinationAfterBack: ["true", "false"],
  requireNewDialog: ["true", "false"],
  requireNewUpdatedState: ["true", "false"],
  requirePrefillTransition: ["true", "false"],
  dismissAfterLast: ["true", "false"],
};

const PARAMETER_LABELS: Record<string, string> = {
  sourcePath: "Đường dẫn file trong starter",
  symbols: "Class / hàm / provider bắt buộc",
  symbolTypes: "Loại symbol tương ứng (class,function,variable,...)",
  requiredTerms: "Nội dung source bắt buộc",
  forbiddenTerms: "Nội dung source không được có",
  className: "Tên class",
  fields: "Danh sách field (field:type)",
  copyMethod: "Tên method tạo bản sao",
  toMapMethod: "Tên hàm ghi Map",
  fromMapMethod: "Tên hàm đọc Map",
  columns: "Danh sách cột dữ liệu",
  tableName: "Tên bảng SQLite",
  schemaMethod: "Method tạo schema cần kiểm tra",
  methods: "Các method bắt buộc",
  fieldLabels: "Label/hint các ô nhập",
  formIndex: "Vị trí Form (bắt đầu từ 1)",
  formAnchorText: "Nội dung nhận diện bên trong Form",
  scopeType: "Loại vùng UI",
  scopeIndex: "Vị trí vùng UI (bắt đầu từ 1)",
  scopeAnchorText: "Nội dung nhận diện trong vùng UI",
  resultScopeType: "Loại vùng hiển thị kết quả",
  resultScopeIndex: "Vị trí vùng kết quả",
  resultScopeAnchorText: "Nội dung nhận diện vùng kết quả",
  textMatchMode: "Cách so khớp nội dung",
  resultTextMatchMode: "Cách so khớp kết quả",
  minimumOccurrences: "Số lần xuất hiện tối thiểu",
  buttonLabels: "Nội dung các nút",
  inputValues: "Dữ liệu nhập thử",
  actionLabel: "Nội dung nút thao tác",
  expectedTexts: "Nội dung phải xuất hiện",
  errorTexts: "Nội dung lỗi phải xuất hiện",
  errorFieldLabels: "Field chứa từng thông báo lỗi (tùy chọn)",
  errorTextMatchMode: "Cách so khớp thông báo lỗi",
  requireNewResult: "Kết quả phải xuất hiện mới sau action",
  requireNewErrors: "Lỗi phải xuất hiện mới sau submit",
  requireNewDestination: "Màn hình đích phải được mở mới sau thao tác",
  hideDestinationAfterBack: "Màn hình đích phải ẩn sau khi quay lại",
  requireNewDialog: "Dialog phải được mở mới sau thao tác",
  requireNewUpdatedState: "Trạng thái mới phải xuất hiện sau thao tác",
  requirePrefillTransition: "Nút Edit phải tạo thay đổi prefill quan sát được",
  readyKey: "Key báo ứng dụng đã sẵn sàng",
  readyText: "Nội dung báo ứng dụng đã sẵn sàng",
  readyTimeoutMs: "Thời gian chờ sẵn sàng (ms)",
  portraitExpectedTexts: "Nội dung cần thấy ở điện thoại",
  landscapeExpectedTexts: "Nội dung cần thấy ở màn hình rộng",
  stepsJson: "Các bước workflow (JSON)",
  seedStepsJson: "Các bước reset/ghi ở process seed (JSON)",
  verifyStepsJson: "Các bước đọc/đối chiếu ở process mới (JSON)",
  fixtureNamespace: "Mã namespace storage cô lập",
  casesJson: "Các viewport responsive (JSON)",
  filesJson: "Các file và contract nội dung (JSON)",
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
  ancestorKey: "Key vùng cha",
  ancestorType: "Loại vùng cha",
  descendantKeys: "Các Key phải nằm trong vùng cha",
  descendantTypes: "Loại tương ứng của các widget con",
  orderedAxis: "Trục kiểm tra thứ tự",
  firstKey: "Key vùng thứ nhất",
  secondKey: "Key vùng thứ hai",
  alignment: "Cách xếp hàng/cột",
  width: "Chiều rộng viewport",
  height: "Chiều cao viewport",
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
  expectedEventsJson: "Chuỗi event chuẩn (JSON array)",
  expectedException: "Tên loại exception mong đợi",
  typeMatchMode: "Cách so khớp tên exception",
  messageContains: "Nội dung message phải chứa (tùy chọn)",
  timeoutMs: "Thời gian chờ tối đa (ms)",
  property: "Thuộc tính widget cần đọc",
  actions: "TextInputAction tương ứng từng field",
  dismissAfterLast: "Đóng focus sau action cuối",
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
  const [engineMode, setEngineMode] = useState<EngineMode>("STARTER_KEY_HYBRID_V1");
  const [templateContract, setTemplateContract] = useState<TemplateContractDraft>(emptyTemplateContract);
  const [skillOptions, setSkillOptions] = useState<SkillOption[]>([]);
  const [examId, setExamId] = useState("");
  const [examName, setExamName] = useState("");
  const [teacherNote, setTeacherNote] = useState("");
  const [items, setItems] = useState<TestcaseItem[]>([]);
  const [suite, setSuite] = useState<SuiteConfig>(hybridStarterKeySuite);
  const [status, setStatus] = useState("");
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<"draft" | "publish" | null>(null);
  const [message, setMessage] = useState<{ type: "ok" | "error"; text: string } | null>(null);
  const [selectedCategory, setSelectedCategory] = useState("ALL");
  const [search, setSearch] = useState("");
  const [itemGroupFilter, setItemGroupFilter] = useState("ALL");
  const [itemProgressFilter, setItemProgressFilter] = useState<"ALL" | ItemProgressState>("ALL");
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
    engine_type: "COMMON_V1",
    template_id: "", name: "", description: "", skill_code: "UI_TEXT_INPUT", layer: "SCREEN",
    testcase_group: "LOGIC", difficulty: "basic", weight_default: "1", runner: "FORM_VALIDATE_FIELDS",
    parameters_schema: '{"fieldKeys":"field.name,field.email","invalidValues":"invalid-name,invalid-email","submitKey":"action.save","errorKeys":"error.name,error.email","fieldType":"input"}',
    contract_bindings: "{}",
    expected_template: "Khi nhập dữ liệu không hợp lệ, các ô nhập phải hiển thị lỗi tương ứng.",
  });

  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewFiles, setPreviewFiles] = useState<Array<{ name: string; content: string }>>([]);
  const [previewFile, setPreviewFile] = useState(0);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [keyPaletteSearch, setKeyPaletteSearch] = useState("");
  const [copiedKey, setCopiedKey] = useState("");

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
            const resolvedEngine: EngineMode = loadedEngine === "STARTER_KEY_HYBRID_V1"
                || loadedEngine === "TEMPLATE_CONTRACT_V1" || loadedEngine === "COMMON_V1"
              ? loadedEngine : "STARTER_KEY_HYBRID_V1";
            setEngineMode(resolvedEngine);
            if (config.suite && typeof config.suite === "object") {
              const loaded = config.suite as Partial<SuiteConfig>;
              if (loaded.template_contract && typeof loaded.template_contract === "object") {
                setTemplateContract(normalizeTemplateContract(loaded.template_contract));
              }
              setSuite({
                ...suiteForEngine(resolvedEngine),
                ...loaded,
                required_keys: Array.isArray(loaded.required_keys)
                  ? loaded.required_keys.join(", ")
                  : String(loaded.required_keys || ""),
                setup_steps: Array.isArray(loaded.setup_steps) ? loaded.setup_steps : [],
                key_contract: normalizeSemanticKeyContract(loaded.key_contract),
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
      fetch(`${API_BASE}/syllabus/skills?testable=auto`, { headers }).then((r) => r.ok ? r.json() : []),
    ])
      .then(([templateRows, skillRows]) => {
        const loadedTemplates = Array.isArray(templateRows) ? templateRows as Template[] : [];
        const loadedSkills = Array.isArray(skillRows) ? (skillRows as SkillOption[]).filter((skill) => !skill.deprecated) : [];
        setTemplates(loadedTemplates);
        setSkillOptions(loadedSkills);
        setSelectedCategory("ALL");
      })
      .catch(() => setMessage({ type: "error", text: "Không tải được thư viện testcase hoặc syllabus." }))
      .finally(() => setLoading(false));
  }, []);

  // Draft cũ có thể thiếu tham số mới được bổ sung vào blueprint (ví dụ bộ chọn
  // Form). Bổ sung riêng các giá trị mặc định còn thiếu để giảng viên thấy và cấu
  // hình được ngay, nhưng không ghi đè tham số/override đã nhập trước đó.
  useEffect(() => {
    if (!templates.length) return;
    const loadedTemplateMap = new Map(templates.map((template) => [template.template_id, template]));
    setItems((current) => current.map((item) => {
      const schema = loadedTemplateMap.get(item.template_id)?.parameters_schema || {};
      const parameters = { ...(item.parameters || {}) };
      let changed = false;
      Object.entries(schema).forEach(([key, value]) => {
        if (!(key in parameters)) {
          parameters[key] = value;
          changed = true;
        }
      });
      return changed ? { ...item, parameters } : item;
    }));
  }, [templates]);

  const templatesForEngine = useMemo(
    () => templates.filter((template) =>
      (template.engine_type || "COMMON_V1") === engineMode
      && template.template_id !== "COMMON_GRADING_ADAPTER_CALL"),
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
  const semanticKeyCatalog = useMemo(() => {
    const catalog = new Map<string, SemanticKeyDefinition & { declared: boolean }>();
    const add = (value: unknown, detail?: Partial<SemanticKeyDefinition>, declared = false) => {
      const normalized = String(value || "").trim();
      if (!SEMANTIC_KEY_PATTERN.test(normalized)) return;
      const current = catalog.get(normalized);
      if (current?.declared && !declared) return;
      catalog.set(normalized, {
        symbol: detail?.symbol || current?.symbol || "",
        value: normalized,
        group: detail?.group || current?.group || (declared ? "Khác" : "Đang dùng trong Draft"),
        description: detail?.description || current?.description || "",
        declared: declared || Boolean(current?.declared),
      });
    };

    suite.key_contract.keys.forEach((key) => add(key.value, key, true));
    add(suite.ready_key, { description: "Key báo ứng dụng sẵn sàng" });
    String(suite.required_keys || "").split(",").forEach((key) =>
      add(key, { description: "Key bắt buộc của khung chấm" }));
    suite.setup_steps.forEach((step) => add(step.key, { description: "Đang dùng trong setup chung" }));
    if (suite.persistence.reload_key) {
      add(suite.persistence.reload_key, { description: "Key reload persistence" });
    }
    templateContract.sections.forEach((section) => section.fields.forEach((field) => {
      if (field.key === "app.rootKey") add(field.value, { description: "Root Key trong contract đề" });
    }));
    items.forEach((item) => {
      Object.entries(item.parameters || {}).forEach(([parameter, raw]) => {
        if (!isSemanticKeyParameter(parameter)) return;
        String(raw ?? "").split(",").forEach((key) =>
          add(key, { description: `Đang dùng bởi ${item.instance_id}` }));
      });
      (item.setup_steps || []).forEach((step) =>
        add(step.key, { description: `Setup của ${item.instance_id}` }));
    });
    return Array.from(catalog.values()).sort((left, right) =>
      left.group.localeCompare(right.group, "vi") || left.value.localeCompare(right.value));
  }, [items, suite.key_contract.keys, suite.persistence.reload_key, suite.ready_key,
    suite.required_keys, suite.setup_steps, templateContract]);
  const filteredSemanticKeys = useMemo(() => {
    const query = keyPaletteSearch.trim().toLocaleLowerCase("vi");
    if (!query) return semanticKeyCatalog;
    return semanticKeyCatalog.filter((key) =>
      `${key.symbol} ${key.value} ${key.group} ${key.description}`.toLocaleLowerCase("vi").includes(query));
  }, [keyPaletteSearch, semanticKeyCatalog]);
  const keyContractIssues = useMemo(() => {
    const issues: string[] = [];
    const sourcePath = suite.key_contract.source_path.trim().replaceAll("\\", "/");
    if (sourcePath && (!sourcePath.startsWith("lib/") || sourcePath.includes("..") || !sourcePath.endsWith(".dart"))) {
      issues.push("File Key phải là đường dẫn .dart tương đối trong lib/");
    }
    if (suite.key_contract.class_name && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(suite.key_contract.class_name)) {
      issues.push("Class Key chưa phải Dart identifier hợp lệ");
    }
    const symbols = new Set<string>();
    const values = new Set<string>();
    suite.key_contract.keys.forEach((key, index) => {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key.symbol)) issues.push(`Key #${index + 1} có tên constant không hợp lệ`);
      else if (symbols.has(key.symbol)) issues.push(`Tên constant bị trùng: ${key.symbol}`);
      if (!SEMANTIC_KEY_PATTERN.test(key.value)) issues.push(`Key #${index + 1} chưa đúng dạng screen.home`);
      else if (values.has(key.value)) issues.push(`Giá trị Key bị trùng: ${key.value}`);
      symbols.add(key.symbol);
      values.add(key.value);
    });
    return Array.from(new Set(issues));
  }, [suite.key_contract]);
  const undeclaredUsedKeys = useMemo(
    () => semanticKeyCatalog.filter((key) => !key.declared),
    [semanticKeyCatalog],
  );
  const activeEngine = items.length
    ? (items[0].engine_type || templateMap.get(items[0].template_id)?.engine_type)
    : engineMode;
  const supportsGrouping = activeEngine === "COMMON_V1";
  const totalWeight = items.reduce((sum, item) => item.enabled ? sum + Number(item.weight || 0) : sum, 0);
  const itemProgressMap = useMemo(() => new Map(items.map((item) => [
    item.instance_id,
    testcaseProgress(item, templateMap.get(item.template_id)),
  ])), [items, templateMap]);
  const activeItemCount = items.filter((item) => item.enabled).length;
  const readyItemCount = items.filter((item) => itemProgressMap.get(item.instance_id)?.state === "ready").length;
  const attentionItemCount = items.filter((item) => itemProgressMap.get(item.instance_id)?.state === "attention").length;
  const disabledItemCount = items.length - activeItemCount;
  const progressPercent = activeItemCount === 0 ? 0 : Math.round((readyItemCount / activeItemCount) * 100);
  const progressGroups = useMemo(() => TESTCASE_GROUP_ORDER.slice(1).map((code) => {
    const groupItems = items.filter((item) => {
      const template = templateMap.get(item.template_id);
      return item.enabled && (item.testcase_group || (template ? testcaseGroup(template) : "LOGIC")) === code;
    });
    return {
      code,
      count: groupItems.length,
      ready: groupItems.filter((item) => itemProgressMap.get(item.instance_id)?.state === "ready").length,
      weight: groupItems.reduce((sum, item) => sum + Number(item.weight || 0), 0),
    };
  }), [items, itemProgressMap, templateMap]);
  const filteredItems = useMemo(() => items.filter((item) => {
    const template = templateMap.get(item.template_id);
    const group = item.testcase_group || (template ? testcaseGroup(template) : "LOGIC");
    const progress = itemProgressMap.get(item.instance_id)?.state || "attention";
    return (itemGroupFilter === "ALL" || group === itemGroupFilter)
      && (itemProgressFilter === "ALL" || progress === itemProgressFilter);
  }), [items, itemGroupFilter, itemProgressFilter, itemProgressMap, templateMap]);
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
    setSuite(suiteForEngine(next));
    setMessage(null);
  };

  const openNewTemplate = () => {
    const builtIn = templatesForEngine[0];
    if (!builtIn) {
      setMessage({ type: "error", text: "Engine hiện tại chưa có runner mẫu để tạo testcase mới." });
      return;
    }
    setNewTemplate((current) => ({
      ...current,
      engine_type: engineMode,
      runner: builtIn.runner || "APP_BOOT",
      parameters_schema: JSON.stringify(builtIn.parameters_schema || {}, null, 2),
      contract_bindings: JSON.stringify(builtIn.contract_bindings || {}, null, 2),
      testcase_group: testcaseGroup(builtIn),
      layer: builtIn.layer,
      skill_code: builtIn.skill_code,
      name: "",
      description: "",
      expected_template: builtIn.expected_template,
    }));
    setNewTemplateError("");
    setNewTemplateOpen(true);
  };

  const changeNewTemplateRunner = (runner: string) => {
    const builtIn = templates.find((template) => template.engine_type === engineMode && template.runner === runner && !template.custom);
    setNewTemplate((current) => ({
      ...current,
      runner,
      parameters_schema: builtIn ? JSON.stringify(builtIn.parameters_schema || {}, null, 2) : current.parameters_schema,
      contract_bindings: builtIn ? JSON.stringify(builtIn.contract_bindings || {}, null, 2) : current.contract_bindings,
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
      let bindings: unknown = {};
      if (runnerUsesStarterContract(engineMode, newTemplate.runner)) {
        try { bindings = JSON.parse(newTemplate.contract_bindings); }
        catch { throw new Error("Ánh xạ contract phải là JSON object hợp lệ."); }
        if (!bindings || typeof bindings !== "object" || Array.isArray(bindings))
          throw new Error("Ánh xạ contract phải là JSON object.");
      }
      const response = await fetch(`${API_BASE}/testcase-templates`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken() ?? ""}` },
        body: JSON.stringify({ ...newTemplate, engine_type: engineMode, weight_default: Number(newTemplate.weight_default), parameters_schema: schema, contract_bindings: bindings }),
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
    const usedIds = new Set(items.map((item) => item.instance_id));
    let nextNumber = items.length + 1;
    while (usedIds.has(`${examId.trim() || "exam"}_item_${pad(nextNumber)}`)) nextNumber += 1;
    const fixedId = template.execution_key || template.template_id;
    const parameters = hasContractBindings(template)
      ? applyTemplateContract(template, cloneParams(template), templateContract)
      : cloneParams(template);
    const item: TestcaseItem = {
      instance_id: `${examId.trim() || "exam"}_item_${pad(nextNumber)}`,
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
      parameters,
      contract_overrides: [],
      expected: renderExpected(template.expected_template, parameters),
      expected_custom: false,
    };
    setItems((current) => [...current, item]);
    setEditingId(item.instance_id);
    setSelectedTemplateId(template.template_id);
    setMessage(null);
  };

  const applyContractToSelectedTestcases = () => {
    let changed = 0;
    const nextItems = items.map((item) => {
      const template = templateMap.get(item.template_id);
      if (!hasContractBindings(template)) return item;
      const parameters = applyTemplateContract(template, item.parameters, templateContract, true);
      changed += 1;
      return {
        ...item,
        parameters,
        contract_overrides: [],
        expected: item.expected_custom ? item.expected : renderExpected(template.expected_template, parameters),
      };
    });
    setItems(nextItems);
    setMessage({
      type: "ok",
      text: changed > 0
        ? `Đã áp dụng contract gợi ý cho ${changed} testcase trong đề.`
        : "Đã ghi nhận contract. Testcase template thêm sau sẽ tự nhận các giá trị phù hợp.",
    });
  };

  const addContractSection = () => setTemplateContract((contract) => ({
    ...contract,
    sections: [...contract.sections, { id: contractId(), name: "Nhóm contract mới", fields: [] }],
  }));

  const updateContractSection = (sectionId: string, patch: Partial<TemplateContractSection>) =>
    setTemplateContract((contract) => ({
      ...contract,
      sections: contract.sections.map((section) => section.id === sectionId ? { ...section, ...patch } : section),
    }));

  const removeContractSection = (sectionId: string) => setTemplateContract((contract) => ({
    ...contract,
    sections: contract.sections.filter((section) => section.id !== sectionId),
  }));

  const addContractField = (sectionId: string) => setTemplateContract((contract) => ({
    ...contract,
    sections: contract.sections.map((section) => section.id === sectionId ? {
      ...section,
      fields: [...section.fields, { id: contractId(), key: "custom.parameter", label: "Trường contract mới", value: "", kind: "text" }],
    } : section),
  }));

  const updateContractField = (sectionId: string, fieldId: string, patch: Partial<TemplateContractField>) => {
    const currentField = templateContract.sections
      .flatMap((section) => section.fields)
      .find((field) => field.id === fieldId);
    if (currentField && patch.value !== undefined && patch.value !== currentField.value) {
      setItems((currentItems) => currentItems.map((item) => {
        const template = templateMap.get(item.template_id);
        if (!hasContractBindings(template)) return item;
        const overridden = new Set(item.contract_overrides || []);
        let changed = false;
        const parameters = { ...item.parameters };
        Object.keys(template.parameters_schema || {}).forEach((parameter) => {
          const contractKey = template.contract_bindings?.[parameter] || parameter;
          if (contractKey !== currentField.key || overridden.has(parameter)) return;
          parameters[parameter] = patch.value?.trim()
            ? patch.value : template.parameters_schema[parameter];
          changed = true;
        });
        return changed ? {
          ...item,
          parameters,
          expected: item.expected_custom ? item.expected : renderExpected(template.expected_template, parameters),
        } : item;
      }));
    }
    setTemplateContract((contract) => ({
      ...contract,
      sections: contract.sections.map((section) => section.id === sectionId ? {
        ...section,
        fields: section.fields.map((field) => field.id === fieldId ? { ...field, ...patch } : field),
      } : section),
    }));
  };

  const removeContractField = (sectionId: string, fieldId: string) => setTemplateContract((contract) => ({
    ...contract,
    sections: contract.sections.map((section) => section.id === sectionId ? {
      ...section,
      fields: section.fields.filter((field) => field.id !== fieldId),
    } : section),
  }));

  const updateItem = (instanceId: string, patch: Partial<TestcaseItem>) => {
    setItems((current) => current.map((item) => item.instance_id === instanceId ? { ...item, ...patch } : item));
  };

  const updateSuite = (patch: Partial<SuiteConfig>) => {
    setSuite((current) => ({ ...current, ...patch }));
  };

  const updateKeyContract = (patch: Partial<SemanticKeyContract>) => {
    setSuite((current) => ({
      ...current,
      key_contract: { ...current.key_contract, ...patch },
    }));
  };

  const addSemanticKey = () => {
    const usedSymbols = new Set(suite.key_contract.keys.map((key) => key.symbol));
    const usedValues = new Set(suite.key_contract.keys.map((key) => key.value));
    let index = suite.key_contract.keys.length + 1;
    while (usedSymbols.has(`widgetKey${index}`) || usedValues.has(`widget.item-${index}`)) index += 1;
    updateKeyContract({
      keys: [...suite.key_contract.keys, {
        symbol: `widgetKey${index}`,
        value: `widget.item-${index}`,
        group: "Màn hình",
        description: "",
      }],
    });
  };

  const updateSemanticKey = (index: number, patch: Partial<SemanticKeyDefinition>) => {
    updateKeyContract({
      keys: suite.key_contract.keys.map((key, keyIndex) => keyIndex === index ? { ...key, ...patch } : key),
    });
  };

  const removeSemanticKey = (index: number) => {
    updateKeyContract({ keys: suite.key_contract.keys.filter((_, keyIndex) => keyIndex !== index) });
  };

  const declareUsedSemanticKeys = () => {
    const usedSymbols = new Set(suite.key_contract.keys.map((key) => key.symbol));
    const additions = undeclaredUsedKeys.map((key) => {
      const parts = key.value.split(/[._-]+/).filter(Boolean);
      const base = parts.map((part, index) => index === 0
        ? part.toLocaleLowerCase("en")
        : `${part.charAt(0).toLocaleUpperCase("en")}${part.slice(1).toLocaleLowerCase("en")}`)
        .join("") || "widgetKey";
      const symbolBase = /^[A-Za-z_]/.test(base) ? base : `key${base}`;
      let symbol = symbolBase;
      let suffix = 2;
      while (usedSymbols.has(symbol)) symbol = `${symbolBase}${suffix++}`;
      usedSymbols.add(symbol);
      return {
        symbol,
        value: key.value,
        group: key.value.split(".")[0] || "Khác",
        description: key.description,
      };
    });
    updateKeyContract({ keys: [...suite.key_contract.keys, ...additions] });
  };

  const copySemanticKey = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedKey(value);
      window.setTimeout(() => setCopiedKey((current) => current === value ? "" : current), 1200);
    } catch {
      setMessage({ type: "error", text: "Trình duyệt không cho phép sao chép Key tự động." });
    }
  };

  const chooseSemanticKey = (item: TestcaseItem, parameter: string, value: string) => {
    if (!value) return;
    if (!isSemanticKeyListParameter(parameter)) {
      updateParameter(item, parameter, value);
      return;
    }
    const current = String(item.parameters[parameter] || "")
      .split(",").map((entry) => entry.trim()).filter(Boolean);
    if (!current.includes(value)) current.push(value);
    updateParameter(item, parameter, current.join(","));
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
      contract_overrides: hasContractBindings(template)
        ? Array.from(new Set([...(item.contract_overrides || []), key])) : item.contract_overrides,
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
    if (usesSemanticKeys(engineMode) && keyContractIssues.length > 0) {
      setMessage({ type: "error", text: `Bộ Semantic Key chưa hợp lệ: ${keyContractIssues[0]}.` });
      return;
    }
    setSaving(kind);
    setMessage(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/testcases/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${getToken() ?? ""}` },
        body: JSON.stringify({ engine_type: engineMode, exam_name: examName.trim(), teacher_note: teacherNote.trim(), suite: { ...suite, template_contract: templateContract }, items }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được cấu hình testcase");
      setStatus(data.status || (kind === "publish" ? "PUBLISHED" : "DRAFT"));
      setExamIdCheck("editable");
      setVersion(Number(data.version ?? version));
      if (data.suite && typeof data.suite === "object") {
        const loadedSuite = data.suite as Partial<SuiteConfig>;
        setSuite({ ...suiteForEngine(engineMode), ...loadedSuite, required_keys: Array.isArray(loadedSuite.required_keys) ? loadedSuite.required_keys.join(", ") : String(loadedSuite.required_keys || ""), setup_steps: Array.isArray(loadedSuite.setup_steps) ? loadedSuite.setup_steps : [], key_contract: normalizeSemanticKeyContract(loadedSuite.key_contract) });
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
      subtitle="Kết hợp starter TODO cho Logic/SQLite với semantic Key cho Widget và Behavior"
      activePath="/teacher/testcases"
    >
      <div className="space-y-5">
        <datalist id="semantic-key-options">
          {semanticKeyCatalog.map((key) => <option key={key.value} value={key.value}>{key.symbol || key.group}</option>)}
        </datalist>
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
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
            <button type="button" onClick={() => selectEngineMode("STARTER_KEY_HYBRID_V1")} className={`rounded-xl border p-4 text-left transition ${engineMode === "STARTER_KEY_HYBRID_V1" ? "border-indigo-400 bg-indigo-50 ring-2 ring-indigo-100" : "border-slate-200 hover:border-indigo-200"}`}>
              <div className="flex items-center justify-between gap-3"><strong className="text-sm text-slate-800">Starter TODO + Key UI</strong><span className="rounded-full bg-indigo-100 px-2 py-1 text-[10px] font-bold text-indigo-700">KHUYẾN NGHỊ</span></div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Logic, Model, Repository và SQLite theo public contract của starter; Widget và luồng UI được định vị bằng semantic Key.</p>
            </button>
            <button type="button" onClick={() => selectEngineMode("TEMPLATE_CONTRACT_V1")} className={`rounded-xl border p-4 text-left transition ${engineMode === "TEMPLATE_CONTRACT_V1" ? "border-indigo-400 bg-indigo-50 ring-2 ring-indigo-100" : "border-slate-200 hover:border-indigo-200"}`}>
              <div className="flex items-center justify-between gap-3"><strong className="text-sm text-slate-800">Bộ testcase chấm theo khung template mẫu</strong><span className="rounded-full bg-emerald-100 px-2 py-1 text-[10px] font-bold text-emerald-700">TEMPLATE MẪU</span></div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Tái sử dụng các mẫu kiểm tra tổng quát; file, class, field, method và label được nhập lại theo từng đề.</p>
            </button>
            <button type="button" onClick={() => selectEngineMode("COMMON_V1")} className={`rounded-xl border p-4 text-left transition ${engineMode === "COMMON_V1" ? "border-indigo-400 bg-indigo-50 ring-2 ring-indigo-100" : "border-slate-200 hover:border-indigo-200"}`}>
              <div className="flex items-center justify-between gap-3"><strong className="text-sm text-slate-800">Bộ testcase 3 tầng chấm theo Key</strong><span className="rounded-full bg-amber-100 px-2 py-1 text-[10px] font-bold text-amber-700">LOGIC – WIDGET – BEHAVIOR</span></div>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Dùng contract Key để tìm widget và chấm ba tầng. Phù hợp khi sinh viên được tự do xây dựng cấu trúc UI hơn.</p>
            </button>
          </div>
          {items.length > 0 && <p className="mt-3 text-[11px] font-semibold text-amber-600">Kiểu chấm được khóa khi đề đã có testcase. Xóa tất cả testcase nếu cần đổi engine.</p>}
        </section>

        {usesStarterContract(engineMode) && (
        <section className="card overflow-hidden">
          <div className="border-b border-slate-100 bg-emerald-50/70 px-4 py-3"><p className="eyebrow">Contract thay đổi theo từng đề</p><div className="mt-1 flex flex-wrap items-center justify-between gap-3"><h2 className="text-sm font-bold text-slate-800">Tái sử dụng mẫu kiểm tra, không tái sử dụng bộ đề cố định</h2><button type="button" onClick={() => document.getElementById("testcase-library")?.scrollIntoView({ behavior: "smooth" })} className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white hover:bg-emerald-700">Chọn mẫu testcase ↓</button></div><p className="mt-1 text-xs text-slate-500">Với mỗi testcase, giảng viên nhập file, class, field, method, label, dữ liệu và expected của đề hiện tại.</p></div>
          {engineMode === "TEMPLATE_CONTRACT_V1" && <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-3">
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-3"><p className="text-xs font-bold text-blue-800">1. Chọn mẫu kiểm tra</p><p className="mt-1 text-[10px] leading-relaxed text-blue-700">Ví dụ: Model fields, SQLite schema, Repository methods, form fields, button/action hoặc responsive.</p></div>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3"><p className="text-xs font-bold text-amber-800">2. Nhập contract của đề</p><p className="mt-1 text-[10px] leading-relaxed text-amber-700">Mọi đường dẫn, tên class, danh sách field/method và label đều sửa được trong testcase đã chọn.</p></div>
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3"><p className="text-xs font-bold text-emerald-800">3. Sinh bộ chấm riêng</p><p className="mt-1 text-[10px] leading-relaxed text-emerald-700">Engine dùng contract hiện tại để chấm starter TODO; không yêu cầu Widget Key hay grading_adapter.dart.</p></div>
          </div>}
          {engineMode === "STARTER_KEY_HYBRID_V1" && <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-3">
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-3"><p className="text-xs font-bold text-blue-800">1. Khóa contract starter</p><p className="mt-1 text-[10px] leading-relaxed text-blue-700">Khai báo file, class, field, method và schema mà sinh viên phải hoàn thành TODO.</p></div>
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-3"><p className="text-xs font-bold text-amber-800">2. Công bố semantic Key</p><p className="mt-1 text-[10px] leading-relaxed text-amber-700">Chỉ khóa điểm tương tác UI; sinh viên vẫn tự xây widget tree, bố cục và state management.</p></div>
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3"><p className="text-xs font-bold text-emerald-800">3. Sinh bộ chấm hybrid</p><p className="mt-1 text-[10px] leading-relaxed text-emerald-700">Logic/SQLite đọc starter trực tiếp, UI dùng Key chính xác; không có grading_adapter.dart.</p></div>
          </div>}
          <details className="border-t border-slate-100 bg-slate-50/50 px-4 py-3">
            <summary className="cursor-pointer text-xs font-bold text-indigo-700">Thiết lập contract gợi ý cho đề (không bắt buộc)</summary>
            <p className="mt-2 text-[11px] leading-relaxed text-slate-500">Contract là bộ biến dùng chung của đề. Có thể thêm/xóa nhóm và trường tùy ý; mỗi blueprint ánh xạ tham số tới một mã như <code>model.path</code>, <code>ui.fieldLabels</code> hoặc <code>behavior.stepsJson</code>.</p>
            <div className="mt-3 space-y-3">
              {templateContract.sections.map((section) => (
                <div key={section.id} className="rounded-xl border border-slate-200 bg-white p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <input value={section.name} onChange={(event) => updateContractSection(section.id, { name: event.target.value })} className="min-w-56 flex-1 rounded-md border border-slate-200 px-2.5 py-2 text-xs font-bold text-slate-700" aria-label="Tên nhóm contract" />
                    <button type="button" onClick={() => addContractField(section.id)} className="rounded-md border border-indigo-200 px-2.5 py-2 text-[11px] font-bold text-indigo-700 hover:bg-indigo-50"><Plus size={13} className="mr-1 inline" />Thêm trường</button>
                    <button type="button" onClick={() => removeContractSection(section.id)} className="rounded-md border border-rose-200 p-2 text-rose-600 hover:bg-rose-50" title="Xóa nhóm"><Trash2 size={14} /></button>
                  </div>
                  {section.fields.length === 0 ? <p className="mt-3 text-[11px] text-slate-400">Nhóm chưa có trường.</p> : (
                    <div className="mt-3 grid grid-cols-1 gap-2 xl:grid-cols-2">
                      {section.fields.map((field) => (
                        <div key={field.id} className="rounded-lg border border-slate-100 bg-slate-50/70 p-2">
                          <div className="grid grid-cols-[1fr_1fr_auto] gap-2">
                            <input value={field.label} onChange={(event) => updateContractField(section.id, field.id, { label: event.target.value })} placeholder="Tên hiển thị" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-[11px] font-semibold" />
                            <input value={field.key} onChange={(event) => updateContractField(section.id, field.id, { key: event.target.value })} placeholder="model.path" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-[11px]" />
                            <button type="button" onClick={() => removeContractField(section.id, field.id)} className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa trường"><X size={14} /></button>
                          </div>
                          <div className="mt-2 grid grid-cols-[110px_1fr] gap-2">
                            <select value={field.kind} onChange={(event) => updateContractField(section.id, field.id, { kind: event.target.value as ContractValueKind })} className="rounded-md border border-slate-200 bg-white px-2 py-2 text-[11px]"><option value="text">Text</option><option value="path">Đường dẫn</option><option value="identifier">Dart identifier</option><option value="csv">Danh sách CSV</option><option value="json">JSON</option><option value="number">Số</option></select>
                            {field.kind === "json" ? <textarea rows={2} value={field.value} onChange={(event) => updateContractField(section.id, field.id, { value: event.target.value })} placeholder="Giá trị contract" className="resize-y rounded-md border border-slate-200 bg-white px-2 py-2 font-mono text-[11px]" /> : <input type={field.kind === "number" ? "number" : "text"} value={field.value} onChange={(event) => updateContractField(section.id, field.id, { value: event.target.value })} placeholder="Giá trị contract" className="rounded-md border border-slate-200 bg-white px-2 py-2 text-[11px]" />}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
            <p className="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-[11px] leading-relaxed text-emerald-700">Giá trị contract được cập nhật tự động vào các testcase đang kế thừa. Tham số bạn sửa trực tiếp trong từng testcase được giữ làm override.</p>
            <div className="mt-3 flex flex-wrap justify-between gap-2"><button type="button" onClick={addContractSection} className="rounded-lg border border-indigo-200 bg-white px-3 py-2 text-xs font-semibold text-indigo-700 hover:bg-indigo-50"><Plus size={14} className="mr-1 inline" />Thêm nhóm contract</button><div className="flex flex-wrap gap-2"><button type="button" onClick={() => setTemplateContract(emptyTemplateContract())} className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 hover:bg-slate-100">Khôi phục khung gợi ý</button><button type="button" onClick={applyContractToSelectedTestcases} className="rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white hover:bg-indigo-700">Bỏ override và đồng bộ lại</button></div></div>
          </details>
          <div className="border-t border-slate-100 px-4 py-3 text-xs text-slate-500"><strong className="text-slate-700">Không có nút nạp hàng loạt:</strong> đề cần gì thì chọn mẫu đó, sau đó thay tham số theo đúng starter đang phát.</div>
        </section>
        )}
        {usesSemanticKeys(engineMode) && (
        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/70 px-4 py-3">
            <div>
              <p className="eyebrow">Khung bộ testcase</p>
              <h2 className="mt-1 text-sm font-bold text-slate-800">Ngữ cảnh, fixture và setup dùng chung</h2>
              <p className="mt-1 text-xs text-slate-500">Mỗi testcase sẽ khởi động lại app rồi chạy khung này trước khi kiểm tra riêng.</p>
            </div>
            <label className="flex items-center gap-2 text-xs font-semibold text-slate-600">
              <input type="checkbox" checked={suite.strict_semantic_keys} disabled={engineMode === "STARTER_KEY_HYBRID_V1"} onChange={(e) => updateSuite({ strict_semantic_keys: e.target.checked })} />
              Bắt buộc semantic key chính xác
            </label>
          </div>
          <div className="grid grid-cols-1 gap-3 p-4 md:grid-cols-2 xl:grid-cols-4">
            <label className="text-xs font-semibold text-slate-600">Tên khung<input value={suite.name} onChange={(e) => updateSuite({ name: e.target.value })} placeholder="Todo CRUD cơ bản" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Ngữ cảnh<input value={suite.context} onChange={(e) => updateSuite({ context: e.target.value })} placeholder="todo_crud" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Tên fixture<input value={suite.fixture_name} onChange={(e) => updateSuite({ fixture_name: e.target.value })} placeholder="one_existing_todo" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Key báo sẵn sàng<input list="semantic-key-options" value={suite.ready_key} onChange={(e) => updateSuite({ ready_key: e.target.value })} placeholder="screen.home.ready" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600 md:col-span-2">Mô tả fixture<textarea rows={2} value={suite.fixture_description} onChange={(e) => updateSuite({ fixture_description: e.target.value })} placeholder="Dữ liệu ban đầu mà starter phải hiển thị trước khi chạy testcase." className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600 md:col-span-2">Các key bắt buộc<input value={suite.required_keys} onChange={(e) => updateSuite({ required_keys: e.target.value })} placeholder="screen.home, list.items, action.add" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs font-normal outline-none focus:border-indigo-400" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Phân tách bằng dấu phẩy. Bỏ trống nếu chỉ muốn testcase tự khai báo target.</span></label>
            <label className="text-xs font-semibold text-slate-600">Chờ khởi động (ms)<input type="number" min={100} max={30000} step={100} value={suite.boot_timeout_ms} onChange={(e) => updateSuite({ boot_timeout_ms: Number(e.target.value) })} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
            <label className="text-xs font-semibold text-slate-600">Chờ mỗi bước (ms)<input type="number" min={100} max={30000} step={100} value={suite.step_timeout_ms} onChange={(e) => updateSuite({ step_timeout_ms: Number(e.target.value) })} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400" /></label>
          </div>
          <div className="border-t border-slate-100 bg-indigo-50/30 px-4 py-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div><p className="text-xs font-bold text-slate-700">Bộ Semantic Key công bố trong starter</p><p className="mt-1 text-[10px] leading-relaxed text-slate-500">Khai báo một lần theo file Key phát cho sinh viên. Các ô Key của testcase sẽ cho chọn từ danh mục này; vẫn có thể nhập Key mới khi cần.</p></div>
              <div className="flex flex-wrap gap-2">{undeclaredUsedKeys.length > 0 && <button type="button" onClick={declareUsedSemanticKeys} className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700 hover:bg-amber-100">Nhập {undeclaredUsedKeys.length} Key đang dùng</button>}<button type="button" onClick={addSemanticKey} className="flex items-center gap-1 rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white hover:bg-indigo-700"><Plus size={13} /> Thêm Key</button></div>
            </div>
            <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
              <label className="text-[11px] font-semibold text-slate-600">File định nghĩa Key<input value={suite.key_contract.source_path} onChange={(event) => updateKeyContract({ source_path: event.target.value })} placeholder="lib/grading/app_keys.dart" className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 font-mono text-xs font-normal" /></label>
              <label className="text-[11px] font-semibold text-slate-600">Class chứa Key<input value={suite.key_contract.class_name} onChange={(event) => updateKeyContract({ class_name: event.target.value })} placeholder="AppKeys" className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 font-mono text-xs font-normal" /></label>
            </div>
            {suite.key_contract.keys.length === 0 ? <p className="mt-3 rounded-lg border border-dashed border-indigo-200 bg-white/70 p-3 text-xs text-slate-500">Chưa khai báo Key. Bấm “Thêm Key”, nhập tên constant và giá trị như <code>uidField</code> → <code>person.form.uid</code>.</p> : <div className="mt-3 space-y-2">{suite.key_contract.keys.map((key, index) => <div key={`${index}-${key.symbol}`} className="grid grid-cols-1 gap-2 rounded-lg border border-indigo-100 bg-white p-2 md:grid-cols-[minmax(120px,0.8fr)_minmax(170px,1fr)_minmax(110px,0.7fr)_minmax(180px,1.4fr)_auto]">
              <input value={key.symbol} onChange={(event) => updateSemanticKey(index, { symbol: event.target.value })} placeholder="uidField" aria-label={`Tên biến Key ${index + 1}`} className="rounded-md border border-slate-200 px-2 py-1.5 font-mono text-[11px]" />
              <input value={key.value} onChange={(event) => updateSemanticKey(index, { value: event.target.value })} placeholder="person.form.uid" aria-label={`Giá trị Key ${index + 1}`} className="rounded-md border border-slate-200 px-2 py-1.5 font-mono text-[11px]" />
              <input value={key.group} onChange={(event) => updateSemanticKey(index, { group: event.target.value })} placeholder="Form" aria-label={`Nhóm Key ${index + 1}`} className="rounded-md border border-slate-200 px-2 py-1.5 text-[11px]" />
              <input value={key.description} onChange={(event) => updateSemanticKey(index, { description: event.target.value })} placeholder="Ô nhập UID" aria-label={`Mô tả Key ${index + 1}`} className="rounded-md border border-slate-200 px-2 py-1.5 text-[11px]" />
              <button type="button" onClick={() => removeSemanticKey(index)} className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa Key"><Trash2 size={14} /></button>
            </div>)}</div>}
            {keyContractIssues.length > 0 && <div className="mt-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[10px] text-rose-700"><strong>Chưa thể lưu:</strong> {keyContractIssues.join(" · ")}</div>}
            {undeclaredUsedKeys.length > 0 && <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[10px] leading-relaxed text-amber-700"><strong>{undeclaredUsedKeys.length} Key đang được testcase dùng nhưng chưa khai báo trong starter:</strong> {undeclaredUsedKeys.slice(0, 6).map((key) => key.value).join(", ")}{undeclaredUsedKeys.length > 6 ? "…" : ""}. Có thể lưu Draft, nhưng nên thêm chúng vào danh mục trước khi phát starter.</div>}
            <p className="mt-3 text-[10px] text-indigo-700">Trong starter: <code>{suite.key_contract.class_name || "AppKeys"}.uidField</code> phải trả về <code>const Key(&apos;person.form.uid&apos;)</code>. Máy chấm dùng chính chuỗi <code>person.form.uid</code>.</p>
          </div>
          <div className="border-t border-slate-100 px-4 py-3">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                  <label className="text-xs font-semibold text-slate-600">Profile<select value={suite.profile} onChange={(e) => updateSuite({ profile: e.target.value as SuiteConfig["profile"] })} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal"><option value="STARTER_KEY_HYBRID_V1">Starter TODO + Key UI</option><option value="COMMON_UI">Common UI</option><option value="FLUTTER_LAYERED">Flutter layered</option><option value="PERSISTENCE" disabled>Persistence (chưa có runner)</option><option value="REPOSITORY_SQLITE" disabled>Repository + SQLite (khai báo qua starter TODO)</option><option value="GOLDEN_RESPONSIVE" disabled>Golden responsive (chưa có runner)</option></select></label>
                  <label className="text-xs font-semibold text-slate-600">Reset strategy<select value={suite.reset_strategy} onChange={(e) => updateSuite({ reset_strategy: e.target.value as SuiteConfig["reset_strategy"] })} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal"><option value="APP_RESTART">App restart</option><option value="FIXTURE_STEPS">Fixture steps</option><option value="CLEAR_STORAGE" disabled>Clear storage (chưa hỗ trợ)</option><option value="PERSISTENCE_PHASE" disabled>Persistence phase (chưa hỗ trợ)</option></select></label>
            </div>
            <div className="mt-4 flex items-center justify-between gap-2"><div><p className="text-xs font-bold text-slate-700">Source contracts</p><p className="text-[10px] text-slate-400">Dùng path trong lib/ và tên Dart identifier chính xác.</p></div><button onClick={addSourceContract} type="button" className="flex items-center gap-1 rounded-md border border-indigo-200 px-2.5 py-1.5 text-xs font-semibold text-indigo-600 hover:bg-indigo-50"><Plus size={13} /> Thêm contract</button></div>
            {suite.source_contracts.length === 0 ? <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-xs text-slate-400">Chưa khai báo symbol bắt buộc.</p> : <div className="mt-3 space-y-2">{suite.source_contracts.map((contract, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-slate-200 bg-slate-50 p-2 md:grid-cols-[150px_minmax(180px,1fr)_minmax(180px,1fr)_auto]"><select value={contract.type} onChange={(e) => updateSourceContract(index, { type: e.target.value as SourceContractType })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="model">Model</option><option value="repository">Repository</option><option value="provider">Provider</option><option value="screen">Screen</option><option value="helper">Helper</option><option value="service">Service</option></select><input value={contract.path} onChange={(e) => updateSourceContract(index, { path: e.target.value })} placeholder="lib/models/user.dart" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" /><input value={contract.symbols} onChange={(e) => updateSourceContract(index, { symbols: e.target.value })} placeholder="User, UserStatus" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" /><button type="button" onClick={() => removeSourceContract(index)} className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa contract"><Trash2 size={14} /></button></div>)}</div>}
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
                  <div className="rounded-md border border-slate-200 bg-slate-50 p-3 opacity-70"><label className="flex items-center gap-2 text-xs font-semibold text-slate-600"><input type="checkbox" checked={suite.persistence.enabled} disabled={!suite.persistence.enabled} onChange={() => updateSuite({ persistence: { ...suite.persistence, enabled: false } })} /> Persistence sau reload · chưa có runner</label><p className="mt-2 text-[10px] leading-relaxed text-slate-500">Logic và SQLite phải được khai báo trong starter TODO. Persistence qua process/reload vẫn bị khóa cho tới khi có runner cách ly database thật.</p></div>
                  <div className="rounded-md border border-slate-200 bg-slate-50 p-3 opacity-70"><label className="flex items-center gap-2 text-xs font-semibold text-slate-600"><input type="checkbox" checked={suite.golden.enabled} disabled={!suite.golden.enabled} onChange={() => updateSuite({ golden: { ...suite.golden, enabled: false } })} /> Golden image · chưa có runner</label><p className="mt-2 text-[10px] leading-relaxed text-slate-500">Nếu cấu hình cũ đang bật, bỏ dấu tích; chức năng chỉ được bật lại khi engine có phép so sánh golden thật.</p></div>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2"><div><p className="text-xs font-bold text-slate-700">Setup UI dùng chung</p><p className="mt-0.5 text-[10px] text-slate-400">Whitelist: tap, nhập text, chờ/kiểm tra key. Không chạy Dart code tùy ý.</p></div><button onClick={addSuiteStep} type="button" className="flex items-center gap-1 rounded-md bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={13} /> Thêm bước</button></div>
            {suite.setup_steps.length === 0 ? <p className="mt-3 rounded-md border border-dashed border-slate-200 p-3 text-xs text-slate-400">Chưa có setup chung. Fixture phải ở đúng trạng thái sau khi app khởi động.</p> : <div className="mt-3 space-y-2">{suite.setup_steps.map((step, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-slate-200 bg-slate-50 p-2 md:grid-cols-[170px_minmax(160px,1fr)_minmax(160px,1fr)_auto]"><p className="text-[10px] leading-relaxed text-slate-500 md:col-span-full">{setupStepHint(step.type)}</p><select value={step.type} onChange={(e) => updateSuiteStep(index, { type: e.target.value as SetupStep["type"] })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"><option value="tap">Tap key</option><option value="enter_text">Nhập text</option><option value="expect_visible">Bắt buộc thấy key</option><option value="expect_absent">Bắt buộc ẩn key</option><option value="wait_for_visible">Chờ key xuất hiện</option></select><input list="semantic-key-options" value={step.key} onChange={(e) => updateSuiteStep(index, { key: e.target.value })} placeholder="action.add" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-xs" />{step.type === "enter_text" ? <input value={step.value || ""} onChange={(e) => updateSuiteStep(index, { value: e.target.value })} placeholder="Giá trị nhập" className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /> : <div />}{step.type === "wait_for_visible" ? <input type="number" min={100} max={30000} value={step.timeout_ms || suite.step_timeout_ms} onChange={(e) => updateSuiteStep(index, { timeout_ms: Number(e.target.value) })} className="rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /> : <div /> }<button onClick={() => removeSuiteStep(index)} type="button" className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa bước"><Trash2 size={14} /></button></div>)}</div>}
          </div>
        </section>
        )}

        <section className="card overflow-hidden">
          <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-200 bg-slate-50 px-4 py-3">
            <div>
              <p className="eyebrow">Tiến độ bộ testcase</p>
              <h2 className="mt-1 text-sm font-bold text-slate-800">Theo dõi tiêu chí đang xây dựng</h2>
              <p className="mt-1 text-xs text-slate-500">Thêm và hoàn thiện từng testcase. Code máy chỉ mở khi cần kiểm tra kỹ thuật.</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <span className={`rounded-full px-2.5 py-1 text-xs font-bold ${status === "PUBLISHED" ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>{version > 0 ? `${status || "DRAFT"} · v${version}` : "Chưa lưu Draft"}</span>
              <button type="button" onClick={openPreview} disabled={version === 0 || previewLoading} className="flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 hover:bg-slate-100 disabled:opacity-50"><Eye size={14} /> Xem 3 file kỹ thuật</button>
            </div>
          </div>
          <div className="grid gap-4 p-4 lg:grid-cols-[220px_minmax(0,1fr)]">
            <div className="rounded-xl border border-slate-200 bg-white p-4">
              <div className="flex items-end justify-between gap-3"><div><p className="text-xs font-semibold text-slate-500">Đã sẵn sàng</p><p className="mt-1 text-2xl font-black text-slate-800">{readyItemCount}<span className="text-sm font-semibold text-slate-400">/{activeItemCount}</span></p></div><span className={`text-lg font-black ${attentionItemCount ? "text-amber-600" : "text-emerald-600"}`}>{progressPercent}%</span></div>
              <div className="mt-3 h-2 overflow-hidden rounded-full bg-slate-100"><div className={`h-full rounded-full ${attentionItemCount ? "bg-amber-400" : "bg-emerald-500"}`} style={{ width: `${progressPercent}%` }} /></div>
              <div className="mt-3 space-y-1 text-xs"><div className="flex justify-between text-slate-500"><span>Cần hoàn thiện</span><strong className={attentionItemCount ? "text-amber-600" : "text-slate-600"}>{attentionItemCount}</strong></div><div className="flex justify-between text-slate-500"><span>Đang tắt</span><strong className="text-slate-600">{disabledItemCount}</strong></div><div className="flex justify-between text-slate-500"><span>Tổng trọng số</span><strong className="text-indigo-700">{totalWeight.toFixed(2)}</strong></div></div>
              {attentionItemCount > 0 && <button type="button" onClick={() => { setItemGroupFilter("ALL"); setItemProgressFilter("attention"); document.getElementById("selected-testcases")?.scrollIntoView({ behavior: "smooth", block: "start" }); }} className="mt-3 w-full rounded-lg bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700 hover:bg-amber-100">Xem {attentionItemCount} mục cần hoàn thiện</button>}
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              {progressGroups.map((group) => (
                <button key={group.code} type="button" onClick={() => { setItemGroupFilter(group.code); setItemProgressFilter("ALL"); document.getElementById("selected-testcases")?.scrollIntoView({ behavior: "smooth", block: "start" }); }} className="rounded-xl border border-slate-200 bg-slate-50 p-4 text-left transition-colors hover:border-indigo-200 hover:bg-indigo-50/40">
                  <div className="flex items-center justify-between gap-2"><span className="text-sm font-bold text-slate-700">{TESTCASE_GROUP_LABEL[group.code]}</span><ChevronRight size={15} className="text-slate-300" /></div>
                  <p className="mt-3 text-xl font-black text-slate-800">{group.ready}<span className="text-sm text-slate-400">/{group.count}</span></p>
                  <p className="mt-1 text-xs text-slate-500">sẵn sàng · {group.weight.toFixed(2)} điểm</p>
                </button>
              ))}
            </div>
          </div>
          <div className="border-t border-slate-100 bg-indigo-50/50 px-4 py-3 text-xs leading-relaxed text-indigo-700"><strong>Luồng đề xuất:</strong> chọn một mẫu → thêm vào đề → sửa cấu hình đang mở → kiểm tra trạng thái → lưu Draft → tiếp tục testcase kế tiếp.</div>
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
                <div><p className="eyebrow">Khu vực 2</p><h2 className="mt-1 text-sm font-bold text-slate-800">Thư viện testcase</h2><p className="mt-1 text-xs text-slate-500">{engineMode === "STARTER_KEY_HYBRID_V1" ? "Logic/SQLite lấy contract từ starter TODO; Widget và Behavior dùng semantic Key." : engineMode === "TEMPLATE_CONTRACT_V1" ? "Các mẫu testcase tổng quát; chọn mẫu nào thì nhập contract của đề cho mẫu đó." : "Thư viện testcase 3 tầng chấm theo Key."}</p></div>
                <div className="flex items-center gap-2"><span className="text-xs text-slate-400">{visibleTemplates.length} template</span><button type="button" onClick={openNewTemplate} className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-2.5 py-2 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={14} /> Tạo testcase mới</button></div>
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
                        <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${template.custom ? "bg-amber-100 text-amber-700" : "bg-emerald-100 text-emerald-700"}`} title={template.custom ? `Tạo bởi ${template.created_by || "giảng viên"}` : ENGINE_LABEL[template.engine_type || ""] || template.engine_type}>{template.custom ? "Tự tạo" : template.engine_type === "STARTER_KEY_HYBRID_V1" ? (template.hybrid_target === "STARTER_CONTRACT" ? "Starter TODO" : "Semantic Key") : template.engine_type === "TEMPLATE_CONTRACT_V1" ? "Khung chung" : template.fixed_contract ? "Template mẫu" : "Chấm theo Key"}</span>
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
          <section id="selected-testcases" className="card min-w-0 scroll-mt-24 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="eyebrow">Khu vực 3</p><h2 className="mt-1 text-sm font-bold text-slate-800">Testcase trong đề</h2></div><div className="flex items-center gap-2"><span className="rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700">{items.length} mục</span>{supportsGrouping && selectedItemIds.length >= 2 && <button onClick={openGroupModal} className="rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700">Gộp thành testcase lớn</button>}{items.length > 0 && <button onClick={clearAllItems} className="flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-100" title="Xóa toàn bộ testcase"><Trash2 size={13} /> Xóa tất cả</button>}</div></div>
              <div className="mt-3 flex items-center justify-between text-xs"><span className="text-slate-500">Tổng trọng số</span><strong className="text-indigo-700">{totalWeight.toFixed(2)}</strong></div>
              {items.length > 0 && <div className="mt-3 space-y-2 border-t border-slate-200 pt-3">
                <div className="flex flex-wrap gap-1.5">{TESTCASE_GROUP_ORDER.map((code) => <button key={code} type="button" onClick={() => setItemGroupFilter(code)} className={`rounded-md px-2 py-1 text-[10px] font-bold ${itemGroupFilter === code ? "bg-indigo-600 text-white" : "bg-white text-slate-500 hover:bg-slate-100"}`}>{code === "ALL" ? "Mọi tầng" : TESTCASE_GROUP_LABEL[code].replace("Testcase ", "")}</button>)}</div>
                <div className="flex flex-wrap gap-1.5">{([
                  ["ALL", `Tất cả ${items.length}`], ["ready", `Sẵn sàng ${readyItemCount}`],
                  ["attention", `Cần xử lý ${attentionItemCount}`], ["disabled", `Đang tắt ${disabledItemCount}`],
                ] as const).map(([code, label]) => <button key={code} type="button" onClick={() => setItemProgressFilter(code)} className={`rounded-md px-2 py-1 text-[10px] font-semibold ${itemProgressFilter === code ? "bg-slate-700 text-white" : "bg-white text-slate-500 hover:bg-slate-100"}`}>{label}</button>)}</div>
              </div>}
            </div>
            <div
              className="custom-scrollbar max-h-[calc(100vh-295px)] min-h-[360px] space-y-2 overflow-y-auto p-3"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => { e.preventDefault(); if (draggedTemplateId) addTemplate(draggedTemplateId); setDraggedTemplateId(null); }}
            >
              {items.length === 0 ? (
                <div className="flex min-h-[330px] flex-col items-center justify-center rounded-xl border-2 border-dashed border-slate-200 p-8 text-center" onDragOver={(e) => e.preventDefault()}>
                  <Package size={28} className="mb-3 text-slate-300" />
                  <p className="text-sm font-semibold text-slate-600">Bắt đầu từ một testcase đơn giản</p>
                  <p className="mt-1 text-xs text-slate-400">Thêm kiểm tra khởi động làm dòng đầu tiên có trọng số, sau đó bổ sung từng tiêu chí.</p>
                  {templatesForEngine.find((template) => template.runner === "APP_BOOT") && <button type="button" onClick={() => addTemplate(templatesForEngine.find((template) => template.runner === "APP_BOOT")!.template_id)} className="mt-3 rounded-lg bg-indigo-600 px-3 py-2 text-xs font-bold text-white hover:bg-indigo-700"><Plus size={13} className="mr-1 inline" />Khởi tạo testcase đầu tiên</button>}
                </div>
              ) : filteredItems.length === 0 ? (
                <div className="flex min-h-[260px] flex-col items-center justify-center rounded-xl border-2 border-dashed border-slate-200 p-8 text-center">
                  <CheckCircle2 size={28} className="mb-3 text-emerald-400" />
                  <p className="text-sm font-semibold text-slate-600">Không có testcase khớp bộ lọc</p>
                  <button type="button" onClick={() => { setItemGroupFilter("ALL"); setItemProgressFilter("ALL"); }} className="mt-3 rounded-md bg-slate-100 px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-200">Hiển thị tất cả</button>
                </div>
              ) : filteredItems.map((item) => {
                const progress = itemProgressMap.get(item.instance_id) || { state: "attention", issues: ["Chưa xác định được trạng thái"] };
                return (
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
                      <div className="flex items-start justify-between gap-2"><div className="min-w-0"><p className="text-sm font-semibold text-slate-800">{item.name}</p>{item.group_id && <div className="mt-1.5 rounded-lg border border-indigo-100 bg-indigo-50/70 px-2.5 py-2 text-[10px] text-indigo-700"><p className="font-bold">Testcase lớn: {groupSummaries.get(item.group_id)?.name || item.group_name || item.group_id}</p><p className="mt-0.5">{groupSummaries.get(item.group_id)?.count || 0} testcase nhỏ · {Number(groupSummaries.get(item.group_id)?.weight || 0).toFixed(2)} điểm · một assert fail sẽ làm cả nhóm fail</p><p className="mt-0.5 font-mono text-indigo-400">{item.group_id}</p></div>}</div><div className="flex shrink-0 items-center gap-1.5"><span className={`rounded px-1.5 py-0.5 text-[10px] font-bold ${progress.state === "ready" ? "bg-emerald-100 text-emerald-700" : progress.state === "disabled" ? "bg-slate-100 text-slate-500" : "bg-amber-100 text-amber-700"}`}>{progress.state === "ready" ? "Sẵn sàng" : progress.state === "disabled" ? "Đang tắt" : "Cần xử lý"}</span><span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[10px] text-indigo-600">#{item.order}</span></div></div>
                      <p className="mt-1 truncate font-mono text-[10px] text-slate-400">{item.instance_id}</p>
                      <div className="mt-2 flex flex-wrap items-center gap-1.5"><span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[item.layer] || item.layer}</span><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[item.difficulty] || item.difficulty}</span><span className="text-[11px] font-semibold text-slate-500">{Number(item.weight).toFixed(2)} điểm</span></div>
                    </div>
                  </div>
                  {progress.state === "attention" && <div className="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[10px] leading-relaxed text-amber-800"><p className="font-bold">Cần hoàn thiện trước khi Publish</p><p className="mt-0.5">{progress.issues.join(" · ")}</p></div>}
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
                      {item.engine_type === "TODO_USER_V12" ? (
                        <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-[11px] leading-relaxed text-emerald-800">
                          <p className="font-bold">Testcase cố định: {item.execution_key || item.instance_id}</p>
                          <p className="mt-1">Logic assert đã nằm trong engine V9. Cấu hình này chỉ cho phép bật/tắt, đổi độ khó, trọng số và mô tả rubric; không sinh Key, setup step hay grading adapter.</p>
                        </div>
                      ) : (<>
                      <div>
                        <div className="mb-2"><p className="text-xs font-semibold text-slate-700">Cấu hình runner</p><p className="mt-0.5 text-[10px] leading-relaxed text-slate-400">{runnerUsesStarterContract(engineMode, String(templateMap.get(item.template_id)?.runner || "")) ? "Nhập contract thật của starter trong đề này: file, class, field, method, dữ liệu fixture và kết quả cần nhận. Phần logic không dùng grading adapter." : "Mỗi trường được phân theo vai trò. Semantic key xác định widget trong bài sinh viên; dữ liệu setup chạy trước; điều kiện pass được chuyển thành assertion."}</p></div>
                        {(() => { const contract = runnerContract(item, templateMap.get(item.template_id)); return <div className="mb-3 grid grid-cols-1 gap-2"><div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2"><p className="text-[10px] font-bold text-amber-800">1. Dữ liệu đầu vào</p><p className="mt-0.5 text-[10px] leading-relaxed text-amber-700">{contract.input}</p></div><div className="rounded-lg border border-cyan-200 bg-cyan-50 px-3 py-2"><p className="text-[10px] font-bold text-cyan-800">2. Đối tượng được tìm</p><p className="mt-0.5 text-[10px] leading-relaxed text-cyan-700">{contract.target}</p></div><div className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2"><p className="text-[10px] font-bold text-emerald-800">3. Testcase pass khi</p><p className="mt-0.5 text-[10px] leading-relaxed text-emerald-700">{contract.pass}</p></div></div>; })()}
                        <div className="space-y-2">{(["target", "input", "assertion", "option"] as ParameterRole[]).map((role) => {
                          const keys = Object.keys(item.parameters || {}).filter((key) => parameterRole(key, templateMap.get(item.template_id)?.runner) === role);
                          if (!keys.length) return null;
                          return <section key={role} className={`rounded-lg border p-2.5 ${PARAMETER_ROLE_STYLE[role]}`}>
                            <div className="mb-2 flex items-center justify-between gap-2"><p className="text-[11px] font-bold">{PARAMETER_ROLE_LABEL[role]}</p><span className="text-[9px] opacity-70">{keys.length} trường</span></div>
                            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">{keys.map((key) => {
                              const template = templateMap.get(item.template_id);
                              const schemaValue = template?.parameters_schema?.[key];
                              const isNumber = typeof schemaValue === "number";
                              const isJson = key.endsWith("Json");
                              const options = PARAMETER_OPTIONS[key];
                              const semanticKeyField = isSemanticKeyParameter(key);
                              return <label key={key} className={`text-[11px] font-medium ${isJson ? "sm:col-span-2" : ""}`}>
                                <span>{PARAMETER_LABELS[key] || key}</span><span className="ml-1 font-mono text-[9px] opacity-60">{key}</span>
                                {options ? <select value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700">{options.map((option) => <option key={option} value={option}>{option}</option>)}</select> : isJson ? <textarea rows={5} spellCheck={false} value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full resize-y rounded-md border border-slate-200 bg-slate-950 px-2 py-1.5 font-mono text-[10px] leading-relaxed text-slate-100" /> : <input list={semanticKeyField && !isSemanticKeyListParameter(key) ? "semantic-key-options" : undefined} type={isNumber ? "number" : "text"} value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700" />}
                                {semanticKeyField && <select value="" disabled={semanticKeyCatalog.length === 0} onChange={(event) => chooseSemanticKey(item, key, event.target.value)} className="mt-1 w-full rounded-md border border-indigo-200 bg-indigo-50 px-2 py-1.5 font-mono text-[10px] text-indigo-700 disabled:opacity-50"><option value="">{semanticKeyCatalog.length ? (isSemanticKeyListParameter(key) ? "+ Thêm từ bộ Key starter" : "Chọn từ bộ Key starter") : "Chưa khai báo bộ Key starter"}</option>{semanticKeyCatalog.map((entry) => <option key={entry.value} value={entry.value}>{entry.symbol ? `${entry.symbol} · ` : ""}{entry.value}</option>)}</select>}
                              </label>;
                            })}</div>
                          </section>;
                        })}</div>
                      </div>
                      {usesSemanticKeys(engineMode) && !runnerUsesStarterContract(engineMode, String(templateMap.get(item.template_id)?.runner || "")) && <div className="border-t border-indigo-100 pt-3"><div className="flex items-center justify-between gap-2"><div><p className="text-xs font-semibold text-slate-600">Chuẩn bị dữ liệu và trạng thái</p><p className="text-[10px] leading-relaxed text-slate-400">“Thêm bước” không tạo thêm field cho runner. Key chỉ định widget; riêng bước Nhập text mới dùng Value làm dữ liệu đầu vào. Bước expect cũng có thể làm testcase fail.</p></div><button type="button" onClick={() => addItemSetupStep(item)} className="flex items-center gap-1 rounded-md border border-indigo-200 px-2 py-1 text-[10px] font-semibold text-indigo-600 hover:bg-indigo-50"><Plus size={12} /> Thêm bước</button></div>{(item.setup_steps || []).length > 0 && <div className="mt-2 space-y-2">{(item.setup_steps || []).map((step, index) => <div key={index} className="grid grid-cols-1 gap-2 rounded-md border border-indigo-100 bg-white p-2 md:grid-cols-[150px_minmax(140px,1fr)_minmax(130px,1fr)_auto]"><p className="text-[10px] leading-relaxed text-indigo-600 md:col-span-full">{setupStepHint(step.type)}</p><select value={step.type} onChange={(e) => updateItemSetupStep(item, index, { type: e.target.value as SetupStep["type"] })} className="rounded border border-slate-200 px-1.5 py-1 text-[10px]"><option value="tap">Tap key</option><option value="enter_text">Nhập text</option><option value="expect_visible">Bắt buộc thấy</option><option value="expect_absent">Bắt buộc ẩn</option><option value="wait_for_visible">Chờ xuất hiện</option></select><input list="semantic-key-options" value={step.key} onChange={(e) => updateItemSetupStep(item, index, { key: e.target.value })} placeholder="action.open" className="rounded border border-slate-200 px-1.5 py-1 font-mono text-[10px]" />{step.type === "enter_text" ? <input value={step.value || ""} onChange={(e) => updateItemSetupStep(item, index, { value: e.target.value })} placeholder="Giá trị" className="rounded border border-slate-200 px-1.5 py-1 text-[10px]" /> : <div />}{step.type === "wait_for_visible" ? <input type="number" min={100} max={30000} value={step.timeout_ms || suite.step_timeout_ms} onChange={(e) => updateItemSetupStep(item, index, { timeout_ms: Number(e.target.value) })} className="rounded border border-slate-200 px-1.5 py-1 text-[10px]" /> : <div /> }<button type="button" onClick={() => removeItemSetupStep(item, index)} className="rounded p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa bước"><Trash2 size={12} /></button></div>)}</div>}</div>}
                      <div className="overflow-hidden rounded-lg border border-slate-700 bg-slate-950"><div className="flex items-center justify-between border-b border-slate-700 px-3 py-2"><div><p className="text-[11px] font-bold text-slate-100">Code kiểm tra tương đương</p><p className="mt-0.5 text-[9px] text-slate-400">{runnerUsesStarterContract(engineMode, String(templateMap.get(item.template_id)?.runner || "")) ? "Chỉ đọc, cập nhật theo contract và dữ liệu của đề hiện tại." : "Chỉ đọc, cập nhật ngay khi sửa setup, semantic key, input hoặc expected."}</p></div><span className="rounded bg-slate-800 px-2 py-1 font-mono text-[9px] text-cyan-300">{templateMap.get(item.template_id)?.runner}</span></div><pre className="custom-scrollbar max-h-80 overflow-auto whitespace-pre p-3 text-[10px] leading-relaxed text-slate-100">{testcaseCodePreview(item, templateMap.get(item.template_id))}</pre></div>
                      <p className="text-[10px] text-slate-400">Ô mô tả kết quả chỉ đi vào rubric và báo cáo. Các assertion trong code preview mới quyết định testcase pass/fail.</p>
                      </>)}
                    </div>
                  )}
                </div>
                );
              })}
            </div>
          </section>
        </div>

        {typeof document !== "undefined" && createPortal(
          <>
            {previewOpen && (
              <div className="fixed inset-0 z-[55] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4" role="dialog" aria-modal="true" onClick={() => setPreviewOpen(false)}>
                <div className="flex max-h-[92vh] w-full max-w-7xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex items-center justify-between border-b border-slate-200 px-5 py-3"><div><p className="eyebrow">Generated testcase + Key starter</p><h2 className="text-sm font-bold text-slate-800">{examId} · profile {suite.profile}</h2><p className="mt-0.5 text-[10px] text-slate-500">ZIP vẫn chỉ có ba file chấm; bảng Key bên phải là tham chiếu từ Draft/starter.</p></div><button onClick={() => setPreviewOpen(false)} className="rounded-md p-1 text-slate-400 hover:bg-slate-100" aria-label="Đóng"><X size={18} /></button></header>
                  {previewLoading ? <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={22} className="animate-spin" /></div> : previewFiles.length === 0 ? <p className="p-10 text-center text-sm text-slate-500">Chưa đọc được file sinh.</p> : <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,1fr)_340px]">
                    <div className="flex min-h-0 min-w-0 flex-col"><div className="flex gap-1 overflow-x-auto border-b border-slate-200 bg-slate-50 px-3 py-2">{previewFiles.map((file, index) => <button key={file.name} onClick={() => setPreviewFile(index)} className={`shrink-0 rounded-md px-3 py-1.5 font-mono text-xs ${index === previewFile ? "bg-indigo-100 font-bold text-indigo-700" : "text-slate-500 hover:bg-white"}`}>{file.name}</button>)}</div><pre className="custom-scrollbar min-h-0 flex-1 overflow-auto bg-slate-900 p-4 text-[11px] leading-relaxed text-slate-100">{previewFiles[previewFile]?.content}</pre></div>
                    <aside className="custom-scrollbar min-h-0 overflow-y-auto border-t border-slate-200 bg-white p-3 lg:border-l lg:border-t-0">
                      <div className="sticky top-0 z-10 bg-white pb-3"><div className="flex items-center justify-between gap-2"><div><p className="text-xs font-bold text-slate-800">Bộ Key của starter</p><p className="mt-0.5 font-mono text-[9px] text-indigo-600">{suite.key_contract.source_path} · {suite.key_contract.class_name}</p></div><span className="rounded-full bg-indigo-50 px-2 py-1 text-[10px] font-bold text-indigo-700">{semanticKeyCatalog.length} Key</span></div><input value={keyPaletteSearch} onChange={(event) => setKeyPaletteSearch(event.target.value)} placeholder="Tìm tên, giá trị, nhóm..." className="mt-2 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs outline-none focus:border-indigo-400" /></div>
                      {filteredSemanticKeys.length === 0 ? <p className="rounded-lg border border-dashed border-slate-200 p-3 text-xs leading-relaxed text-slate-500">Chưa có Key phù hợp. Hãy khai báo trong “Bộ Semantic Key công bố trong starter” hoặc cấu hình một testcase dùng Key.</p> : <div className="space-y-2">{filteredSemanticKeys.map((key) => <div key={key.value} className="rounded-lg border border-slate-200 bg-slate-50 p-2.5"><div className="flex items-start justify-between gap-2"><div className="min-w-0"><p className="truncate font-mono text-[11px] font-bold text-indigo-700" title={key.value}>{key.value}</p><p className="mt-0.5 truncate font-mono text-[9px] text-slate-500">{key.symbol ? `${suite.key_contract.class_name}.${key.symbol}` : "Tự phát hiện trong Draft"}</p></div><button type="button" onClick={() => copySemanticKey(key.value)} className="shrink-0 rounded-md border border-indigo-200 bg-white px-2 py-1 text-[9px] font-bold text-indigo-700 hover:bg-indigo-50">{copiedKey === key.value ? "Đã chép" : "Chép"}</button></div><div className="mt-2 flex flex-wrap items-center gap-1"><span className="rounded bg-slate-200 px-1.5 py-0.5 text-[9px] text-slate-600">{key.group}</span><span className={`rounded px-1.5 py-0.5 text-[9px] ${key.declared ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>{key.declared ? "Starter công bố" : "Đang dùng"}</span></div>{key.description && <p className="mt-1.5 text-[10px] leading-relaxed text-slate-500">{key.description}</p>}</div>)}</div>}
                    </aside>
                  </div>}
                </div>
              </div>
            )}
            {newTemplateOpen && (
              <div className="fixed inset-0 z-[90] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4" role="dialog" aria-modal="true" onClick={() => !newTemplateSaving && setNewTemplateOpen(false)}>
                <div className="max-h-[92vh] w-full max-w-3xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-start justify-between gap-4"><div><p className="eyebrow">Thư viện template</p><h2 className="mt-1 text-lg font-bold text-slate-800">Tạo testcase template mới</h2><p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">{engineMode === "STARTER_KEY_HYBRID_V1" ? "Tạo mẫu kiểm tra starter cho Logic/SQLite hoặc mẫu semantic Key cho UI; chế độ này không dùng grading_adapter.dart." : engineMode === "TEMPLATE_CONTRACT_V1" ? "Chọn một runner contract đã được engine hỗ trợ rồi đặt các giá trị mặc định." : "Tạo một khung tái sử dụng từ runner Key đã có. Logic pass/fail do runner và schema tham số quyết định."}</p></div><button type="button" onClick={() => setNewTemplateOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100" aria-label="Đóng"><X size={18} /></button></div>
                  <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2">
                    <label className="text-xs font-semibold text-slate-600">Mã template <span className="font-normal text-slate-400">(để trống để tự sinh)</span><input value={newTemplate.template_id} onChange={(e) => setNewTemplate((v) => ({ ...v, template_id: e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "") }))} placeholder="CUSTOM_EMAIL_INVALID" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 font-mono text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600">Tên template<input value={newTemplate.name} onChange={(e) => setNewTemplate((v) => ({ ...v, name: e.target.value }))} placeholder="Kiểm tra email không hợp lệ" className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Mô tả<textarea rows={2} value={newTemplate.description} onChange={(e) => setNewTemplate((v) => ({ ...v, description: e.target.value }))} placeholder="Mô tả điều kiện và mục đích kiểm tra" className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600">Skill code<select value={newTemplate.skill_code} onChange={(e) => setNewTemplate((v) => ({ ...v, skill_code: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 font-mono text-xs">{skillOptions.length === 0 && <option value={newTemplate.skill_code}>{newTemplate.skill_code}</option>}{skillOptions.map((skill) => <option key={skill.code} value={skill.code}>{skill.code} · {skill.name || SKILL_LABEL[skill.code] || skill.code}</option>)}</select><span className="mt-1 block text-[10px] font-normal text-slate-400">Chỉ hiển thị skill đang bật và có thể chấm tự động.</span></label>
                    <label className="text-xs font-semibold text-slate-600">Runner<select value={newTemplate.runner} onChange={(e) => changeNewTemplateRunner(e.target.value)} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs">{Object.entries(RUNNER_LABEL).filter(([key]) => key !== "GROUP" && templatesForEngine.some((template) => template.runner === key && !template.custom)).map(([key, label]) => <option key={key} value={key}>{key} · {label}</option>)}</select></label>
                    <label className="text-xs font-semibold text-slate-600">Layer<select value={newTemplate.layer} onChange={(e) => setNewTemplate((v) => ({ ...v, layer: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs">{Object.entries(LAYER_LABEL).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
                    <label className="text-xs font-semibold text-slate-600">Nhóm<select value={newTemplate.testcase_group} onChange={(e) => setNewTemplate((v) => ({ ...v, testcase_group: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs"><option value="LOGIC">Testcase Logic</option><option value="WIDGET">Testcase Widget</option><option value="BEHAVIOR">Testcase Behavior</option></select></label>
                    <label className="text-xs font-semibold text-slate-600">Độ khó<select value={newTemplate.difficulty} onChange={(e) => setNewTemplate((v) => ({ ...v, difficulty: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs"><option value="basic">Cơ bản</option><option value="intermediate">Trung bình</option><option value="advanced">Nâng cao</option></select></label>
                    <label className="text-xs font-semibold text-slate-600">Trọng số mặc định<input type="number" min={0} max={100} step={0.5} value={newTemplate.weight_default} onChange={(e) => setNewTemplate((v) => ({ ...v, weight_default: e.target.value }))} className="mt-1.5 w-full rounded-md border border-slate-200 px-2.5 py-2 text-xs" /></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Expected template<textarea rows={2} value={newTemplate.expected_template} onChange={(e) => setNewTemplate((v) => ({ ...v, expected_template: e.target.value }))} className="mt-1.5 w-full resize-y rounded-md border border-slate-200 px-2.5 py-2 text-xs" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Đây là mô tả rubric. Kết quả pass/fail thật do runner và parameters_schema quyết định.</span></label>
                    <label className="text-xs font-semibold text-slate-600 md:col-span-2">Schema tham số mặc định (JSON object)<textarea rows={8} value={newTemplate.parameters_schema} onChange={(e) => setNewTemplate((v) => ({ ...v, parameters_schema: e.target.value }))} spellCheck={false} className="mt-1.5 w-full resize-y rounded-md border border-slate-200 bg-slate-900 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-100" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Các key phải đúng với runner đã chọn. Không nhập Dart code tại đây.</span></label>
                    {runnerUsesStarterContract(engineMode, newTemplate.runner) && <label className="text-xs font-semibold text-slate-600 md:col-span-2">Ánh xạ tham số → mã contract (JSON object)<textarea rows={5} value={newTemplate.contract_bindings} onChange={(e) => setNewTemplate((v) => ({ ...v, contract_bindings: e.target.value }))} spellCheck={false} className="mt-1.5 w-full resize-y rounded-md border border-slate-200 bg-slate-900 px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-100" /><span className="mt-1 block text-[10px] font-normal text-slate-400">Ví dụ: {`{"sourcePath":"model.path","className":"model.class"}`}. Chỉ ánh xạ những tham số cần tự nhận từ contract; vẫn có thể sửa riêng sau khi thêm vào đề.</span></label>}
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
