"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  AlertCircle, CheckCircle2, ChevronLeft, ChevronRight, Code2, Eye, GripVertical,
  Download, Layers, Lightbulb, Loader2, Package, Plus, Save, Settings2, Trash2, UploadCloud, X,
} from "lucide-react";

type JsonMap = Record<string, unknown>;

/** Testcase gõ tay: backend nhận diện qua template_id/runner này thay vì tra thư viện template. */
const CUSTOM_TEMPLATE_ID = "CUSTOM_CODE";

interface Template {
  template_id: string;
  template_version: string;
  engine_type?: string;
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
  /** BUILTIN = bản gốc trong hệ thống; OVERRIDE = bản gốc đã sửa; CUSTOM = giáo viên tự thêm. */
  origin?: string;
  hidden?: boolean;
  restorable?: boolean;
}

/** Mô tả một tham số của runner — backend trả về để dựng form thêm/sửa testcase. */
interface RunnerParam {
  name: string;
  label: string;
  type: "text" | "semantic_key" | "semantic_keys" | "values" | "number" | "bool" | "enum";
  required: boolean;
  default?: unknown;
  hint?: string;
  options?: string[];
  min?: number;
  pair_with?: string;
}

interface RunnerDef {
  runner: string;
  label: string;
  layer_default: string;
  description: string;
  parameters: RunnerParam[];
  parameters_schema: JsonMap;
}

interface RunnerCatalog {
  runners: RunnerDef[];
  semantic_keys: string[];
  layers: string[];
  difficulties: string[];
  testcase_groups: Record<string, string>;
}

/** Một dòng trong hợp đồng bài làm: semantic key này được dò thế nào khi bài không gắn key. */
interface ContractKey {
  key: string;
  label: string;
  required: boolean;
  strategy: string;
  value?: string;
  text?: string;
  index: number;
}

interface ContractStrategy {
  code: string;
  label: string;
  needs_value: boolean;
  needs_text: boolean;
  uses_index: boolean;
}

interface ContractCatalog {
  strategies: ContractStrategy[];
  icon_groups: string[];
  default_keys: ContractKey[];
  common_widget_types: string[];
}

interface TemplateDraft {
  template_id: string;
  name: string;
  description: string;
  runner: string;
  skill_code: string;
  layer: string;
  difficulty: string;
  weight_default: number;
  testcase_group: string;
  expected_template: string;
  parameters_schema: JsonMap;
}

interface TestcaseItem {
  instance_id: string;
  template_id: string;
  template_version: string;
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
  runner?: string;
  /** Thân testWidgets do giáo viên gõ; chỉ có ở testcase tự viết. */
  custom_code?: string;
}

interface GeneratedFile {
  name: string;
  content: string;
}

interface SkillOption {
  code: string;
  name?: string;
}

const isCustomItem = (item: TestcaseItem) => item.template_id === CUSTOM_TEMPLATE_ID;

const DIFF_LABEL: Record<string, string> = {
  basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao",
};

const LAYER_LABEL: Record<string, string> = {
  CONTRACT: "Hợp đồng API", MODEL: "Mô hình dữ liệu", REPOSITORY: "Truy cập dữ liệu", VIEWMODEL: "Trạng thái & xử lý",
  SCREEN: "Giao diện màn hình", BLACKBOX: "Chức năng người dùng", RESPONSIVE: "Tương thích kích thước",
  CUSTOM: "Tự viết code",
};

/** Key theo docs/common-testcase-contract.md — gợi ý khi gõ tham số và khi viết code tay. */
const SEMANTIC_KEYS = [
  "screen.home", "screen.list", "screen.detail",
  "field.title", "field.name", "field.fullName", "field.email", "field.avatar",
  "action.save", "action.item.edit", "action.delete", "action.delete.cancel",
  "action.back", "action.open-detail",
  "list.items", "item.1", "item.2", "item.3",
  "dialog.delete", "message.success", "state.empty", "state.loaded",
  "text.screen.title", "error.title", "error.email",
];

/** Mẫu thân testWidgets chèn vào editor; đều dùng helper có sẵn của engine. */
const CODE_SNIPPETS: { id: string; label: string; code: string }[] = [
  {
    id: "blank",
    label: "Khung trống có hướng dẫn",
    code: `// 1. Mở app của sinh viên
await _boot(tester);

// 2. Thao tác: tap / enterText rồi chờ giao diện cập nhật
// await tester.tap(_byKey('action.save'));
// await _settle(tester);

// 3. Khẳng định kết quả — expect sai là testcase FAIL
expect(tester.takeException(), isNull);`,
  },
  {
    id: "widget",
    label: "Mở app và kiểm tra widget hiển thị",
    code: `await _boot(tester);
expect(_byKey('screen.home'), findsOneWidget, reason: 'Thiếu màn hình chính');
expect(_byKey('list.items'), findsOneWidget, reason: 'Thiếu danh sách');
expect(tester.takeException(), isNull);`,
  },
  {
    id: "submit",
    label: "Nhập form hợp lệ rồi submit",
    code: `await _boot(tester);
await tester.enterText(_byKey('field.title'), 'Nguyen Van A');
await tester.enterText(_byKey('field.email'), 'a@example.com');
await tester.tap(_byKey('action.save'));
await _settle(tester);
expect(_byKey('message.success'), findsOneWidget,
    reason: 'Submit dữ liệu hợp lệ phải báo thành công');`,
  },
  {
    id: "validate",
    label: "Validate: bỏ trống và nhập sai định dạng",
    code: `await _boot(tester);
// Bỏ trống ô bắt buộc + nhập email sai định dạng
await tester.enterText(_byKey('field.title'), '');
await tester.enterText(_byKey('field.email'), 'email-sai');
await tester.tap(_byKey('action.save'));
await _settle(tester);
expect(_byKey('error.title'), findsOneWidget, reason: 'Ô bắt buộc bỏ trống phải báo lỗi');
expect(_byKey('error.email'), findsOneWidget, reason: 'Email sai định dạng phải báo lỗi');
expect(_byKey('message.success'), findsNothing, reason: 'Dữ liệu sai thì không được lưu');`,
  },
  {
    id: "navigation",
    label: "Điều hướng sang màn hình khác rồi quay lại",
    code: `await _boot(tester);
await tester.tap(_byKey('action.open-detail'));
await _settle(tester);
expect(_byKey('screen.detail'), findsOneWidget, reason: 'Không mở được màn hình chi tiết');
await tester.tap(_byKey('action.back'));
await _settle(tester);
expect(_byKey('screen.home'), findsOneWidget, reason: 'Không quay lại được màn hình chính');`,
  },
  {
    id: "list",
    label: "Đếm số mục trong danh sách",
    code: `await _boot(tester);
final list = _byKey('list.items');
expect(list, findsOneWidget);
final rows = find.descendant(of: list, matching: find.byType(ListTile));
expect(rows, findsNWidgets(3), reason: 'Danh sách phải hiển thị đúng 3 mục');`,
  },
  {
    id: "layout",
    label: "Đo kích thước và khoảng cách",
    code: `await _boot(tester);
final avatar = _byKey('field.avatar');
expect(avatar, findsOneWidget);
expect(tester.getSize(avatar).height, closeTo(80, 0.5),
    reason: 'Ảnh đại diện phải cao 80px');

final gap = tester.getRect(_byKey('field.email')).top
    - tester.getRect(_byKey('field.title')).bottom;
expect(gap, closeTo(12, 0.5), reason: 'Hai ô nhập phải cách nhau 12px');`,
  },
  {
    id: "responsive",
    label: "Responsive: đổi kích thước màn hình",
    code: `tester.view.devicePixelRatio = 1.0;
addTearDown(tester.view.resetPhysicalSize);
addTearDown(tester.view.resetDevicePixelRatio);

tester.view.physicalSize = const Size(390, 844);
await _boot(tester);
expect(tester.takeException(), isNull, reason: 'Màn hình dọc bị tràn');

tester.view.physicalSize = const Size(1024, 768);
await _settle(tester);
expect(tester.takeException(), isNull, reason: 'Màn hình ngang bị tràn');`,
  },
  {
    id: "dialog",
    label: "Hộp thoại xác nhận",
    code: `await _boot(tester);
await tester.tap(_byKey('action.delete'));
await _settle(tester);
expect(find.byType(AlertDialog), findsOneWidget,
    reason: 'Phải hỏi xác nhận trước khi xóa');
await tester.tap(_byKey('action.delete.cancel'));
await _settle(tester);
expect(find.byType(AlertDialog), findsNothing, reason: 'Bấm Hủy phải đóng hộp thoại');`,
  },
  {
    id: "text",
    label: "Nội dung chữ và kiểu chữ",
    code: `await _boot(tester);
final title = _byKey('text.screen.title');
expect(title, findsOneWidget);
final label = tester.widget<Text>(title);
expect(label.data, 'Danh sách người dùng');
final style = DefaultTextStyle.of(tester.element(title)).style.merge(label.style);
expect(style.fontSize, closeTo(20, 0.5));
expect(style.fontWeight, FontWeight.w700);`,
  },
];

/** Bảng tra nhanh những gì dùng được trong thân test (engine đã định nghĩa sẵn). */
const CODE_HELPERS: { code: string; desc: string }[] = [
  { code: "await _boot(tester)", desc: "Chạy main() của bài và chờ giao diện ổn định" },
  { code: "await _settle(tester)", desc: "Chờ sau khi tap / nhập liệu" },
  { code: "_byKey('field.email')", desc: "Finder theo ValueKey, dò thay thế theo vai trò nếu bài không gắn key" },
  { code: "tester.tap(finder)", desc: "Bấm vào widget" },
  { code: "tester.enterText(finder, 'abc')", desc: "Nhập chữ vào ô nhập" },
  { code: "tester.widget<Text>(finder)", desc: "Đọc widget để lấy thuộc tính" },
  { code: "tester.getSize/getRect(finder)", desc: "Đo kích thước và vị trí thật khi render" },
  { code: "expect(finder, findsOneWidget)", desc: "findsNothing · findsWidgets · findsNWidgets(n)" },
  { code: "expect(so, closeTo(80, 0.5))", desc: "So sánh số có sai số" },
  { code: "expect(tester.takeException(), isNull)", desc: "Không có exception / lỗi tràn layout" },
  { code: "find.byType(ListTile)", desc: "find.text · find.descendant(of:, matching:)" },
  { code: "reason: 'Vì sao fail'", desc: "Ghi vào kết quả chấm, sinh viên đọc được" },
];

/** Giá trị sai/rỗng hay dùng khi kiểm tra validate — dựng thành dropdown cho giáo viên. */
const INVALID_VALUE_OPTIONS: { value: string; label: string }[] = [
  { value: "__EMPTY__", label: "Bỏ trống (chuỗi rỗng)" },
  { value: " ", label: "Chỉ có khoảng trắng" },
  { value: "A", label: "Quá ngắn (1 ký tự)" },
  { value: "email-sai", label: "Email sai định dạng" },
  { value: "a@b", label: "Email thiếu tên miền" },
  { value: "abc", label: "Nhập chữ vào ô số" },
  { value: "-1", label: "Số âm" },
  { value: "0", label: "Số 0" },
  { value: "999999999999", label: "Số quá lớn" },
  { value: "2026-13-45", label: "Ngày không hợp lệ" },
  { value: "!@#$%^&*", label: "Ký tự đặc biệt" },
];

const VALID_VALUE_OPTIONS: { value: string; label: string }[] = [
  { value: "Nguyen Van A", label: "Họ tên hợp lệ" },
  { value: "user@example.com", label: "Email hợp lệ" },
  { value: "Task mới", label: "Tiêu đề hợp lệ" },
  { value: "100000", label: "Số hợp lệ" },
  { value: "2026-08-06", label: "Ngày hợp lệ" },
];

/** Tham số dạng danh sách giá trị, ăn theo thứ tự của fieldKeys. */
const PAIRED_VALUE_PARAMS: Record<string, { source: string; options: { value: string; label: string }[]; hint: string }> = {
  invalidValues: { source: "fieldKeys", options: INVALID_VALUE_OPTIONS, hint: "Dữ liệu sai để bắt form báo lỗi" },
  values: { source: "fieldKeys", options: VALID_VALUE_OPTIONS, hint: "Dữ liệu hợp lệ để submit thành công" },
  expectedValues: { source: "fieldKeys", options: VALID_VALUE_OPTIONS, hint: "Giá trị form phải tự điền sẵn" },
};

/** Mẫu khai một thành phần cho phần "Gõ config" — mỗi mẫu là một cách dò khác nhau. */
const CONTRACT_SNIPPETS: { id: string; label: string; row: ContractKey & { text?: string } }[] = [
  {
    id: "widget_type",
    label: "Theo loại widget — vd danh sách dạng grid",
    row: { key: "list.items", label: "Danh sách", required: false, strategy: "widget_type", value: "SliverGrid", index: 0 },
  },
  {
    id: "icon",
    label: "Theo icon — nút chỉ có icon, không chữ",
    row: { key: "action.item.edit", label: "Nút sửa trên item", required: false, strategy: "icon", value: "edit", index: 0 },
  },
  {
    id: "tooltip",
    label: "Theo tooltip của nút",
    row: { key: "action.delete", label: "Nút xóa", required: false, strategy: "tooltip", value: "Delete", index: 0 },
  },
  {
    id: "button_text",
    label: "Theo nút chứa chữ (nhận regex)",
    row: { key: "action.save", label: "Nút thêm/lưu", required: false, strategy: "button_text", value: "/^(add|update) user$/", index: 0 },
  },
  {
    id: "text",
    label: "Theo chữ hiển thị — vd tiêu đề màn hình",
    row: { key: "screen.detail", label: "Màn hình chi tiết", required: false, strategy: "text", value: "User Detail", index: 0 },
  },
  {
    id: "type_with_text",
    label: "Widget loại X chứa chữ Y — vd thẻ của một user",
    row: { key: "action.open-detail", label: "Vùng bấm mở chi tiết", required: false, strategy: "type_with_text", value: "InkWell", text: "/@gmail\\.com$/", index: 0 },
  },
  {
    id: "index",
    label: "Phần tử thứ N cùng loại — vd ô nhập thứ hai",
    row: { key: "field.email", label: "Ô nhập email", required: false, strategy: "widget_type", value: "TextField", index: 1 },
  },
  {
    id: "key_only",
    label: "Không dò thay thế — bắt buộc gắn ValueKey",
    row: { key: "field.avatar", label: "Ảnh đại diện", required: true, strategy: "key_only", value: "", index: 0 },
  },
];

/** Nghĩa từng trường trong config, hiện ở bảng tra cạnh ô gõ. */
const CONTRACT_FIELD_HELP: { field: string; desc: string }[] = [
  { field: "key", desc: "Tên định danh, dạng nhom.ten — vd field.email, action.delete" },
  { field: "label", desc: "Mô tả tiếng Việt, in ra đề bài cho sinh viên đọc" },
  { field: "strategy", desc: "Cách nhận diện thay thế khi bài không gắn ValueKey (xem danh sách bên dưới)" },
  { field: "value", desc: "Tên widget / nhóm icon / chuỗi cần khớp; bọc /…/ để dùng regex" },
  { field: "text", desc: "Chỉ dùng với type_with_text: chữ nằm bên trong widget đó" },
  { field: "index", desc: "Lấy phần tử thứ mấy khi nhiều widget cùng khớp (0 = đầu tiên)" },
  { field: "required", desc: "true = thành phần bắt buộc phải có trong bài làm" },
  { field: "allow_fallback", desc: "true = vẫn được dò dù đề bật \"bắt buộc gắn ValueKey\"" },
];

/** Nhãn tiếng Việt cho nhóm icon của hợp đồng bài làm (khớp _iconGroups trong engine). */
const ICON_GROUP_LABEL: Record<string, string> = {
  edit: "Sửa (bút)", delete: "Xóa (thùng rác)", add: "Thêm (dấu +)", save: "Lưu / xác nhận",
  back: "Quay lại", forward: "Đi tiếp / xem chi tiết", close: "Đóng / hủy", search: "Tìm kiếm",
  person: "Người dùng", email: "Email", image: "Ảnh", menu: "Menu / thêm tùy chọn",
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
  COMMON_V1: "Bộ testcase dùng chung",
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

/** Chuyển dữ liệu Draft thành literal Dart để code preview phản ánh đúng giá trị đang nhập. */
function dartLiteral(value: unknown): string {
  if (value === null || value === undefined) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : "0";
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `<dynamic>[${value.map(dartLiteral).join(", ")}]`;
  if (typeof value === "object") {
    const entries = Object.entries(value as JsonMap)
      .map(([key, entry]) => `${JSON.stringify(key)}: ${dartLiteral(entry)}`);
    return `<String, dynamic>{${entries.length ? `\n    ${entries.join(",\n    ")}\n  ` : ""}}`;
  }
  return JSON.stringify(String(value));
}

/**
 * Code tương đương mà common engine đăng ký cho một instance. Đây là preview chỉ đọc;
 * runner và toàn bộ parameters thay đổi ngay trên màn hình khi giảng viên sửa form.
 */
function testcaseCodePreview(item: TestcaseItem, template?: Template) {
  if (isCustomItem(item)) {
    const body = String(item.custom_code || "").split("\n").map((line) => `  ${line}`).join("\n");
    return `testWidgets(${JSON.stringify(item.instance_id)}, (tester) async {\n${body}\n});`;
  }
  const runner = String(item.runner || template?.runner || "");
  const expected = String(item.expected || "").replace(/[\r\n]+/g, " ").trim();
  return [
    `// Expected trong rubric: ${expected || "(chưa nhập)"}`,
    `testWidgets(${JSON.stringify(item.instance_id)}, (tester) async {`,
    `  await _runCase(tester, ${JSON.stringify(item.instance_id)}, <String, dynamic>{`,
    `    'runner': ${JSON.stringify(runner)},`,
    `    'parameters': ${dartLiteral(item.parameters || {})},`,
    "  });",
    "});",
  ].join("\n");
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
  matchMode: ["equals", "contains"],
  fieldType: ["input", "text"],
  expectedEnabled: ["true", "false"],
};

/** Nhãn tiếng Việt cho giá trị trong dropdown; thiếu thì hiển thị nguyên giá trị kỹ thuật. */
const PARAMETER_OPTION_LABELS: Record<string, string> = {
  any: "Bất kỳ loại nào", form: "Form", image: "Ảnh", text: "Chữ (Text)", input: "Ô nhập",
  button: "Nút bấm", padding: "Padding", container: "Khung chứa",
  height: "Chiều cao", width: "Chiều rộng",
  equals: "Bằng đúng", at_least: "Tối thiểu", at_most: "Tối đa", contains: "Có chứa",
  vertical: "Dọc", horizontal: "Ngang",
  true: "Bật (dùng được)", false: "Tắt (bị khóa)",
  w400: "w400 — Thường", w500: "w500 — Hơi đậm", w600: "w600 — Đậm vừa",
  w700: "w700 — Đậm", w800: "w800 — Rất đậm",
};

/** Tham số chỉ nhận một semantic key → gợi ý sẵn danh sách key chuẩn. */
const SINGLE_KEY_PARAMS = new Set([
  "widgetKey", "rootKey", "targetKey", "submitKey", "listKey", "openKey", "destinationKey",
  "backKey", "homeKey", "buttonKey", "resultKey", "initialKey", "actionKey", "updatedKey",
  "absentKey", "dialogKey", "decisionKey", "editKey", "fromKey", "toKey",
]);

const splitCsv = (value: unknown) => String(value ?? "").split(",").map((v) => v.trim()).filter(Boolean);

/** Ép một CSV về đúng số phần tử; ô trống được điền mặc định theo vị trí. */
const resizeCsv = (current: string, count: number, fill: (index: number) => string) => {
  const parts = String(current ?? "").split(",").map((v) => v.trim());
  return Array.from({ length: count }, (_, i) => parts[i] || fill(i)).join(",");
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

// ── Editor code trong trình duyệt ──────────────────────────────────────────
// Tự viết thay vì kéo CodeMirror/Monaco: artifact chấm chạy offline, thêm 1 thư viện
// editor vài trăm KB chỉ để tô màu Dart là không đáng.

// Cỡ chữ phải là số NGUYÊN: 13.5px được textarea và <pre> làm tròn khác nhau nên
// hai lớp lệch dần theo chiều ngang, gõ tới cuối dòng là thấy rõ.
const EDITOR_LINE_HEIGHT = 24;
const EDITOR_FONT = "text-[14px]";
const CLOSING_PAIR: Record<string, string> = { "(": ")", "[": "]", "{": "}", "'": "'", '"': '"' };

type EditorLanguage = "dart" | "json";

/** Một lượt quét: chú thích → chuỗi → số → từ khóa → tên class → lời gọi hàm. */
const DART_TOKEN = new RegExp(
  "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
  + "|(?<str>r?'''[\\s\\S]*?'''|r?\"\"\"[\\s\\S]*?\"\"\"|r?'(?:\\\\.|[^'\\\\\\n])*'|r?\"(?:\\\\.|[^\"\\\\\\n])*\")"
  + "|(?<num>\\b\\d+(?:\\.\\d+)?\\b)"
  + "|(?<kw>\\b(?:await|async|final|const|var|late|dynamic|void|new|return|if|else|for|while|do|in|is|as|switch|case|default|break|continue|try|catch|finally|throw|true|false|null|this|super|expect|reason)\\b)"
  + "|(?<type>\\b[A-Z][A-Za-z0-9_]*)"
  + "|(?<call>\\b_?[a-z][A-Za-z0-9_]*(?=\\())",
  "g",
);

/** Tên thuộc tính tô khác giá trị chuỗi để nhìn ra ngay cấu trúc config. */
const JSON_TOKEN = new RegExp(
  "(?<jkey>\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:))"
  + "|(?<jstr>\"(?:\\\\.|[^\"\\\\])*\")"
  + "|(?<jnum>-?\\b\\d+(?:\\.\\d+)?\\b)"
  + "|(?<jkw>\\b(?:true|false|null)\\b)",
  "g",
);

// Mỗi token có bản cho nền sáng và bản dark: — editor đi theo theme của app, chứ hộp code
// tối cứng giữa giao diện sáng thì mắt vừa quen nền trắng nhìn vào sẽ thấy chữ chìm hẳn.
const TOKEN_CLASS: Record<EditorLanguage, Record<string, string>> = {
  dart: {
    comment: "text-slate-500 italic dark:text-slate-300",
    str: "text-amber-700 dark:text-amber-200",
    num: "text-orange-700 dark:text-orange-200",
    kw: "text-sky-700 dark:text-sky-300",
    type: "text-emerald-700 dark:text-emerald-200",
    call: "text-violet-700 dark:text-violet-200",
  },
  json: {
    jkey: "text-sky-700 dark:text-sky-300",
    jstr: "text-amber-700 dark:text-amber-200",
    jnum: "text-orange-700 dark:text-orange-200",
    jkw: "text-violet-700 dark:text-violet-200",
  },
};

const escapeHtml = (text: string) =>
  text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

function highlight(code: string, language: EditorLanguage) {
  const classes = TOKEN_CLASS[language];
  const pattern = language === "json" ? JSON_TOKEN : DART_TOKEN;
  // KHÔNG thêm xuống dòng ở đầu: luật "bỏ newline đầu tiên sau <pre>" chỉ áp dụng khi
  // trình duyệt PARSE thẻ <pre>, còn innerHTML thì giữ nguyên → cả lớp tô màu tụt xuống
  // một dòng so với textarea, làm con trỏ và chữ lệch nhau.
  let out = "";
  let last = 0;
  for (const match of code.matchAll(pattern)) {
    const groups = match.groups || {};
    const kind = Object.keys(classes).find((name) => groups[name] !== undefined);
    if (!kind || match.index === undefined) continue;
    out += escapeHtml(code.slice(last, match.index));
    out += `<span class="${classes[kind]}">${escapeHtml(match[0])}</span>`;
    last = match.index + match[0].length;
  }
  // Ký tự xuống dòng cuối phải còn lại, nếu không dòng trống cuối bị mất và lệch số dòng.
  return out + escapeHtml(code.slice(last)) + "\n";
}

/**
 * Editor code trên web: số dòng, tô màu cú pháp, tự thụt lề, tự đóng ngoặc,
 * Tab/Shift+Tab thụt-lùi cả khối, Ctrl+/ bật tắt chú thích.
 */
function CodeEditor({ value, onChange, rows = 14, bare = false, language = "dart", hint }: {
  value: string; onChange: (next: string) => void; rows?: number;
  /** Bỏ viền/bo góc khi editor nằm trong khung có sẵn dòng bọc testWidgets. */
  bare?: boolean;
  language?: EditorLanguage;
  /** Chữ nhắc bên trái thanh trạng thái; mặc định là phím tắt. */
  hint?: string;
}) {
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const [caret, setCaret] = useState({ line: 1, column: 1 });

  const lines = value.split("\n");
  const highlighted = useMemo(() => highlight(value, language), [value, language]);

  /** Ghi giá trị mới rồi đặt lại con trỏ — textarea là controlled nên phải chờ render xong. */
  const commit = (next: string, selectionStart: number, selectionEnd = selectionStart) => {
    onChange(next);
    requestAnimationFrame(() => {
      const el = areaRef.current;
      if (!el) return;
      el.selectionStart = selectionStart;
      el.selectionEnd = selectionEnd;
      syncCaret();
    });
  };

  const syncCaret = () => {
    const el = areaRef.current;
    if (!el) return;
    const before = el.value.slice(0, el.selectionStart);
    const row = before.split("\n");
    setCaret({ line: row.length, column: row[row.length - 1].length + 1 });
  };


  /** Đầu dòng chứa vị trí `at`. */
  const lineStart = (text: string, at: number) => text.lastIndexOf("\n", at - 1) + 1;

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const el = event.currentTarget;
    const start = el.selectionStart;
    const end = el.selectionEnd;
    const before = value.slice(0, start);
    const after = value.slice(end);

    if (event.key === "Tab") {
      event.preventDefault();
      const multiline = value.slice(start, end).includes("\n");
      if (!multiline && !event.shiftKey) {
        commit(`${before}  ${after}`, start + 2);
        return;
      }
      const from = lineStart(value, start);
      const block = value.slice(from, end);
      if (event.shiftKey) {
        const dedented = block.replace(/^ {1,2}/gm, "");
        commit(value.slice(0, from) + dedented + after, from, from + dedented.length);
      } else {
        const indented = block.replace(/^/gm, "  ");
        commit(value.slice(0, from) + indented + after, from, from + indented.length);
      }
      return;
    }

    // Ctrl+/ bật tắt chú thích cho mọi dòng đang chọn. JSON không có chú thích nên bỏ qua,
    // chèn "//" vào config sẽ làm hỏng JSON.
    if (language === "dart" && (event.ctrlKey || event.metaKey) && event.key === "/") {
      event.preventDefault();
      const from = lineStart(value, start);
      const lineEnd = value.indexOf("\n", end);
      const to = lineEnd < 0 ? value.length : lineEnd;
      const block = value.slice(from, to);
      const allCommented = block.split("\n").every((line) => !line.trim() || line.trimStart().startsWith("//"));
      const next = allCommented
        ? block.replace(/^(\s*)\/\/ ?/gm, "$1")
        : block.replace(/^(\s*)(?=\S)/gm, "$1// ");
      commit(value.slice(0, from) + next + value.slice(to), from, from + next.length);
      return;
    }

    if (event.key === "Enter") {
      event.preventDefault();
      const currentLine = value.slice(lineStart(value, start), start);
      const indent = (currentLine.match(/^\s*/) || [""])[0];
      const opensBlock = /[{([]\s*$/.test(currentLine);
      const nextChar = after.charAt(0);
      const closesRightAfter = opensBlock && /^[})\]]/.test(nextChar);
      const inner = indent + (opensBlock ? "  " : "");
      if (closesRightAfter) {
        // Đặt dấu đóng xuống dòng riêng để khối nhìn đúng như trong IDE.
        commit(`${before}\n${inner}\n${indent}${after}`, start + 1 + inner.length);
      } else {
        commit(`${before}\n${inner}${after}`, start + 1 + inner.length);
      }
      return;
    }

    // Gõ dấu đóng ngay trước dấu đóng có sẵn thì chỉ đi qua, không nhân đôi.
    if (start === end && [")", "]", "}", "'", '"'].includes(event.key) && after.charAt(0) === event.key) {
      event.preventDefault();
      commit(value, start + 1);
      return;
    }

    if (start === end && CLOSING_PAIR[event.key]) {
      // Với nháy, chỉ tự đóng khi đứng ở chỗ trống — tránh phá `don't` hay `map['k']`.
      const quote = event.key === "'" || event.key === '"';
      if (!quote || !/[A-Za-z0-9_'"]/.test(after.charAt(0) || "")) {
        event.preventDefault();
        commit(`${before}${event.key}${CLOSING_PAIR[event.key]}${after}`, start + 1);
        return;
      }
    }

    if (event.key === "Backspace" && start === end && start > 0) {
      const previous = value.charAt(start - 1);
      if (CLOSING_PAIR[previous] && after.charAt(0) === CLOSING_PAIR[previous]) {
        event.preventDefault();
        commit(before.slice(0, -1) + after.slice(1), start - 1);
      }
    }
  };

  return (
    <div className={`overflow-hidden bg-white dark:bg-slate-900 ${bare ? "" : "rounded-lg border border-slate-300 focus-within:border-indigo-400 focus-within:ring-2 focus-within:ring-indigo-100 dark:border-slate-700 dark:focus-within:ring-indigo-500/30"}`}>
      {/* MỘT vùng cuộn duy nhất bọc cả gutter, lớp tô màu và textarea. Trước đây textarea
          tự cuộn nên thanh cuộn ngang của nó nằm đè lên dòng code cuối. */}
      <div
        className="custom-scrollbar resize-y overflow-auto"
        style={{ height: rows * EDITOR_LINE_HEIGHT + 16, minHeight: 6 * EDITOR_LINE_HEIGHT + 16 }}
      >
        <div className="flex min-h-full w-max min-w-full">
          <div
            aria-hidden
            className={`pointer-events-none sticky left-0 z-10 shrink-0 select-none border-r border-slate-200 bg-slate-50 py-2 pl-3 pr-2 text-right font-mono dark:border-slate-800 dark:bg-slate-950 ${EDITOR_FONT} text-slate-400 dark:text-slate-500`}
            style={{ lineHeight: `${EDITOR_LINE_HEIGHT}px`, minWidth: 48 }}
          >
            {lines.map((_, index) => <div key={index}>{index + 1}</div>)}
          </div>
          <div className="relative min-h-full flex-1">
            <pre
              aria-hidden
              className={`pointer-events-none m-0 min-h-full py-2 pl-3 pr-8 font-mono ${EDITOR_FONT} text-slate-800 dark:text-slate-50`}
              style={{ lineHeight: `${EDITOR_LINE_HEIGHT}px`, whiteSpace: "pre", tabSize: 2 }}
              dangerouslySetInnerHTML={{ __html: highlighted }}
            />
            {!value && (
              <p className={`pointer-events-none absolute left-3 top-2 font-mono ${EDITOR_FONT} text-slate-400 dark:text-slate-600`} style={{ lineHeight: `${EDITOR_LINE_HEIGHT}px` }}>
                {language === "json" ? '{ "require_keys": false, "keys": [] }' : "await _boot(tester);"}
              </p>
            )}
            <textarea
              ref={areaRef}
              value={value}
              // Ký tự tab dán từ IDE khác sẽ rộng khác nhau giữa <pre> và <textarea>
              // → lớp tô màu lệch khỏi con trỏ. Quy về 2 khoảng trắng ngay khi nhập.
              onChange={(e) => { onChange(e.target.value.replace(/\t/g, "  ")); syncCaret(); }}
              onKeyDown={handleKeyDown}
              onKeyUp={syncCaret}
              onClick={syncCaret}
              spellCheck={false}
              wrap="off"
              className={`absolute inset-0 z-20 h-full w-full resize-none overflow-hidden border-0 bg-transparent py-2 pl-3 pr-8 font-mono ${EDITOR_FONT} caret-slate-900 outline-none selection:bg-indigo-200 dark:caret-sky-300 dark:selection:bg-indigo-500/40`}
              style={{
                lineHeight: `${EDITOR_LINE_HEIGHT}px`,
                tabSize: 2,
                // BẮT BUỘC để ở inline style: globals.css có rule KHÔNG nằm trong @layer
                // `select, input, textarea { color: var(--foreground) }`, mà style không phân
                // lớp thì thắng mọi utility của Tailwind — kể cả text-transparent. Hậu quả:
                // chữ thô của textarea bị vẽ đè lên lớp tô màu (nền sáng thì thành chữ đen
                // chồng chữ màu → nhìn mờ nhòe). Inline style mới chặn được rule đó.
                color: "transparent",
                WebkitTextFillColor: "transparent",
              }}
            />
          </div>
        </div>
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-slate-200 bg-slate-50 px-3 py-1.5 font-mono text-[10px] text-slate-500 dark:border-slate-800 dark:bg-slate-950/60">
        <span>{hint ?? "Dart · Tab thụt · Shift+Tab lùi · Ctrl+/ chú thích"}</span>
        <span>Dòng {caret.line}, cột {caret.column} · {lines.length} dòng · {value.length} ký tự</span>
      </div>
    </div>
  );
}

/** Danh sách giá trị đi kèm fieldKeys: mỗi ô nhập một dropdown, tránh gõ CSV lệch số phần tử. */
function PairedValueEditor({ fields, value, options, onChange }: {
  fields: string[];
  value: string;
  options: { value: string; label: string }[];
  onChange: (next: string) => void;
}) {
  const current = String(value ?? "").split(",").map((part) => part.trim());
  const fallback = options[0]?.value ?? "";
  const commit = (index: number, next: string) => {
    const merged = fields.map((_, i) => {
      const raw = i === index ? next : (current[i] ?? "");
      // Dấu phẩy là ký tự phân tách của CSV nên không cho lọt vào giá trị.
      return (raw.trim() ? raw : fallback).replace(/,/g, " ");
    });
    onChange(merged.join(","));
  };

  if (!fields.length) {
    return <p className="rounded-md bg-amber-50 px-2 py-1.5 text-[10px] text-amber-700">Hãy nhập &quot;Mã các ô nhập&quot; trước, mỗi ô sẽ có một dòng chọn giá trị.</p>;
  }
  return (
    <div className="space-y-1.5">
      {fields.map((field, index) => {
        const raw = current[index] ?? "";
        const known = options.some((option) => option.value === raw);
        return (
          <div key={`${field}-${index}`} className="rounded-md border border-slate-200 bg-white p-1.5">
            <p className="mb-1 truncate font-mono text-[10px] text-indigo-600" title={field}>{field}</p>
            <select
              value={known ? raw : "__custom__"}
              onChange={(e) => commit(index, e.target.value === "__custom__" ? (raw || " ") : e.target.value)}
              className="w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs"
            >
              {options.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
              <option value="__custom__">Tự nhập giá trị khác…</option>
            </select>
            {!known && (
              <input
                value={raw}
                onChange={(e) => commit(index, e.target.value)}
                placeholder="Giá trị tự nhập (không dùng dấu phẩy)"
                className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 font-mono text-[11px]"
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

function TestcasesEditor() {
  // ?exam=MÃ → mở bộ testcase đã lưu để SỬA; không có tham số = tạo bộ mới.
  const searchParams = useSearchParams();
  const editExamId = (searchParams.get("exam") || "").trim().toUpperCase();
  const isEdit = editExamId.length > 0;

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
  const [examIdCheck, setExamIdCheck] = useState<"idle" | "checking" | "available" | "exists" | "error">("idle");
  const [libraryTab, setLibraryTab] = useState<"templates" | "custom">("templates");
  const [skills, setSkills] = useState<SkillOption[]>([]);
  const [codeModalOpen, setCodeModalOpen] = useState(false);
  const [helpersOpen, setHelpersOpen] = useState(false);
  const [customDraft, setCustomDraft] = useState({
    name: "",
    skill_code: "",
    testcase_group: "LOGIC",
    difficulty: "intermediate",
    weight: 2,
    description: "",
    expected: "",
    code: CODE_SNIPPETS[0].code,
  });
  const [codeCheck, setCodeCheck] = useState<{ state: "idle" | "checking" | "ok" | "error"; message: string }>({
    state: "idle", message: "",
  });
  // ── Quản lý thư viện Khu vực 2: thêm / sửa / ẩn template ──
  const [runnerCatalog, setRunnerCatalog] = useState<RunnerCatalog | null>(null);
  const [showHiddenTemplates, setShowHiddenTemplates] = useState(false);
  const [templateEditor, setTemplateEditor] = useState<{ mode: "create" | "edit"; draft: TemplateDraft } | null>(null);
  const [templateError, setTemplateError] = useState("");
  const [templateBusy, setTemplateBusy] = useState(false);
  const [templateToHide, setTemplateToHide] = useState<Template | null>(null);
  // ── Khu vực 0: hợp đồng bài làm (cách nhận diện thành phần giao diện) ──
  const [contractCatalog, setContractCatalog] = useState<ContractCatalog | null>(null);
  const [contractOpen, setContractOpen] = useState(false);
  const [requireKeys, setRequireKeys] = useState(false);
  const [contractKeys, setContractKeys] = useState<ContractKey[]>([]);
  const [contractDoc, setContractDoc] = useState<{ requirements_text: string; starter_dart: string } | null>(null);
  const [contractBusy, setContractBusy] = useState(false);
  const [contractMode, setContractMode] = useState<"form" | "json">("form");
  const [contractJson, setContractJson] = useState("");
  const [contractJsonError, setContractJsonError] = useState("");
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewFiles, setPreviewFiles] = useState<GeneratedFile[]>([]);
  const [previewFile, setPreviewFile] = useState(0);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [previewNotice, setPreviewNotice] = useState("");

  // Nạp bộ testcase đang có khi vào chế độ sửa; hỏng/không có config thì báo ngay
  // thay vì để giáo viên sửa trên form rỗng rồi ghi đè mất bộ cũ.
  const [loadingExam, setLoadingExam] = useState(isEdit);
  const [missingConfig, setMissingConfig] = useState(false);
  useEffect(() => {
    if (!editExamId) return;
    setExamId(editExamId);
    setLoadingExam(true);
    fetch(`${API_BASE}/exam-setup/${encodeURIComponent(editExamId)}/testcases`)
      .then(async (r) => {
        const data = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(data.error || "Không đọc được bộ testcase này.");
        return data;
      })
      .then((data) => {
        // schema_version chỉ có ở bộ dựng từ template. Thiếu nó = bộ upload ZIP:
        // sửa ở đây sẽ bị backend chặn, nên chặn sớm và nói rõ lý do.
        if (data.schema_version == null) {
          setMissingConfig(true);
          setMessage({
            type: "error",
            text: `Bộ ${editExamId} không có cấu hình template để mở lại (thường là bộ tải lên bằng file ZIP). `
              + `Hãy tạo một bộ testcase mới nếu cần thay đổi.`,
          });
          return;
        }
        setMissingConfig(false);
        setItems(Array.isArray(data.items) ? data.items as TestcaseItem[] : []);
        setExamName(typeof data.exam_name === "string" ? data.exam_name : "");
        setTeacherNote(typeof data.teacher_note === "string" ? data.teacher_note : "");
        setStatus(typeof data.status === "string" ? data.status : "");
        setVersion(Number(data.version) || 0);
        const contract = (data.contract || {}) as { require_keys?: boolean; keys?: ContractKey[] };
        setRequireKeys(!!contract.require_keys);
        setContractKeys(Array.isArray(contract.keys) ? contract.keys : []);
      })
      .catch((e: unknown) => setMessage({
        type: "error",
        text: e instanceof Error ? e.message : "Không đọc được bộ testcase này.",
      }))
      .finally(() => setLoadingExam(false));
  }, [editExamId]);

  useEffect(() => {
    // Sửa bộ đã có thì mã trùng là chuyện đương nhiên → không chạy kiểm tra trùng mã.
    if (isEdit) {
      setExamIdCheck("idle");
      return;
    }
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
  }, [examId, isEdit]);

  // Xem trước từ chính trạng thái form, không yêu cầu Lưu Draft. Debounce để không gọi backend mỗi phím gõ.
  useEffect(() => {
    if (!previewOpen) return;
    const normalizedId = examId.trim();
    if (!/^[A-Z0-9_-]{1,60}$/.test(normalizedId)) {
      setPreviewLoading(false);
      setPreviewError("Hãy nhập mã bộ testcase hợp lệ để sinh code xem trước.");
      setPreviewFiles([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setPreviewLoading(true);
      setPreviewError("");
      setPreviewNotice("");
      try {
        const response = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(normalizedId)}/testcases/preview`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          signal: controller.signal,
          body: JSON.stringify({
            exam_name: examName.trim(),
            teacher_note: teacherNote.trim(),
            items,
            contract: { require_keys: requireKeys, keys: contractKeys },
          }),
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.error || "Không sinh được code xem trước.");
        const files = Array.isArray(data.files) ? data.files as GeneratedFile[] : [];
        setPreviewFiles(files);
        setPreviewNotice(typeof data.warning === "string" ? data.warning : "");
        setPreviewFile((current) => Math.min(current, Math.max(0, files.length - 1)));
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setPreviewNotice("");
        setPreviewError(error instanceof Error ? error.message : "Không sinh được code xem trước.");
      } finally {
        if (!controller.signal.aborted) setPreviewLoading(false);
      }
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [previewOpen, examId, examName, teacherNote, items, requireKeys, contractKeys]);

  useEffect(() => {
    fetch(`${API_BASE}/testcase-templates?includeHidden=${showHiddenTemplates}`)
      .then((r) => r.ok ? r.json() : [])
      .then((templateRows) => {
        setTemplates(Array.isArray(templateRows) ? templateRows as Template[] : []);
      })
      .catch(() => setMessage({ type: "error", text: "Không tải được thư viện testcase." }))
      .finally(() => setLoading(false));
  }, [showHiddenTemplates]);

  // Danh mục runner dựng form "Thêm testcase"; thiếu nó thì chỉ ẩn nút, không chặn cả trang.
  useEffect(() => {
    fetch(`${API_BASE}/testcase-templates/runners`)
      .then((r) => r.ok ? r.json() : null)
      .then((rows) => { if (rows && Array.isArray(rows.runners)) setRunnerCatalog(rows as RunnerCatalog); })
      .catch(() => { /* không có catalog thì vẫn dùng được thư viện có sẵn */ });
  }, []);

  useEffect(() => {
    fetch(`${API_BASE}/testcase-templates/contract-catalog`)
      .then((r) => r.ok ? r.json() : null)
      .then((rows) => { if (rows && Array.isArray(rows.strategies)) setContractCatalog(rows as ContractCatalog); })
      .catch(() => { /* không có hợp đồng thì engine dùng cách dò mặc định như trước */ });
  }, []);

  // Testcase code tay phải tự khai chủ đề năng lực; lấy đúng danh mục syllabus đang bật.
  useEffect(() => {
    fetch(`${API_BASE}/syllabus/skills`)
      .then((r) => r.ok ? r.json() : [])
      .then((rows) => {
        const list = Array.isArray(rows) ? rows as SkillOption[] : [];
        setSkills(list);
        setCustomDraft((draft) => draft.skill_code || !list.length
          ? draft
          : { ...draft, skill_code: list[0].code });
      })
      .catch(() => { /* thiếu syllabus chỉ làm dropdown rỗng, không chặn cả trang */ });
  }, []);

  const categories = useMemo(() => {
    const counts = new Map<string, number>(TESTCASE_GROUP_ORDER.map((code) => [code, 0]));
    counts.set("ALL", templates.length);
    templates.forEach((template) => {
      const code = testcaseGroup(template);
      counts.set(code, (counts.get(code) || 0) + 1);
    });
    return TESTCASE_GROUP_ORDER.map((code) => ({
      code,
      label: TESTCASE_GROUP_LABEL[code],
      count: counts.get(code) || 0,
    }));
  }, [templates]);

  const visibleTemplates = useMemo(() => templates.filter((t) => {
    const categoryMatch = selectedCategory === "ALL" || testcaseGroup(t) === selectedCategory;
    const query = search.trim().toLowerCase();
    const skillLabel = SKILL_LABEL[t.skill_code] || t.skill_name || t.skill_code;
    const searchMatch = !query || [t.name, t.description, t.skill_code, skillLabel, t.layer,
      ENGINE_LABEL[t.engine_type || ""] || ""]
      .some((value) => value.toLowerCase().includes(query));
    return categoryMatch && searchMatch;
  }), [templates, selectedCategory, search]);

  const selectedTemplate = templates.find((t) => t.template_id === selectedTemplateId) || null;
  const templateMap = useMemo(() => new Map(templates.map((t) => [t.template_id, t])), [templates]);
  // Testcase code tay không có template nên phải bỏ qua khi xét khả năng gộp nhóm,
  // nếu không một mục code tay đứng đầu sẽ làm mất luôn nút gộp của cả đề.
  const supportsGrouping = items.some((item) => templateMap.get(item.template_id)?.engine_type === "COMMON_V1");
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
    const usedIds = new Set(items.map((item) => item.instance_id));
    let nextNumber = items.length + 1;
    while (usedIds.has(`${examId.trim() || "exam"}_item_${pad(nextNumber)}`)) nextNumber += 1;
    const item: TestcaseItem = {
      instance_id: `${examId.trim() || "exam"}_item_${pad(nextNumber)}`,
      template_id: template.template_id,
      template_version: template.template_version,
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

  /** Thêm testcase gõ tay vào đề; backend sẽ bọc thành testWidgets đúng mã instance này. */
  const addCustomItem = () => {
    const name = customDraft.name.trim();
    if (!name) {
      setMessage({ type: "error", text: "Hãy đặt tên cho testcase tự viết." });
      return;
    }
    if (!customDraft.skill_code) {
      setMessage({ type: "error", text: "Hãy chọn chủ đề năng lực cho testcase tự viết." });
      return;
    }
    if (!customDraft.code.trim()) {
      setMessage({ type: "error", text: "Phần code testcase đang trống." });
      return;
    }
    const prefix = examId.trim() || "exam";
    const usedIds = new Set(items.map((item) => item.instance_id));
    let nextNumber = items.filter(isCustomItem).length + 1;
    while (usedIds.has(`${prefix}_custom_${pad(nextNumber)}`)) nextNumber += 1;
    const item: TestcaseItem = {
      instance_id: `${prefix}_custom_${pad(nextNumber)}`,
      template_id: CUSTOM_TEMPLATE_ID,
      template_version: "custom-v1",
      runner: CUSTOM_TEMPLATE_ID,
      skill_code: customDraft.skill_code,
      layer: "CUSTOM",
      testcase_group: customDraft.testcase_group,
      name,
      description: customDraft.description.trim() || "Testcase do giáo viên tự viết code.",
      difficulty: customDraft.difficulty,
      enabled: true,
      order: items.length + 1,
      weight: Number(customDraft.weight) || 0,
      parameters: {},
      expected: customDraft.expected.trim() || "Đoạn kiểm tra tự viết phải chạy qua toàn bộ assert.",
      expected_custom: true,
      custom_code: customDraft.code,
    };
    setItems((current) => [...current, item]);
    setEditingId(item.instance_id);
    setCodeModalOpen(false);
    setCodeCheck({ state: "idle", message: "" });
    // Giữ lại chủ đề/độ khó để soạn tiếp testcase cùng nhóm cho nhanh.
    setCustomDraft((draft) => ({ ...draft, name: "", description: "", expected: "" }));
    setMessage({ type: "ok", text: `Đã thêm testcase tự viết "${name}" vào bộ testcase (${item.instance_id}).` });
  };

  /** Nhờ backend parse thử bằng Dart trong ảnh nền — bắt lỗi cú pháp trước khi Lưu. */
  const checkCustomCode = async (code: string) => {
    setCodeCheck({ state: "checking", message: "" });
    try {
      const res = await fetch(`${API_BASE}/testcase-templates/custom-code/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ custom_code: code }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không kiểm tra được cú pháp");
      setCodeCheck({ state: data.ok ? "ok" : "error", message: String(data.message || "") });
    } catch (e) {
      setCodeCheck({ state: "error", message: e instanceof Error ? e.message : "Không kiểm tra được cú pháp" });
    }
  };

  // ── Thư viện Khu vực 2: thêm / sửa / ẩn testcase có sẵn ────────────────────
  const runnerDef = (runner: string) => runnerCatalog?.runners.find((row) => row.runner === runner) || null;

  const refreshTemplates = async () => {
    const res = await fetch(`${API_BASE}/testcase-templates?includeHidden=${showHiddenTemplates}`);
    const rows = res.ok ? await res.json() : [];
    setTemplates(Array.isArray(rows) ? rows as Template[] : []);
  };

  const openTemplateCreator = () => {
    const first = runnerCatalog?.runners[0];
    if (!first) return;
    setTemplateError("");
    setTemplateEditor({
      mode: "create",
      draft: {
        template_id: "",
        name: "",
        description: first.description,
        runner: first.runner,
        skill_code: skills[0]?.code || "",
        layer: first.layer_default,
        difficulty: "intermediate",
        weight_default: 1,
        testcase_group: "LOGIC",
        expected_template: "",
        parameters_schema: { ...first.parameters_schema },
      },
    });
  };

  const openTemplateEditor = (template: Template) => {
    setTemplateError("");
    setTemplateEditor({
      mode: "edit",
      draft: {
        template_id: template.template_id,
        name: template.name,
        description: template.description || "",
        runner: template.runner || "",
        skill_code: template.skill_code,
        layer: template.layer,
        difficulty: template.difficulty,
        weight_default: Number(template.weight_default) || 1,
        testcase_group: testcaseGroup(template),
        expected_template: template.expected_template || "",
        parameters_schema: { ...(template.parameters_schema || {}) },
      },
    });
  };

  /** Đổi runner thì bộ tham số cũng khác hẳn — nạp lại mặc định để không còn tham số thừa. */
  const changeDraftRunner = (runner: string) => {
    const def = runnerDef(runner);
    setTemplateEditor((current) => current && {
      ...current,
      draft: {
        ...current.draft,
        runner,
        layer: def?.layer_default || current.draft.layer,
        description: current.draft.description || def?.description || "",
        parameters_schema: { ...(def?.parameters_schema || {}) },
      },
    });
  };

  const updateDraftParameter = (name: string, value: unknown) => {
    setTemplateEditor((current) => {
      if (!current) return current;
      const params: JsonMap = { ...current.draft.parameters_schema, [name]: value };
      // Đổi danh sách key thì các tham số ghép cặp phải co giãn theo, nếu không backend
      // sẽ từ chối vì "phải cùng số phần tử".
      (runnerDef(current.draft.runner)?.parameters || []).forEach((param) => {
        if (param.type !== "values" || param.pair_with !== name) return;
        const count = splitCsv(value).length;
        params[param.name] = resizeCsv(String(params[param.name] ?? ""), count, () => "__EMPTY__");
      });
      return { ...current, draft: { ...current.draft, parameters_schema: params } };
    });
  };

  const saveTemplate = async () => {
    if (!templateEditor) return;
    const { mode, draft } = templateEditor;
    setTemplateBusy(true);
    setTemplateError("");
    try {
      const url = mode === "create"
        ? `${API_BASE}/testcase-templates`
        : `${API_BASE}/testcase-templates/${encodeURIComponent(draft.template_id)}`;
      const res = await fetch(url, {
        method: mode === "create" ? "POST" : "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(draft),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được testcase");
      await refreshTemplates();
      setTemplateEditor(null);
      setSelectedTemplateId(data.template_id || draft.template_id);
      setMessage({
        type: "ok",
        text: mode === "create"
          ? `Đã thêm testcase "${draft.name}" vào thư viện.`
          : `Đã cập nhật testcase "${draft.name}".`,
      });
    } catch (e) {
      setTemplateError(e instanceof Error ? e.message : "Không lưu được testcase");
    } finally {
      setTemplateBusy(false);
    }
  };

  const hideTemplate = async (template: Template) => {
    setTemplateBusy(true);
    try {
      const res = await fetch(`${API_BASE}/testcase-templates/${encodeURIComponent(template.template_id)}`,
        { method: "DELETE" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không ẩn được testcase");
      await refreshTemplates();
      setTemplateToHide(null);
      if (selectedTemplateId === template.template_id) setSelectedTemplateId(null);
      const used = Number(data.usage) || 0;
      setMessage({
        type: "ok",
        text: `Đã ẩn "${template.name}" khỏi thư viện.`
          + (used > 0 ? ` ${used} bộ testcase đang dùng vẫn chấm bình thường.` : ""),
      });
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không ẩn được testcase" });
    } finally {
      setTemplateBusy(false);
    }
  };

  const restoreTemplate = async (template: Template) => {
    setTemplateBusy(true);
    try {
      const res = await fetch(
        `${API_BASE}/testcase-templates/${encodeURIComponent(template.template_id)}/restore`,
        { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không khôi phục được testcase");
      await refreshTemplates();
      setMessage({ type: "ok", text: `Đã khôi phục "${template.name}".` });
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không khôi phục được testcase" });
    } finally {
      setTemplateBusy(false);
    }
  };

  // ── Khu vực 0: hợp đồng bài làm ───────────────────────────────────────────
  const contractStrategy = (code: string) =>
    contractCatalog?.strategies.find((row) => row.code === code) || null;

  /**
   * Key đã khai ở Khu vực 0 chính là những key testcase nên dùng — đưa lên đầu mọi chỗ
   * chọn key để giáo viên không phải nhớ và gõ lại.
   */
  const declaredKeys = useMemo(
    () => contractKeys
      .filter((row) => row.key.trim())
      .map((row) => ({ key: row.key.trim(), label: row.label.trim() || row.key.trim() })),
    [contractKeys],
  );

  /** Gợi ý cho datalist: key của đề trước, rồi tới bộ key quy ước chung. */
  const keySuggestions = useMemo(() => {
    const seen = new Set(declaredKeys.map((row) => row.key));
    return [...declaredKeys, ...SEMANTIC_KEYS.filter((key) => !seen.has(key)).map((key) => ({ key, label: "" }))];
  }, [declaredKeys]);

  /** Thêm nốt các thành phần chưa khai, giữ nguyên những dòng giáo viên đã chỉnh. */
  const loadDefaultContract = () => {
    if (!contractCatalog) return;
    setContractKeys((current) => [
      ...current,
      ...contractCatalog.default_keys
        .filter((row) => !current.some((existing) => existing.key === row.key))
        .map((row) => ({ ...row })),
    ]);
    setContractOpen(true);
  };

  /** Thêm một thành phần có sẵn (đã điền sẵn cách dò) hoặc một dòng trống để tự khai. */
  const addContractKey = (templateKey?: string) => {
    const preset = contractCatalog?.default_keys.find((row) => row.key === templateKey);
    setContractKeys((current) => [...current, preset
      ? { ...preset }
      : { key: "", label: "", required: false, strategy: "auto", value: "", text: "", index: 0 }]);
    setContractOpen(true);
  };

  /** Đổi qua lại giữa bảng chọn và ô gõ JSON; hai chiều luôn cùng một dữ liệu. */
  const switchContractMode = (mode: "form" | "json") => {
    setContractJsonError("");
    if (mode === "json") {
      setContractJson(JSON.stringify({ require_keys: requireKeys, keys: contractKeys }, null, 2));
    }
    setContractMode(mode);
  };

  /** Chèn một mẫu vào mảng keys; giữ nguyên phần còn lại của config đang gõ. */
  const insertContractSnippet = (snippetId: string) => {
    const snippet = CONTRACT_SNIPPETS.find((item) => item.id === snippetId);
    if (!snippet) return;
    try {
      const parsed = contractJson.trim() ? JSON.parse(contractJson) : { require_keys: requireKeys, keys: [] };
      const keys = Array.isArray(parsed.keys) ? parsed.keys : [];
      setContractJson(JSON.stringify({ ...parsed, keys: [...keys, snippet.row] }, null, 2));
      setContractJsonError("");
    } catch {
      setContractJsonError("Config hiện tại chưa đúng JSON nên không chèn được mẫu. Sửa lỗi rồi thử lại.");
    }
  };

  /** Soi lỗi ngay khi gõ để không phải bấm "Áp dụng" mới biết sai. */
  const contractJsonStatus = useMemo(() => {
    if (!contractJson.trim()) return { ok: false, message: "Chưa có nội dung" };
    try {
      const parsed = JSON.parse(contractJson);
      if (!parsed || !Array.isArray(parsed.keys)) return { ok: false, message: "Thiếu mảng \"keys\"" };
      return { ok: true, message: `JSON hợp lệ · ${parsed.keys.length} thành phần` };
    } catch (e) {
      return { ok: false, message: e instanceof Error ? e.message : "JSON không hợp lệ" };
    }
  }, [contractJson]);

  const applyContractJson = () => {
    try {
      const parsed = JSON.parse(contractJson);
      if (!parsed || !Array.isArray(parsed.keys)) throw new Error("Thiếu mảng \"keys\"");
      setRequireKeys(parsed.require_keys === true);
      setContractKeys(parsed.keys.map((row: Partial<ContractKey>) => ({
        key: String(row.key ?? "").trim(),
        label: String(row.label ?? "").trim(),
        required: row.required === true,
        strategy: String(row.strategy ?? "auto"),
        value: row.value === undefined ? "" : String(row.value),
        text: row.text === undefined ? "" : String(row.text),
        index: Number(row.index ?? 0) || 0,
      })));
      setContractJsonError("");
      setContractMode("form");
      setMessage({ type: "ok", text: "Đã áp dụng config vào bảng cấu hình bài làm." });
    } catch (e) {
      setContractJsonError(e instanceof Error ? e.message : "JSON không hợp lệ");
    }
  };

  const updateContractKey = (index: number, patch: Partial<ContractKey>) => {
    setContractKeys((current) => current.map((row, i) => i === index ? { ...row, ...patch } : row));
  };

  const removeContractKey = (index: number) => {
    setContractKeys((current) => current.filter((_, i) => i !== index));
  };

  /** Sinh sẵn hai thứ giáo viên cần phát ra: yêu cầu dán vào đề và code cho sinh viên. */
  const previewContract = async () => {
    setContractBusy(true);
    try {
      const res = await fetch(`${API_BASE}/testcase-templates/contract/preview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ contract: { require_keys: requireKeys, keys: contractKeys } }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không dựng được hợp đồng");
      setContractDoc({
        requirements_text: String(data.requirements_text || ""),
        starter_dart: String(data.starter_dart || ""),
      });
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không dựng được hợp đồng" });
    } finally {
      setContractBusy(false);
    }
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
    // Đổi danh sách ô nhập thì các tham số đi kèm phải co giãn theo, nếu không backend
    // sẽ chặn vì "fieldKeys, invalidValues và errorKeys phải cùng số phần tử".
    if (key === "fieldKeys") {
      const fields = splitCsv(parsed);
      Object.entries(PAIRED_VALUE_PARAMS).forEach(([pairedKey, config]) => {
        if (!(pairedKey in parameters)) return;
        parameters[pairedKey] = resizeCsv(String(parameters[pairedKey] ?? ""), fields.length,
          () => config.options[0]?.value ?? "");
      });
      if ("errorKeys" in parameters) {
        parameters.errorKeys = resizeCsv(String(parameters.errorKeys ?? ""), fields.length,
          (index) => `error.${(fields[index] ?? "field").split(".").pop()}`);
      }
    }
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
    setMessage({ type: "ok", text: "Đã xóa toàn bộ testcase khỏi bộ hiện tại." });
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
      setMessage({ type: "error", text: "Vui lòng nhập mã bộ testcase mới trước khi lưu." });
      return;
    }
    if (!isEdit && examIdCheck !== "available") {
      setMessage({ type: "error", text: examIdCheck === "exists"
        ? "Mã bộ testcase đã tồn tại. Vui lòng nhập một mã bộ testcase mới."
        : "Vui lòng chờ kiểm tra mã bộ testcase hoàn tất." });
      return;
    }
    if (!examName.trim()) {
      setMessage({ type: "error", text: "Vui lòng nhập tên bộ testcase trước khi lưu." });
      return;
    }
    setSaving(kind);
    setMessage(null);
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/testcases/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          exam_name: examName.trim(),
          teacher_note: teacherNote.trim(),
          items,
          contract: { require_keys: requireKeys, keys: contractKeys },
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Không lưu được cấu hình testcase");
      setStatus(data.status || (kind === "publish" ? "PUBLISHED" : "DRAFT"));
      setVersion(Number(data.version ?? version));
      setItems(Array.isArray(data.items) ? data.items as TestcaseItem[] : items);
      setMessage({ type: "ok", text: data.warning || (kind === "publish"
        ? `Đã lưu bộ code testcase v${data.version}. Hãy Build Sandbox tại Kho bộ testcase trước khi chấm.`
        : `Đã lưu nháp bộ code testcase v${data.version} (chưa dùng để chấm).` ) });
    } catch (e) {
      setMessage({ type: "error", text: e instanceof Error ? e.message : "Không lưu được cấu hình testcase" });
    } finally {
      setSaving(null);
    }
  };

  const downloadTestcase = async () => {
    if (!examId.trim()) return;
    try {
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId.trim())}/download/exam-test`);
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

  const insertSnippet = (snippetId: string) => {
    const snippet = CODE_SNIPPETS.find((item) => item.id === snippetId);
    if (!snippet) return;
    // Không xóa code đang gõ dở: có nội dung thì chèn nối tiếp phía dưới.
    setCustomDraft((draft) => ({
      ...draft,
      code: draft.code.trim() ? `${draft.code.replace(/\s+$/, "")}\n\n${snippet.code}` : snippet.code,
    }));
    setCodeCheck({ state: "idle", message: "" });
  };

  /**
   * Khung soạn testcase code tay. Là hàm trả JSX (không phải component con) để textarea
   * không bị remount mỗi lần gõ — dùng chung cho khu vực 2 và cửa sổ phóng to.
   */
  const renderCodeComposer = (rows: number, expandable = true) => (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2">
        <label className="col-span-2 text-xs font-semibold text-slate-600">
          Tên testcase
          <input
            value={customDraft.name}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, name: e.target.value }))}
            placeholder="VD: Tổng tiền giỏ hàng cập nhật đúng sau khi xóa món"
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs font-normal outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
          />
        </label>
        <label className="col-span-2 text-xs font-semibold text-slate-600">
          Chủ đề năng lực
          <select
            value={customDraft.skill_code}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, skill_code: e.target.value }))}
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
          >
            {!skills.length && <option value="">(chưa tải được syllabus)</option>}
            {skills.map((skill) => (
              <option key={skill.code} value={skill.code}>{SKILL_LABEL[skill.code] || skill.name || skill.code}</option>
            ))}
          </select>
        </label>
        <label className="text-xs font-semibold text-slate-600">
          Nhóm testcase
          <select
            value={customDraft.testcase_group}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, testcase_group: e.target.value }))}
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
          >
            {TESTCASE_GROUP_ORDER.filter((code) => code !== "ALL").map((code) => (
              <option key={code} value={code}>{TESTCASE_GROUP_LABEL[code]}</option>
            ))}
          </select>
        </label>
        <label className="text-xs font-semibold text-slate-600">
          Độ khó
          <select
            value={customDraft.difficulty}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, difficulty: e.target.value }))}
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
          >
            <option value="basic">Cơ bản</option>
            <option value="intermediate">Trung bình</option>
            <option value="advanced">Nâng cao</option>
          </select>
        </label>
        <label className="text-xs font-semibold text-slate-600">
          Điểm
          <input
            type="number" min="0" step="0.5"
            value={customDraft.weight}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, weight: Number(e.target.value) }))}
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
          />
        </label>
        <label className="text-xs font-semibold text-slate-600">
          Expected khi pass
          <input
            value={customDraft.expected}
            onChange={(e) => setCustomDraft((draft) => ({ ...draft, expected: e.target.value }))}
            placeholder="Mô tả kết quả đúng"
            className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
          />
        </label>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <select
          value=""
          onChange={(e) => insertSnippet(e.target.value)}
          className="min-w-0 flex-1 rounded-md border border-indigo-200 bg-indigo-50 px-2 py-1.5 text-xs font-semibold text-indigo-700"
        >
          <option value="">＋ Chèn mẫu cấu trúc code…</option>
          {CODE_SNIPPETS.map((snippet) => (
            <option key={snippet.id} value={snippet.id}>{snippet.label}</option>
          ))}
        </select>
        <button
          onClick={() => setHelpersOpen((open) => !open)}
          className="flex items-center gap-1 rounded-md border border-slate-200 px-2 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50"
        >
          <Lightbulb size={13} /> Cú pháp
        </button>
        {expandable && (
          <button
            onClick={() => setCodeModalOpen(true)}
            className="rounded-md border border-slate-200 px-2 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50"
          >
            Phóng to
          </button>
        )}
      </div>

      {helpersOpen && (
        <div className="space-y-2 rounded-xl border border-slate-200 bg-slate-50 p-3">
          <div className="flex items-start justify-between gap-2">
            <p className="text-[11px] font-bold text-slate-700">Hàm dùng được trong thân test</p>
            <button
              onClick={() => setHelpersOpen(false)}
              className="-mr-1 -mt-1 rounded-md p-1 text-slate-400 hover:bg-white hover:text-slate-600"
              aria-label="Đóng bảng cú pháp, quay lại khung viết code"
              title="Đóng, quay lại khung viết code"
            >
              <X size={14} />
            </button>
          </div>
          <div className="space-y-1">
            {CODE_HELPERS.map((helper) => (
              <div key={helper.code} className="flex flex-wrap items-baseline gap-x-2">
                <code className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-indigo-700">{helper.code}</code>
                <span className="text-[10px] text-slate-500">{helper.desc}</span>
              </div>
            ))}
          </div>
          <p className="pt-1 text-[11px] font-bold text-slate-700">Semantic key theo quy ước của bộ testcase</p>
          <div className="flex flex-wrap gap-1">
            {SEMANTIC_KEYS.map((key) => (
              <code key={key} className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-slate-500">{key}</code>
            ))}
          </div>
          <p className="text-[10px] leading-relaxed text-slate-500">
            Đã import sẵn: <code className="font-mono">material.dart</code>, <code className="font-mono">flutter_test.dart</code>,
            <code className="font-mono"> rendering.dart</code>, <code className="font-mono">dart:io</code>. Không viết
            <code className="font-mono"> import</code>, <code className="font-mono">main()</code>, <code className="font-mono">group()</code> hay
            <code className="font-mono"> testWidgets()</code> trong ô code.
          </p>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-300 focus-within:border-indigo-400 focus-within:ring-2 focus-within:ring-indigo-100 dark:border-slate-700 dark:focus-within:ring-indigo-500/30">
        <p className="border-b border-slate-200 bg-slate-100 px-3 py-1.5 font-mono text-[11px] text-slate-600 dark:border-slate-800 dark:bg-slate-800 dark:text-slate-300">
          testWidgets(&apos;{examId.trim() || "exam"}_custom_{pad(items.filter(isCustomItem).length + 1)}&apos;, (tester) async {"{"}
        </p>
        <CodeEditor
          bare
          value={customDraft.code}
          onChange={(code) => { setCustomDraft((draft) => ({ ...draft, code })); setCodeCheck({ state: "idle", message: "" }); }}
          rows={rows}
        />
        <p className="border-t border-slate-200 bg-slate-100 px-3 py-1.5 font-mono text-[11px] text-slate-600 dark:border-slate-800 dark:bg-slate-800 dark:text-slate-300">{"});"}</p>
      </div>

      {codeCheck.state !== "idle" && (
        <div className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-[11px] leading-relaxed ${codeCheck.state === "ok" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : codeCheck.state === "error" ? "border-rose-200 bg-rose-50 text-rose-700" : "border-slate-200 bg-slate-50 text-slate-500"}`}>
          {codeCheck.state === "checking"
            ? <><Loader2 size={13} className="mt-0.5 shrink-0 animate-spin" /> Đang kiểm tra cú pháp…</>
            : <>
                {codeCheck.state === "ok" ? <CheckCircle2 size={13} className="mt-0.5 shrink-0" /> : <AlertCircle size={13} className="mt-0.5 shrink-0" />}
                <span className="whitespace-pre-wrap break-words">{codeCheck.message}</span>
              </>}
        </div>
      )}

      <div className="flex gap-2">
        <button
          onClick={() => checkCustomCode(customDraft.code)}
          disabled={codeCheck.state === "checking"}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        >
          <Code2 size={14} /> Kiểm tra cú pháp
        </button>
        <button
          onClick={addCustomItem}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-700"
        >
          <Plus size={14} /> Thêm vào bộ testcase
        </button>
      </div>
    </div>
  );

  /**
   * Ô chọn một semantic key. Có khai ở Khu vực 0 thì cho chọn thẳng từ danh sách đó,
   * kèm lối thoát "tự nhập" cho key ngoài hợp đồng.
   */
  const renderKeyField = (current: string, onPick: (next: string) => void, cellClass: string) => {
    const value = String(current ?? "");
    if (!declaredKeys.length) {
      return (
        <input value={value} list="semantic-key-options" onChange={(e) => onPick(e.target.value)}
          className={`${cellClass} font-mono`} placeholder="vd: field.email" />
      );
    }
    const known = declaredKeys.some((row) => row.key === value);
    return (
      <>
        <select
          value={known ? value : "__custom__"}
          onChange={(e) => onPick(e.target.value === "__custom__" ? "" : e.target.value)}
          className={cellClass}
        >
          {!value && <option value="">(chưa chọn)</option>}
          {declaredKeys.map((row) => (
            <option key={row.key} value={row.key}>{row.label} — {row.key}</option>
          ))}
          <option value="__custom__">Tự nhập key khác…</option>
        </select>
        {!known && (
          <input value={value} list="semantic-key-options" onChange={(e) => onPick(e.target.value)}
            className={`${cellClass} mt-1 font-mono`} placeholder="vd: field.phone" />
        )}
      </>
    );
  };

  /** Một ô nhập tham số của runner, dựng theo `type` mà backend khai trong danh mục. */
  const renderTemplateParam = (param: RunnerParam, draft: TemplateDraft) => {
    const value = draft.parameters_schema[param.name];
    const label = (
      <span className="flex items-baseline gap-1">
        {param.label}
        {param.required && <span className="text-rose-500">*</span>}
        <code className="font-mono text-[10px] font-normal text-slate-400">{param.name}</code>
      </span>
    );
    const wrap = (control: React.ReactNode, wide = false) => (
      <label key={param.name} className={`text-xs font-semibold text-slate-600 ${wide ? "col-span-2" : ""}`}>
        {label}
        {control}
        {param.hint && <p className="mt-1 text-[10px] font-normal leading-relaxed text-slate-400">{param.hint}</p>}
      </label>
    );
    const inputClass = "mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";

    if (param.type === "enum") {
      return wrap(
        <select value={String(value ?? "")} onChange={(e) => updateDraftParameter(param.name, e.target.value)} className={inputClass}>
          {!param.required && <option value="">(không kiểm tra)</option>}
          {(param.options || []).map((option) => (
            <option key={option} value={option}>{PARAMETER_OPTION_LABELS[option] || option}</option>
          ))}
        </select>);
    }
    if (param.type === "bool") {
      return wrap(
        <select value={String(value) === "true" ? "true" : "false"} onChange={(e) => updateDraftParameter(param.name, e.target.value === "true")} className={inputClass}>
          <option value="true">Có / bật</option>
          <option value="false">Không / tắt</option>
        </select>);
    }
    if (param.type === "number") {
      return wrap(
        <input type="number" min={param.min ?? 0} step="0.5" value={Number(value ?? 0)}
          onChange={(e) => updateDraftParameter(param.name, Number(e.target.value))} className={inputClass} />);
    }
    if (param.type === "values") {
      return wrap(
        <div className="mt-1">
          <PairedValueEditor
            fields={splitCsv(draft.parameters_schema[param.pair_with || ""])}
            value={String(value ?? "")}
            options={param.name === "invalidValues" ? INVALID_VALUE_OPTIONS : VALID_VALUE_OPTIONS}
            onChange={(next) => updateDraftParameter(param.name, next)}
          />
        </div>, true);
    }
    if (param.type === "semantic_key") {
      return wrap(renderKeyField(String(value ?? ""), (next) => updateDraftParameter(param.name, next), inputClass));
    }
    const isKeyList = param.type === "semantic_keys";
    return wrap(
      <input
        value={String(value ?? "")}
        list={isKeyList ? "semantic-key-options" : undefined}
        placeholder={isKeyList ? "key1,key2" : ""}
        onChange={(e) => updateDraftParameter(param.name, e.target.value)}
        className={`${inputClass} ${isKeyList ? "font-mono" : ""}`}
      />, isKeyList);
  };

  const templateDef = templateEditor ? runnerDef(templateEditor.draft.runner) : null;

  /** Một dòng khai báo cách nhận diện của Khu vực 0. */
  const renderContractRow = (row: ContractKey, index: number) => {
    const strategy = contractStrategy(row.strategy);
    const known = (contractCatalog?.default_keys || []).some((option) => option.key === row.key);
    const cell = "w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";
    // widget_type/type_with_text nhận TÊN CLASS widget, không phải semantic key.
    const isTypeStrategy = row.strategy === "widget_type" || row.strategy === "type_with_text";
    const widgetTypes = contractCatalog?.common_widget_types || [];
    const typeSelectValue = !row.value ? "" : widgetTypes.includes(row.value) ? row.value : "__custom__";
    return (
      <div key={index} className="grid grid-cols-12 items-start gap-2 rounded-lg border border-slate-200 bg-white p-2">
        <div className="col-span-3">
          <select
            value={known ? row.key : "__custom__"}
            onChange={(e) => {
              if (e.target.value === "__custom__") { updateContractKey(index, { key: "" }); return; }
              const preset = contractCatalog?.default_keys.find((option) => option.key === e.target.value);
              // Chọn thành phần khác thì lấy luôn cách dò mặc định của nó, khỏi khai lại từ đầu.
              updateContractKey(index, preset ? { ...preset } : { key: e.target.value });
            }}
            className={cell}
          >
            {(contractCatalog?.default_keys || []).map((option) => (
              <option key={option.key} value={option.key}>{option.label}</option>
            ))}
            <option value="__custom__">Thành phần khác (tự nhập)…</option>
          </select>
          {!known && (
            <input
              value={row.key}
              list="semantic-key-options"
              onChange={(e) => updateContractKey(index, { key: e.target.value.trim() })}
              placeholder="vd: field.phone"
              className={`${cell} mt-1 font-mono`}
            />
          )}
          <input
            value={row.label}
            onChange={(e) => updateContractKey(index, { label: e.target.value })}
            placeholder="Mô tả cho sinh viên"
            className={`${cell} mt-1`}
          />
          <p className="mt-1 truncate px-1 font-mono text-[10px] text-slate-400" title={row.key}>{row.key || "(chưa có key)"}</p>
        </div>
        <div className="col-span-3">
          <select
            value={row.strategy}
            onChange={(e) => updateContractKey(index, { strategy: e.target.value })}
            className={cell}
          >
            {(contractCatalog?.strategies || []).map((option) => (
              <option key={option.code} value={option.code}>{option.label}</option>
            ))}
          </select>
        </div>
        <div className="col-span-4">
          {strategy?.needs_value && (
            row.strategy === "icon" ? (
              <select
                value={row.value || ""}
                onChange={(e) => updateContractKey(index, { value: e.target.value })}
                className={cell}
              >
                <option value="">(chọn nhóm icon)</option>
                {(contractCatalog?.icon_groups || []).map((name) => (
                  <option key={name} value={name}>{ICON_GROUP_LABEL[name] || name}</option>
                ))}
              </select>
            ) : isTypeStrategy ? (
              // Datalist tự lọc theo chữ đang có trong ô, nên preset "ListView" che mất
              // 26 loại còn lại. Dùng select để giáo viên thấy đủ danh sách.
              <>
                <select
                  value={typeSelectValue}
                  onChange={(e) => updateContractKey(index, { value: e.target.value === "__custom__" ? "" : e.target.value })}
                  className={cell}
                >
                  <option value="">(chọn loại widget)</option>
                  {widgetTypes.map((name) => (
                    <option key={name} value={name}>{name}</option>
                  ))}
                  <option value="__custom__">Loại khác (tự nhập)…</option>
                </select>
                {typeSelectValue === "__custom__" && (
                  <input
                    value={row.value || ""}
                    onChange={(e) => updateContractKey(index, { value: e.target.value })}
                    placeholder="Tên class widget — vd SliverAnimatedGrid"
                    className={`${cell} mt-1 font-mono`}
                  />
                )}
              </>
            ) : (
              <input
                value={row.value || ""}
                onChange={(e) => updateContractKey(index, { value: e.target.value })}
                placeholder="Chữ hiển thị — bọc /…/ để dùng regex"
                className={`${cell} font-mono`}
              />
            )
          )}
          {strategy?.needs_text && (
            <input
              value={row.text || ""}
              onChange={(e) => updateContractKey(index, { text: e.target.value })}
              placeholder="Chữ nằm bên trong widget đó"
              className={`${cell} mt-1 font-mono`}
            />
          )}
          {!strategy?.needs_value && !strategy?.needs_text && (
            <p className="px-1 py-1.5 text-[10px] leading-relaxed text-slate-400">
              {row.strategy === "key_only"
                ? "Bài không gắn ValueKey là không tìm thấy."
                : "Dùng quy tắc nhận diện mặc định của hệ thống."}
            </p>
          )}
        </div>
        <div className="col-span-1">
          {strategy?.uses_index && (
            <input
              type="number" min="0"
              value={row.index}
              title="Lấy phần tử thứ mấy (0 = đầu tiên)"
              onChange={(e) => updateContractKey(index, { index: Math.max(0, Number(e.target.value)) })}
              className={cell}
            />
          )}
        </div>
        <div className="col-span-1 flex items-center justify-end gap-1">
          <label className="flex cursor-pointer items-center gap-1 text-[10px] font-semibold text-slate-500" title="Bắt buộc có trong bài làm">
            <input
              type="checkbox" checked={row.required}
              onChange={(e) => updateContractKey(index, { required: e.target.checked })}
              className="h-3.5 w-3.5 accent-indigo-600"
            />
          </label>
          <button onClick={() => removeContractKey(index)} className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600" title="Xóa dòng">
            <Trash2 size={13} />
          </button>
        </div>
      </div>
    );
  };

  // Sửa: chỉ chờ nạp xong; Tạo mới: phải chắc chắn mã chưa tồn tại mới cho lưu.
  const saveDisabled = !!saving || !examName.trim() || loadingExam || missingConfig
    || (!isEdit && examIdCheck !== "available");

  return (
    <SidebarLayout
      title={isEdit ? "Sửa bộ testcase" : "Tạo testcase từ template"}
      subtitle={isEdit
        ? `Đang sửa ${editExamId} — bấm Lưu để cập nhật bộ đang dùng để chấm`
        : "Kéo-thả testcase chung theo semantic key → dùng lại cho nhiều bộ testcase Flutter"}
      activePath="/teacher/archive"
      contentClassName="max-w-[1600px]"
    >
      <div className="space-y-5">
        {/* Cả tạo lẫn sửa đều mở từ trang Kho → luôn có đường quay lại. */}
        <Link href="/teacher/archive" className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-500 hover:text-indigo-600">
          <ChevronLeft size={14} /> Kho bộ testcase
        </Link>
        <div className="card flex flex-wrap items-end gap-4 p-4">
          <div className="min-w-[220px] flex-1">
            <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">
              {isEdit ? "Mã bộ testcase" : "Mã bộ testcase mới"}
            </label>
            <input
              value={examId}
              onChange={(e) => setExamId(e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, ""))}
              readOnly={isEdit}
              placeholder="VD: FLUTTER_PE_30 — chưa tồn tại"
              className={`w-full rounded-lg border border-slate-200 px-3 py-2.5 font-mono text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 ${isEdit ? "cursor-not-allowed bg-slate-100 text-slate-500" : "bg-white"}`}
            />
            {isEdit && <p className="mt-1.5 text-[11px] text-slate-400">Không đổi được mã khi sửa — tạo bộ mới nếu cần mã khác.</p>}
            {!isEdit && examIdCheck === "checking" && <p className="mt-1.5 text-[11px] text-slate-400">Đang kiểm tra mã bộ testcase…</p>}
            {!isEdit && examIdCheck === "available" && <p className="mt-1.5 text-[11px] font-semibold text-emerald-600">Mã bộ testcase chưa tồn tại, có thể tạo.</p>}
            {!isEdit && examIdCheck === "exists" && <p className="mt-1.5 text-[11px] font-semibold text-rose-600">Mã bộ testcase đã tồn tại, hãy chọn mã khác.</p>}
            {!isEdit && examIdCheck === "error" && <p className="mt-1.5 text-[11px] font-semibold text-amber-600">Không kiểm tra được mã bộ testcase. Vui lòng thử lại.</p>}
          </div>
          <div className="min-w-[260px] flex-[1.4]">
            <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">Tên bộ testcase</label>
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
            {loadingExam ? <span className="flex items-center gap-1.5 text-slate-400"><Loader2 size={13} className="animate-spin" /> Đang nạp bộ testcase…</span>
              : version > 0 ? <>
                <span className={`rounded-full px-2.5 py-1 font-bold ${status === "PUBLISHED" ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"}`}>
                  {status === "PUBLISHED" ? "ĐÃ LƯU" : "BẢN NHÁP"}
                </span>
                <span>version {version}</span>
              </> : <span className="text-slate-400">Chưa lưu</span>}
          </div>
          <div className="ml-auto flex gap-2">
            <button onClick={() => { setPreviewOpen(true); setPreviewError(""); }} disabled={!examId.trim() || !!saving} className="flex items-center gap-2 rounded-lg border border-violet-200 bg-violet-50 px-3.5 py-2.5 text-sm font-semibold text-violet-700 hover:bg-violet-100 disabled:cursor-not-allowed disabled:opacity-50" title="Xem exam_test.dart và matrix đang sinh từ form hiện tại, không cần lưu trước">
              <Eye size={16} /> Xem code hiện tại
            </button>
            <button onClick={downloadTestcase} disabled={!examId.trim() || !items.length || !!saving} className="flex items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 px-3.5 py-2.5 text-sm font-semibold text-indigo-700 hover:bg-indigo-100 disabled:cursor-not-allowed disabled:opacity-50">
              <Download size={16} /> Tải ZIP code
            </button>
            <button onClick={() => save("draft")} disabled={saveDisabled} title="Lưu tạm để sửa tiếp — chưa dùng để chấm" className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "draft" ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} Lưu nháp
            </button>
            <button onClick={() => save("publish")} disabled={saveDisabled} title="Lưu chính thức — bộ testcase này sẽ được dùng để chấm" className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50">
              {saving === "publish" ? <Loader2 size={16} className="animate-spin" /> : <UploadCloud size={16} />} Lưu
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

        {/* Khu vực 0: hợp đồng bài làm — quyết định testcase nhận diện widget thế nào */}
        <section className="card overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="min-w-0">
                <p className="eyebrow">Khu vực 0</p>
                <h2 className="mt-1 text-sm font-bold text-slate-800">Cấu hình bài làm</h2>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <span className={`rounded-full px-2.5 py-1 text-xs font-bold ${contractKeys.length ? "bg-indigo-100 text-indigo-700" : "bg-slate-100 text-slate-500"}`}>
                  {contractKeys.length ? `${contractKeys.length} key` : "Chưa cấu hình"}
                </span>
                <button onClick={() => setContractOpen((open) => !open)} disabled={!contractCatalog} className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-40">
                  <Settings2 size={13} /> {contractOpen ? "Thu gọn" : "Cấu hình"}
                </button>
                <button onClick={previewContract} disabled={contractBusy || !contractKeys.length} className="flex items-center gap-1.5 rounded-lg border border-indigo-200 bg-indigo-50 px-2.5 py-1.5 text-xs font-semibold text-indigo-700 hover:bg-indigo-100 disabled:opacity-40">
                  {contractBusy ? <Loader2 size={13} className="animate-spin" /> : <Eye size={13} />} Xem hợp đồng
                </button>
              </div>
            </div>
            {!contractCatalog && (
              <div className="flex items-start gap-2 border-b border-amber-100 bg-amber-50 px-4 py-3 text-xs leading-relaxed text-amber-800">
                <AlertCircle size={15} className="mt-0.5 shrink-0" />
                <span>
                  Backend đang chạy chưa có API <code className="font-mono">/api/testcase-templates/contract-catalog</code>.
                  Hãy <strong>khởi động lại backend</strong> (<code className="font-mono">run</code> hoặc <code className="font-mono">mvnw spring-boot:run</code>) để dùng Khu vực 0.
                  Bộ testcase lưu lúc này vẫn chấm bình thường bằng cách dò mặc định.
                </span>
              </div>
            )}
            {contractOpen && contractCatalog && (
              <div className="space-y-3 p-4">
                <div className="flex gap-1 rounded-lg bg-slate-100 p-1">
                  <button
                    onClick={() => switchContractMode("form")}
                    className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-semibold transition-colors ${contractMode === "form" ? "bg-white text-indigo-700 shadow-sm" : "text-slate-500 hover:text-slate-700"}`}
                  >
                    <Settings2 size={13} /> Chọn thuộc tính
                  </button>
                  <button
                    onClick={() => switchContractMode("json")}
                    className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-semibold transition-colors ${contractMode === "json" ? "bg-white text-indigo-700 shadow-sm" : "text-slate-500 hover:text-slate-700"}`}
                  >
                    <Code2 size={13} /> Gõ config
                  </button>
                </div>

                <label className="flex cursor-pointer items-start gap-2 text-xs text-slate-600">
                  <input type="checkbox" checked={requireKeys} onChange={(e) => setRequireKeys(e.target.checked)} className="mt-0.5 h-4 w-4 accent-indigo-600" />
                  <span>
                    <strong className="text-slate-700">Bắt buộc sinh viên gắn ValueKey.</strong>{" "}
                    ValueKey là tham số <code className="font-mono">key:</code> đặt trên chính widget
                    (<code className="font-mono">TextFormField(key: const ValueKey(&apos;field.email&apos;))</code>) —
                    không phải tên class. Chấm chính xác nhất, nhưng phải công bố quy ước key trong đề bài.
                    Bật ô này là bỏ toàn bộ cách nhận diện thay thế: thiếu key thì phần đó không được tính điểm.
                  </span>
                </label>

                {contractMode === "json" ? (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <select
                        value=""
                        onChange={(e) => insertContractSnippet(e.target.value)}
                        className="min-w-0 flex-1 rounded-lg border border-indigo-200 bg-indigo-50 px-2.5 py-1.5 text-xs font-semibold text-indigo-700"
                      >
                        <option value="">＋ Chèn mẫu khai thành phần…</option>
                        {CONTRACT_SNIPPETS.map((snippet) => (
                          <option key={snippet.id} value={snippet.id}>{snippet.label}</option>
                        ))}
                      </select>
                      <button
                        onClick={() => setHelpersOpen((open) => !open)}
                        className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50"
                      >
                        <Lightbulb size={13} /> Bảng tra
                      </button>
                    </div>

                    {helpersOpen && (
                      <div className="space-y-2 rounded-xl border border-slate-200 bg-slate-50 p-3">
                        <div className="flex items-start justify-between gap-2">
                          <p className="text-[11px] font-bold text-slate-700">Các trường trong một thành phần</p>
                          <button onClick={() => setHelpersOpen(false)} className="-mr-1 -mt-1 rounded-md p-1 text-slate-400 hover:bg-white hover:text-slate-600" aria-label="Đóng bảng tra">
                            <X size={14} />
                          </button>
                        </div>
                        <div className="space-y-1">
                          {CONTRACT_FIELD_HELP.map((row) => (
                            <div key={row.field} className="flex flex-wrap items-baseline gap-x-2">
                              <code className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-indigo-700">{row.field}</code>
                              <span className="text-[10px] text-slate-500">{row.desc}</span>
                            </div>
                          ))}
                        </div>
                        <p className="pt-1 text-[11px] font-bold text-slate-700">Giá trị hợp lệ của <code className="font-mono">strategy</code></p>
                        <div className="space-y-1">
                          {contractCatalog.strategies.map((row) => (
                            <div key={row.code} className="flex flex-wrap items-baseline gap-x-2">
                              <code className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-indigo-700">{row.code}</code>
                              <span className="text-[10px] text-slate-500">{row.label}</span>
                            </div>
                          ))}
                        </div>
                        <p className="pt-1 text-[11px] font-bold text-slate-700">Nhóm icon dùng cho <code className="font-mono">strategy: &quot;icon&quot;</code></p>
                        <div className="flex flex-wrap gap-1">
                          {contractCatalog.icon_groups.map((name) => (
                            <code key={name} className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-slate-500">{name} = {ICON_GROUP_LABEL[name] || name}</code>
                          ))}
                        </div>
                        <p className="pt-1 text-[11px] font-bold text-slate-700">Tên widget hay dùng cho <code className="font-mono">strategy: &quot;widget_type&quot;</code></p>
                        <div className="flex flex-wrap gap-1">
                          {contractCatalog.common_widget_types.map((name) => (
                            <code key={name} className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-slate-500">{name}</code>
                          ))}
                        </div>
                      </div>
                    )}

                    <CodeEditor
                      language="json"
                      rows={16}
                      hint="JSON · Tab thụt · tự đóng ngoặc/nháy"
                      value={contractJson}
                      onChange={(next) => { setContractJson(next); setContractJsonError(""); }}
                    />
                    <p className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-xs font-semibold ${contractJsonError || !contractJsonStatus.ok ? "border-rose-200 bg-rose-50 text-rose-700" : "border-emerald-200 bg-emerald-50 text-emerald-700"}`}>
                      {contractJsonError || !contractJsonStatus.ok
                        ? <AlertCircle size={13} className="mt-0.5 shrink-0" />
                        : <CheckCircle2 size={13} className="mt-0.5 shrink-0" />}
                      {contractJsonError || contractJsonStatus.message}
                    </p>
                    <div className="flex justify-end gap-2">
                      <button onClick={() => switchContractMode("form")} className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 hover:bg-slate-50">
                        Hủy
                      </button>
                      <button onClick={applyContractJson} disabled={!contractJsonStatus.ok} className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-700 disabled:opacity-40">
                        <CheckCircle2 size={13} /> Áp dụng config
                      </button>
                    </div>
                  </>
                ) : (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <select
                        value=""
                        onChange={(e) => { if (e.target.value) addContractKey(e.target.value); }}
                        className="min-w-0 flex-1 rounded-lg border border-indigo-200 bg-indigo-50 px-2.5 py-1.5 text-xs font-semibold text-indigo-700"
                      >
                        <option value="">＋ Thêm thành phần giao diện…</option>
                        {(contractCatalog.default_keys || [])
                          .filter((option) => !contractKeys.some((row) => row.key === option.key))
                          .map((option) => (
                            <option key={option.key} value={option.key}>{option.label} — {option.key}</option>
                          ))}
                      </select>
                      <button onClick={() => addContractKey()} className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50">
                        Dòng trống
                      </button>
                      <button onClick={loadDefaultContract} className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50">
                        Thêm tất cả
                      </button>
                    </div>

                    {contractKeys.length === 0 ? (
                      <div className="rounded-xl border-2 border-dashed border-slate-200 p-6 text-center">
                        <p className="text-sm font-semibold text-slate-600">Chưa khai thành phần nào</p>
                        <p className="mt-1 text-xs text-slate-400">Chọn ở ô &quot;Thêm thành phần giao diện&quot; phía trên — mỗi mục đã có sẵn cách nhận diện mặc định.</p>
                      </div>
                    ) : (
                      <>
                        <div className="grid grid-cols-12 gap-2 px-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
                          <span className="col-span-3">Thành phần</span>
                          <span className="col-span-3">Khi bài không gắn key</span>
                          <span className="col-span-4">Giá trị để nhận diện</span>
                          <span className="col-span-1">Thứ tự</span>
                          <span className="col-span-1 text-right">Bắt buộc</span>
                        </div>
                        <div className="custom-scrollbar max-h-[420px] space-y-2 overflow-y-auto pr-1">
                          {contractKeys.map(renderContractRow)}
                        </div>
                      </>
                    )}
                  </>
                )}
              </div>
            )}
        </section>

        <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-[280px_minmax(420px,1fr)_minmax(420px,1fr)]">
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

          {/* Khu vực 2: thư viện template + nơi tự viết code */}
          <section className="card min-w-0 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex items-center justify-between gap-3">
                <div><p className="eyebrow">Khu vực 2</p><h2 className="mt-1 text-sm font-bold text-slate-800">Nguồn testcase</h2></div>
                <span className="text-xs text-slate-400">{libraryTab === "templates" ? `${visibleTemplates.length} template` : `${items.filter(isCustomItem).length} testcase tự viết`}</span>
              </div>
              <div className="mt-3 flex gap-1 rounded-lg bg-slate-100 p-1">
                <button
                  onClick={() => setLibraryTab("templates")}
                  className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-semibold transition-colors ${libraryTab === "templates" ? "bg-white text-indigo-700 shadow-sm" : "text-slate-500 hover:text-slate-700"}`}
                >
                  <Package size={13} /> Testcase có sẵn
                </button>
                <button
                  onClick={() => setLibraryTab("custom")}
                  className={`flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-semibold transition-colors ${libraryTab === "custom" ? "bg-white text-indigo-700 shadow-sm" : "text-slate-500 hover:text-slate-700"}`}
                >
                  <Code2 size={13} /> Tự viết code
                </button>
              </div>
              {libraryTab === "templates" && (
                <>
                  <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Tìm theo tên, skill, layer..." className="mt-3 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100" />
                  <div className="mt-2 flex items-center justify-between gap-2">
                    <button
                      onClick={openTemplateCreator}
                      disabled={!runnerCatalog}
                      title={runnerCatalog ? "Tạo testcase mới cho thư viện dùng chung" : "Chưa tải được danh mục runner"}
                      className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700 disabled:opacity-40"
                    >
                      <Plus size={13} /> Thêm testcase
                    </button>
                    <label className="flex cursor-pointer items-center gap-1.5 text-[11px] font-semibold text-slate-500">
                      <input type="checkbox" checked={showHiddenTemplates} onChange={(e) => setShowHiddenTemplates(e.target.checked)} className="h-3.5 w-3.5 accent-indigo-600" />
                      Hiện cả mục đã ẩn
                    </label>
                  </div>
                </>
              )}
            </div>
            {libraryTab === "custom" ? (
              <div className="custom-scrollbar max-h-[calc(100vh-295px)] min-h-[360px] overflow-y-auto p-3">
                {renderCodeComposer(12)}
              </div>
            ) : (
            <>
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
                        <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-bold text-emerald-700" title={ENGINE_LABEL[template.engine_type || ""] || template.engine_type}>Dùng chung</span>
                        <span className="rounded bg-cyan-100 px-1.5 py-0.5 text-[10px] font-bold text-cyan-700">{TESTCASE_GROUP_LABEL[testcaseGroup(template)]}</span>
                        <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[template.layer] || template.layer}</span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[template.difficulty] || template.difficulty}</span>
                        {template.origin === "CUSTOM" && <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700">Tự thêm</span>}
                        {template.origin === "OVERRIDE" && <span className="rounded bg-sky-100 px-1.5 py-0.5 text-[10px] font-bold text-sky-700">Đã sửa</span>}
                        {template.hidden && <span className="rounded bg-rose-100 px-1.5 py-0.5 text-[10px] font-bold text-rose-700">Đã ẩn</span>}
                      </div>
                      <p className="mt-1 line-clamp-3 text-xs leading-relaxed text-slate-500">{template.description}</p>
                      <div className="mt-2 flex items-center justify-between gap-2">
                        <span className="truncate text-[10px] text-indigo-600" title={template.skill_code}>{SKILL_LABEL[template.skill_code] || template.skill_name || template.skill_code}</span>
                        <span className="shrink-0 text-[11px] font-semibold text-slate-500">{template.weight_default} điểm mặc định</span>
                      </div>
                    </div>
                  </div>
                  <div className="mt-2 flex flex-wrap items-center justify-end gap-1 border-t border-slate-100 pt-2">
                    <button onClick={(e) => { e.stopPropagation(); setSelectedTemplateId(template.template_id); }} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100 hover:text-slate-700"><Eye size={13} /> Chi tiết</button>
                    {runnerCatalog && (
                      <button onClick={(e) => { e.stopPropagation(); openTemplateEditor(template); }} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100 hover:text-slate-700" title="Sửa testcase trong thư viện"><Settings2 size={13} /> Sửa</button>
                    )}
                    {template.hidden ? (
                      <button onClick={(e) => { e.stopPropagation(); restoreTemplate(template); }} disabled={templateBusy} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-emerald-600 hover:bg-emerald-50 disabled:opacity-40">Khôi phục</button>
                    ) : (
                      <button onClick={(e) => { e.stopPropagation(); setTemplateToHide(template); }} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-rose-600 hover:bg-rose-50" title="Ẩn khỏi thư viện"><Trash2 size={13} /> Xóa</button>
                    )}
                    {!template.hidden && (
                      <button onClick={(e) => { e.stopPropagation(); addTemplate(template.template_id); }} className="flex items-center gap-1 rounded-md bg-indigo-600 px-2 py-1 text-xs font-semibold text-white hover:bg-indigo-700"><Plus size={13} /> Thêm vào bộ testcase</button>
                    )}
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
            </>
            )}
          </section>

          {/* Khu vực 3: testcase instance của đề */}
          <section className="card min-w-0 overflow-hidden">
            <div className="border-b border-slate-100 bg-slate-50/70 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="eyebrow">Khu vực 3</p><h2 className="mt-1 text-sm font-bold text-slate-800">Testcase trong bộ</h2></div><div className="flex items-center gap-2"><span className="rounded-full bg-indigo-100 px-2.5 py-1 text-xs font-bold text-indigo-700">{items.length} mục</span>{supportsGrouping && selectedItemIds.length >= 2 && <button onClick={openGroupModal} className="rounded-lg bg-indigo-600 px-2.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-700">Gộp thành testcase lớn</button>}{items.length > 0 && <button onClick={clearAllItems} className="flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-semibold text-rose-700 hover:bg-rose-100" title="Xóa toàn bộ testcase"><Trash2 size={13} /> Xóa tất cả</button>}</div></div>
              <div className="mt-3 flex items-center justify-between text-xs"><span className="text-slate-500">Tổng trọng số</span><strong className="text-indigo-700">{totalWeight.toFixed(2)}</strong></div>
            </div>
            <div
              className="min-h-[360px] space-y-2 p-3"
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => { e.preventDefault(); if (draggedTemplateId) addTemplate(draggedTemplateId); setDraggedTemplateId(null); }}
            >
              {items.length === 0 ? (
                <div className="flex min-h-[330px] flex-col items-center justify-center rounded-xl border-2 border-dashed border-slate-200 p-8 text-center" onDragOver={(e) => e.preventDefault()}>
                  <Package size={28} className="mb-3 text-slate-300" />
                  <p className="text-sm font-semibold text-slate-600">Kéo testcase vào đây</p>
                  <p className="mt-1 text-xs text-slate-400">Hoặc bấm “Thêm vào bộ testcase” để tránh bỏ sót thao tác.</p>
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
                      <div className="mt-2 flex flex-wrap items-center gap-1.5">{isCustomItem(item) && <span className="flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold text-amber-700"><Code2 size={10} /> Code tay</span>}<span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-bold text-violet-700">{LAYER_LABEL[item.layer] || item.layer}</span><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[item.difficulty] || item.difficulty}</span><span className="text-[11px] font-semibold text-slate-500">{Number(item.weight).toFixed(2)} điểm</span></div>
                    </div>
                  </div>
                  <div className="mt-2 flex items-center justify-between border-t border-slate-100 pt-2">
                    <div className="flex items-center gap-3"><label className="flex items-center gap-1.5 text-xs text-slate-500"><input type="checkbox" checked={item.enabled} onChange={(e) => updateItem(item.instance_id, { enabled: e.target.checked })} /> Đang bật</label>{supportsGrouping && !isCustomItem(item) && <label className="flex items-center gap-1.5 text-xs text-indigo-600"><input type="checkbox" checked={selectedItemIds.includes(item.instance_id)} onChange={() => toggleItemSelection(item.instance_id)} /> Chọn nhóm</label>}</div>
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
                      {isCustomItem(item) ? (
                        <div className="space-y-2">
                          <label className="block text-xs text-slate-500">Tên testcase<input value={item.name} onChange={(e) => updateItem(item.instance_id, { name: e.target.value })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs" /></label>
                          <label className="block text-xs text-slate-500">Chủ đề năng lực
                            <select value={item.skill_code} onChange={(e) => updateItem(item.instance_id, { skill_code: e.target.value })} className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs">
                              {!skills.some((skill) => skill.code === item.skill_code) && <option value={item.skill_code}>{item.skill_code}</option>}
                              {skills.map((skill) => <option key={skill.code} value={skill.code}>{SKILL_LABEL[skill.code] || skill.name || skill.code}</option>)}
                            </select>
                          </label>
                          <div>
                            <div className="mb-1 flex items-center justify-between">
                              <p className="text-xs font-semibold text-slate-600">Code testcase</p>
                              <button onClick={() => checkCustomCode(item.custom_code ?? "")} className="text-[10px] font-bold text-indigo-600 hover:text-indigo-800">Kiểm tra cú pháp</button>
                            </div>
                            <CodeEditor value={item.custom_code ?? ""} onChange={(code) => updateItem(item.instance_id, { custom_code: code })} rows={10} />
                            <p className="mt-1 text-[10px] text-slate-400">Chỉ viết thân test; hệ thống bọc <code className="font-mono">testWidgets(&apos;{item.instance_id}&apos;, …)</code> khi lưu.</p>
                          </div>
                        </div>
                      ) : (
                      <div><p className="mb-1 text-xs font-semibold text-slate-600">Thông số template</p><div className="grid grid-cols-2 gap-2">{Object.keys(item.parameters || {}).map((key) => {
                        const template = templateMap.get(item.template_id);
                        const schemaValue = template?.parameters_schema?.[key];
                        const isNumber = typeof schemaValue === "number";
                        const options = PARAMETER_OPTIONS[key];
                        const paired = PAIRED_VALUE_PARAMS[key];
                        // Danh sách giá trị theo từng ô nhập: chọn từ dropdown nên không lệch
                        // số phần tử so với fieldKeys — lỗi hay gặp nhất khi gõ CSV tay.
                        if (paired) return (
                          <div key={key} className="col-span-2 rounded-lg bg-slate-50 p-2">
                            <p className="text-[11px] font-semibold text-slate-600">{PARAMETER_LABELS[key] || key}</p>
                            <p className="mb-1.5 text-[10px] text-slate-400">{paired.hint}</p>
                            <PairedValueEditor
                              fields={splitCsv(item.parameters[paired.source])}
                              value={formatParam(item.parameters[key])}
                              options={paired.options}
                              onChange={(next) => updateParameter(item, key, next)}
                            />
                          </div>
                        );
                        const cellClass = "mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-xs";
                        return <label key={key} className="text-[11px] text-slate-500">{PARAMETER_LABELS[key] || key}{options
                          ? <select value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className={cellClass}>{options.map((option) => <option key={option} value={option}>{PARAMETER_OPTION_LABELS[option] || option}</option>)}</select>
                          : SINGLE_KEY_PARAMS.has(key)
                            ? renderKeyField(formatParam(item.parameters[key]), (next) => updateParameter(item, key, next), cellClass)
                            : <input type={isNumber ? "number" : "text"} value={formatParam(item.parameters[key])} onChange={(e) => updateParameter(item, key, e.target.value)} className={cellClass} />}</label>;
                      })}</div></div>
                      )}
                      {!isCustomItem(item) && (
                        <div className="overflow-hidden rounded-lg border border-slate-700 bg-slate-950">
                          <div className="flex items-center justify-between gap-3 border-b border-slate-700 px-3 py-2">
                            <div>
                              <p className="text-[11px] font-bold text-slate-100">Code kiểm tra tương đương</p>
                              <p className="mt-0.5 text-[9px] text-slate-400">Chỉ đọc · cập nhật ngay khi đổi semantic key, input, expected hoặc tham số runner.</p>
                            </div>
                            <span className="rounded bg-slate-800 px-2 py-1 font-mono text-[9px] text-cyan-300">{item.runner || templateMap.get(item.template_id)?.runner}</span>
                          </div>
                          <pre className="custom-scrollbar max-h-72 overflow-auto whitespace-pre p-3 text-[10px] leading-relaxed text-slate-100">{testcaseCodePreview(item, templateMap.get(item.template_id))}</pre>
                        </div>
                      )}
                      <p className="text-[10px] text-slate-400">Expected trên sẽ được lưu vào kết quả chấm; actual chỉ xuất hiện sau khi grader chạy bài sinh viên.</p>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* Gợi ý key: key đã khai ở Khu vực 0 đứng trước bộ key quy ước chung */}
        <datalist id="semantic-key-options">
          {keySuggestions.map((row) => <option key={row.key} value={row.key} label={row.label || undefined} />)}
        </datalist>

        {typeof document !== "undefined" && previewOpen && createPortal(
          <div className="fixed inset-0 z-[82] flex min-h-screen min-w-full items-center justify-center bg-slate-950/65 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="generated-code-title" onClick={() => setPreviewOpen(false)}>
            <div className="flex max-h-[92vh] w-full max-w-6xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(event) => event.stopPropagation()}>
              <header className="flex shrink-0 items-start justify-between gap-4 border-b border-slate-200 px-5 py-4">
                <div>
                  <p className="eyebrow">Code sinh theo thời gian thực</p>
                  <h2 id="generated-code-title" className="mt-1 text-lg font-bold text-slate-800">Bộ file chấm hiện tại · {examId.trim()}</h2>
                  <p className="mt-1 text-xs leading-relaxed text-slate-500">Mặc định hiển thị toàn bộ <code className="font-mono">exam_test.dart</code>. Tham số từng testcase nằm trong <code className="font-mono">skills_matrix.json</code> và cũng cập nhật ngay khi form thay đổi.</p>
                </div>
                <div className="flex items-center gap-2">
                  {previewLoading && <span className="flex items-center gap-1.5 text-xs font-semibold text-indigo-600"><Loader2 size={14} className="animate-spin" /> Đang cập nhật</span>}
                  <button onClick={() => setPreviewOpen(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng cửa sổ xem code"><X size={18} /></button>
                </div>
              </header>
              {previewError && <div className="flex items-start gap-2 border-b border-rose-200 bg-rose-50 px-5 py-3 text-xs font-semibold text-rose-700"><AlertCircle size={15} className="mt-0.5 shrink-0" /> <span>{previewError}<br /><span className="font-normal">Code hợp lệ gần nhất vẫn được giữ bên dưới để đối chiếu.</span></span></div>}
              {previewNotice && <div className="flex items-start gap-2 border-b border-amber-200 bg-amber-50 px-5 py-3 text-xs font-semibold text-amber-800"><AlertCircle size={15} className="mt-0.5 shrink-0" /> <span>{previewNotice}<br /><span className="font-normal">Muốn cập nhật theo thời gian thực, hãy thay các testcase cũ bằng template hiện còn trong thư viện.</span></span></div>}
              {previewFiles.length === 0 && previewLoading ? (
                <div className="flex min-h-80 flex-1 items-center justify-center text-slate-400"><Loader2 size={24} className="animate-spin" /></div>
              ) : previewFiles.length === 0 ? (
                <div className="flex min-h-80 flex-1 items-center justify-center p-8 text-center text-sm text-slate-500">Chưa có file code hợp lệ để hiển thị.</div>
              ) : (
                <div className="flex min-h-0 flex-1 flex-col">
                  <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-slate-200 bg-slate-50 px-4 py-2">
                    {previewFiles.map((file, index) => (
                      <button key={file.name} onClick={() => setPreviewFile(index)} className={`shrink-0 rounded-lg px-3 py-1.5 font-mono text-xs ${index === previewFile ? "bg-indigo-100 font-bold text-indigo-700" : "text-slate-500 hover:bg-white hover:text-slate-700"}`}>{file.name}</button>
                    ))}
                  </div>
                  <pre className="custom-scrollbar min-h-0 flex-1 overflow-auto whitespace-pre bg-slate-950 p-5 text-[11px] leading-relaxed text-slate-100">{previewFiles[previewFile]?.content}</pre>
                </div>
              )}
            </div>
          </div>,
          document.body
        )}

        {typeof document !== "undefined" && createPortal(
          <>
            {contractDoc && (
              <div className="fixed inset-0 z-[85] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="contract-modal-title" onClick={() => setContractDoc(null)}>
                <div className="flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 px-5 py-4">
                    <div className="flex items-center gap-3">
                      <div className="rounded-xl bg-indigo-100 p-2 text-indigo-600"><Layers size={20} /></div>
                      <div>
                        <p className="eyebrow">Khu vực 0 · Hợp đồng bài làm</p>
                        <h2 id="contract-modal-title" className="text-lg font-bold text-slate-800">Nội dung công bố cho sinh viên</h2>
                      </div>
                    </div>
                    <button onClick={() => setContractDoc(null)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={18} /></button>
                  </header>
                  <main className="custom-scrollbar min-h-0 space-y-4 overflow-y-auto bg-slate-50 p-5">
                    <div>
                      <div className="mb-1.5 flex items-center justify-between gap-2">
                        <p className="text-xs font-bold text-slate-700">1. Dán vào đề bài</p>
                        <button onClick={() => navigator.clipboard?.writeText(contractDoc.requirements_text)} className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-600 hover:bg-slate-50">Sao chép</button>
                      </div>
                      <pre className="custom-scrollbar max-h-[280px] overflow-auto rounded-xl border border-slate-200 bg-white p-3 font-mono text-[11px] leading-relaxed text-slate-700">{contractDoc.requirements_text}</pre>
                    </div>
                    <div>
                      <div className="mb-1.5 flex items-center justify-between gap-2">
                        <p className="text-xs font-bold text-slate-700">
                          2. Phát kèm starter project (lib/exam_keys.dart)
                          <span className="ml-1.5 rounded bg-slate-200 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600">tùy chọn</span>
                        </p>
                        <button onClick={() => navigator.clipboard?.writeText(contractDoc.starter_dart)} className="rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-600 hover:bg-slate-50">Sao chép</button>
                      </div>
                      <pre className="custom-scrollbar max-h-[280px] overflow-auto rounded-xl border border-slate-700 bg-slate-900 p-3 font-mono text-[11px] leading-relaxed text-slate-100">{contractDoc.starter_dart}</pre>
                    </div>
                    <p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[11px] leading-relaxed text-amber-800">
                      File <code className="font-mono">exam_keys.dart</code> chỉ để sinh viên khỏi gõ sai chính tả tên key —
                      viết thẳng <code className="font-mono">const ValueKey(&apos;field.email&apos;)</code> trong widget cũng được
                      tính điểm như nhau. Hai nội dung trên cũng được ghi kèm bộ testcase
                      (<code className="font-mono">contract.json</code>, <code className="font-mono">contract.md</code>) khi bấm Lưu nháp hoặc Lưu.
                    </p>
                  </main>
                </div>
              </div>
            )}
            {templateEditor && templateDef && (
              <div className="fixed inset-0 z-[80] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="template-modal-title" onClick={() => setTemplateEditor(null)}>
                <div className="flex max-h-[92vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 px-5 py-4">
                    <div className="flex items-center gap-3">
                      <div className="rounded-xl bg-indigo-100 p-2 text-indigo-600"><Package size={20} /></div>
                      <div>
                        <p className="eyebrow">Khu vực 2 · Thư viện testcase</p>
                        <h2 id="template-modal-title" className="text-lg font-bold text-slate-800">
                          {templateEditor.mode === "create" ? "Thêm testcase vào thư viện" : `Sửa "${templateEditor.draft.name}"`}
                        </h2>
                      </div>
                    </div>
                    <button onClick={() => setTemplateEditor(null)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={18} /></button>
                  </header>
                  <main className="custom-scrollbar min-h-0 space-y-3 overflow-y-auto bg-slate-50 p-5">
                    <div className="rounded-xl border border-indigo-100 bg-indigo-50/60 p-3 text-[11px] leading-relaxed text-indigo-800">
                      Testcase ở đây dùng lại cho mọi bộ testcase. Chọn <strong>loại kiểm tra</strong> rồi khai tham số mặc định —
                      giáo viên vẫn chỉnh được tham số cho từng bộ testcase ở Khu vực 3. Tham số phải qua đúng bộ kiểm tra dùng
                      khi lưu bộ testcase, nên không tạo được testcase hỏng.
                    </div>

                    <div className="grid grid-cols-2 gap-2">
                      <label className="text-xs font-semibold text-slate-600">
                        Mã testcase (template_id)
                        <input
                          value={templateEditor.draft.template_id}
                          disabled={templateEditor.mode === "edit"}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, template_id: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") } })}
                          placeholder="VD: PRM393_USER_AVATAR"
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 font-mono text-xs font-normal disabled:bg-slate-100 disabled:text-slate-400"
                        />
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Loại kiểm tra (runner)
                        <select
                          value={templateEditor.draft.runner}
                          onChange={(e) => changeDraftRunner(e.target.value)}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        >
                          {runnerCatalog?.runners.map((row) => (
                            <option key={row.runner} value={row.runner}>{row.label}</option>
                          ))}
                        </select>
                      </label>
                      <label className="col-span-2 text-xs font-semibold text-slate-600">
                        Tên hiển thị
                        <input
                          value={templateEditor.draft.name}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, name: e.target.value } })}
                          placeholder="VD: Bắt buộc chọn ảnh đại diện trước khi thêm"
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        />
                      </label>
                      <label className="col-span-2 text-xs font-semibold text-slate-600">
                        Mô tả cho giáo viên
                        <textarea
                          value={templateEditor.draft.description}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, description: e.target.value } })}
                          rows={2}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        />
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Chủ đề năng lực
                        <select
                          value={templateEditor.draft.skill_code}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, skill_code: e.target.value } })}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        >
                          {!skills.length && <option value="">(chưa tải được syllabus)</option>}
                          {skills.map((skill) => (
                            <option key={skill.code} value={skill.code}>{SKILL_LABEL[skill.code] || skill.name || skill.code}</option>
                          ))}
                        </select>
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Nhóm testcase
                        <select
                          value={templateEditor.draft.testcase_group}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, testcase_group: e.target.value } })}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        >
                          {TESTCASE_GROUP_ORDER.filter((code) => code !== "ALL").map((code) => (
                            <option key={code} value={code}>{TESTCASE_GROUP_LABEL[code]}</option>
                          ))}
                        </select>
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Lớp kiểm tra
                        <select
                          value={templateEditor.draft.layer}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, layer: e.target.value } })}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        >
                          {(runnerCatalog?.layers || []).map((layer) => (
                            <option key={layer} value={layer}>{LAYER_LABEL[layer] || layer}</option>
                          ))}
                        </select>
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Độ khó
                        <select
                          value={templateEditor.draft.difficulty}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, difficulty: e.target.value } })}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        >
                          {(runnerCatalog?.difficulties || []).map((code) => (
                            <option key={code} value={code}>{DIFF_LABEL[code] || code}</option>
                          ))}
                        </select>
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Điểm mặc định
                        <input
                          type="number" min="0" step="0.5"
                          value={templateEditor.draft.weight_default}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, weight_default: Number(e.target.value) } })}
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        />
                      </label>
                      <label className="text-xs font-semibold text-slate-600">
                        Expected mẫu (tùy chọn)
                        <input
                          value={templateEditor.draft.expected_template}
                          onChange={(e) => setTemplateEditor((c) => c && { ...c, draft: { ...c.draft, expected_template: e.target.value } })}
                          placeholder="Để trống sẽ tự sinh; dùng {tenThamSo} để chèn giá trị"
                          className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-2 text-xs font-normal"
                        />
                      </label>
                    </div>

                    <div className="rounded-xl border border-slate-200 bg-white p-3">
                      <p className="text-xs font-bold text-slate-700">Tham số mặc định của {templateDef.label}</p>
                      <p className="mt-0.5 text-[11px] leading-relaxed text-slate-500">{templateDef.description}</p>
                      <div className="mt-2 grid grid-cols-2 gap-2">
                        {templateDef.parameters.map((param) => renderTemplateParam(param, templateEditor.draft))}
                      </div>
                    </div>

                    {templateError && (
                      <p className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">
                        <AlertCircle size={14} className="mt-0.5 shrink-0" /> {templateError}
                      </p>
                    )}
                  </main>
                  <footer className="flex shrink-0 justify-end gap-2 border-t border-slate-200 px-5 py-3">
                    <button onClick={() => setTemplateEditor(null)} className="rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">Hủy</button>
                    <button onClick={saveTemplate} disabled={templateBusy} className="flex items-center gap-2 rounded-xl bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 disabled:opacity-50">
                      {templateBusy ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
                      {templateEditor.mode === "create" ? "Thêm vào thư viện" : "Lưu thay đổi"}
                    </button>
                  </footer>
                </div>
              </div>
            )}
            {templateToHide && (
              <div className="fixed inset-0 z-[80] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" onClick={() => setTemplateToHide(null)}>
                <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-start gap-3">
                    <div className="rounded-xl bg-rose-100 p-2 text-rose-600"><Trash2 size={20} /></div>
                    <div>
                      <h3 className="text-base font-bold text-slate-800">Xóa &quot;{templateToHide.name}&quot; khỏi thư viện?</h3>
                      <p className="mt-1 text-xs leading-relaxed text-slate-500">
                        Testcase sẽ không còn hiện ở Khu vực 2. Các bộ testcase <strong>đã lưu</strong> vẫn giữ và chấm bình thường —
                        đây là lý do hệ thống ẩn thay vì xóa hẳn. Bật &quot;Hiện cả mục đã ẩn&quot; để khôi phục.
                      </p>
                    </div>
                  </div>
                  <div className="mt-5 flex justify-end gap-2">
                    <button onClick={() => setTemplateToHide(null)} className="rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">Hủy</button>
                    <button onClick={() => hideTemplate(templateToHide)} disabled={templateBusy} className="rounded-xl bg-rose-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-rose-700 disabled:opacity-50">Xóa khỏi thư viện</button>
                  </div>
                </div>
              </div>
            )}
            {codeModalOpen && (
              <div className="fixed inset-0 z-[75] flex min-h-screen min-w-full items-center justify-center bg-slate-950/60 p-4 backdrop-blur-[2px]" role="dialog" aria-modal="true" aria-labelledby="code-modal-title" onClick={() => setCodeModalOpen(false)}>
                <div className="flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
                  <header className="flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 px-5 py-4">
                    <div className="flex items-center gap-3"><div className="rounded-xl bg-indigo-100 p-2 text-indigo-600"><Code2 size={20} /></div><div><p className="eyebrow">Khu vực 2 · Tự viết code</p><h2 id="code-modal-title" className="text-lg font-bold text-slate-800">Soạn testcase bằng code Flutter</h2></div></div>
                    <button onClick={() => setCodeModalOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={18} /></button>
                  </header>
                  <main className="custom-scrollbar min-h-0 overflow-y-auto bg-slate-50 p-5">{renderCodeComposer(22, false)}</main>
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
                    <div className="flex items-start gap-3"><div className="rounded-xl bg-rose-100 p-2 text-rose-600"><Trash2 size={20} /></div><div><h3 id="clear-all-modal-title" className="text-base font-bold text-slate-800">Xóa toàn bộ testcase?</h3><p className="mt-1 text-xs leading-relaxed text-slate-500">Bạn sắp xóa {items.length} testcase khỏi bộ testcase hiện tại.</p></div></div>
                    <button onClick={() => setClearAllModalOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Đóng"><X size={17} /></button>
                  </div>
                  <div className="mx-5 rounded-xl border border-rose-100 bg-rose-50 p-3 text-xs leading-relaxed text-rose-700">Thao tác này chỉ xóa danh sách đang chỉnh sửa và chưa ghi đè dữ liệu cho đến khi bạn bấm Lưu nháp hoặc Lưu. Bạn có muốn tiếp tục không?</div>
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

/**
 * useSearchParams bắt buộc nằm dưới một Suspense boundary, nếu không bản build production
 * sẽ đứt ở bước prerender ("Missing Suspense boundary with useSearchParams").
 */
export default function TestcasesPage() {
  return (
    <Suspense fallback={
      <SidebarLayout title="Bộ testcase" subtitle="Đang tải…" activePath="/teacher/archive">
        <div className="flex items-center justify-center py-20 text-slate-400">
          <Loader2 size={24} className="animate-spin" />
        </div>
      </SidebarLayout>
    }>
      <TestcasesEditor />
    </Suspense>
  );
}
