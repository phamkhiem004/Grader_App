"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import {
  Check, CheckCircle2, Circle, Database, FileArchive, FileJson,
  Loader2, MonitorPlay, Play, Plus, Radio, Send, ShieldCheck,
  Square, Trash2, UploadCloud, XCircle,
} from "lucide-react";

type JsonMap = Record<string, unknown>;
type ArtifactType =
  | "STUDENT_DATABASE"
  | "HIDDEN_DATABASE"
  | "GOLDEN_SOLUTION"
  | "AUTOMATION_RECORD"
  | "GRADING_ENVIRONMENT"
  | "TESTCASE_DEFINITION"
  | "OUTPUT_DATABASE";

interface GoldenApp { id: string; name: string; runtime_url?: string | null; status: string }
interface Suite { id: string; suite_code: string; exam_id?: string; golden_app_id: string; name: string; status: string; database_contract?: JsonMap; recordings?: Recording[]; scenarios?: JsonMap[] }
interface Recording { id: string; suite_id: string; name: string; status: string; raw_trace?: JsonMap[]; initial_state?: JsonMap }
interface Artifact { id: string; type: ArtifactType; version: number; file_name: string; size_bytes: number; active: boolean; sha256: string }
interface Readiness { ready: boolean; missing: ArtifactType[]; artifacts: Partial<Record<ArtifactType, Artifact | null>> }
interface GoldenValidation { status: "NOT_RUN" | "RUNNING" | "PASSED" | "FAILED" | "UNAVAILABLE"; current: boolean; total_checkpoints?: number; passed_checkpoints?: number; log?: string }
interface RuntimeStatus { status: string; runtime_url?: string | null; runtime_path?: string | null; available?: boolean; cached?: boolean; message?: string; metadata?: JsonMap }

const ARTIFACTS: { type: ArtifactType; title: string; owner: "teacher" | "system"; accept: string; hint: string; icon: typeof Database }[] = [
  { type: "STUDENT_DATABASE", title: "1. Database phát cho sinh viên", owner: "teacher", accept: ".db,.sqlite,.sqlite3", hint: "Dữ liệu mẫu công khai đi cùng đề.", icon: Database },
  { type: "HIDDEN_DATABASE", title: "2. Database ẩn", owner: "teacher", accept: ".db,.sqlite,.sqlite3", hint: "Cùng schema, dữ liệu khác để chống hardcode.", icon: ShieldCheck },
  { type: "GOLDEN_SOLUTION", title: "3. Golden Solution", owner: "teacher", accept: ".zip", hint: "ZIP đáp án chuẩn dùng để tạo oracle.", icon: FileArchive },
  { type: "AUTOMATION_RECORD", title: "4. Bản ghi thao tác", owner: "system", accept: ".json", hint: "Hệ thống sinh khi dừng phiên record.", icon: Radio },
  { type: "GRADING_ENVIRONMENT", title: "5. Môi trường chấm", owner: "system", accept: ".json", hint: "API, driver, browser và timeout của suite.", icon: MonitorPlay },
  { type: "TESTCASE_DEFINITION", title: "6. File testcase", owner: "system", accept: ".json", hint: "7 cột Stage, Attribute, AttributeValue, ValueType, Value, Action, Browser.", icon: FileJson },
  { type: "OUTPUT_DATABASE", title: "7. Output Database", owner: "system", accept: ".db,.sqlite,.sqlite3", hint: "Hệ thống replay Golden trên DB ẩn rồi tự capture trạng thái DB sau thao tác.", icon: Database },
];

const ACTIONS = ["boot", "tap", "enter_text", "clear_text", "scroll", "back", "restart", "wait_until"];
const LOCATORS = ["semanticId", "valueKey", "label", "hint", "text"];

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: init?.body instanceof FormData ? init.headers : { "Content-Type": "application/json", ...init?.headers },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(String(data.error || `HTTP ${response.status}`));
  return data as T;
}

function bytes(value: number) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

function absoluteRuntimeUrl(value?: string | null) {
  if (!value) return "";
  try {
    if (/^https?:\/\//i.test(value)) return value;
    return new URL(value, new URL(API_BASE).origin).toString();
  } catch {
    return value;
  }
}

function BehaviorAuthoringEditor() {
  const search = useSearchParams();
  const [examId, setExamId] = useState(() => search.get("exam") || "");
  const [name, setName] = useState("");
  const [runtimeUrl, setRuntimeUrl] = useState("");
  const [databaseName, setDatabaseName] = useState("app.db");
  const [suite, setSuite] = useState<Suite | null>(null);
  const [availableSuites, setAvailableSuites] = useState<Suite[]>([]);
  const [recording, setRecording] = useState<Recording | null>(null);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [validation, setValidation] = useState<GoldenValidation | null>(null);
  const [runtimeStatus, setRuntimeStatus] = useState<RuntimeStatus | null>(null);
  const [recorderReady, setRecorderReady] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [action, setAction] = useState("tap");
  const [locator, setLocator] = useState("semanticId");
  const [locatorValue, setLocatorValue] = useState("");
  const [inputValue, setInputValue] = useState("");
  const [checkpointText, setCheckpointText] = useState("");
  const [hiddenCheckpointText, setHiddenCheckpointText] = useState("");
  const [checkpointMode, setCheckpointMode] = useState<"ui" | "database">("ui");
  const [uiCheckpointType, setUiCheckpointType] = useState<"text" | "component" | "no_exception">("text");
  const [checkpointLocator, setCheckpointLocator] = useState("semanticId");
  const [checkpointLocatorValue, setCheckpointLocatorValue] = useState("");
  const [checkpointVisible, setCheckpointVisible] = useState(true);
  const [databaseTable, setDatabaseTable] = useState("");
  const [databaseOperation, setDatabaseOperation] = useState("READ");
  const [databaseRow, setDatabaseRow] = useState("{}");
  const [databaseCount, setDatabaseCount] = useState("");
  const [scenarioCode, setScenarioCode] = useState("MAIN_FLOW");
  const [scenarioName, setScenarioName] = useState("Luồng chính");
  const [scenarioWeight, setScenarioWeight] = useState(10);
  const [viewportWidth, setViewportWidth] = useState(390);
  const [viewportHeight, setViewportHeight] = useState(844);
  const [testDesktop, setTestDesktop] = useState(true);
  const [desktopWidth, setDesktopWidth] = useState(1280);
  const [desktopHeight, setDesktopHeight] = useState(800);
  const goldenFrame = useRef<HTMLIFrameElement | null>(null);
  // The Golden iframe can still emit a debounced event immediately after Stop.
  // Keep an imperative session guard so those late events never reach a closed
  // recording while React is waiting for the next render/refresh.
  const activeRecordingId = useRef<string | null>(null);
  const acceptsRecorderEvents = useRef(false);
  const requestedSuite = search.get("suite");
  const previewUrl = useMemo(() => absoluteRuntimeUrl(runtimeUrl), [runtimeUrl]);
  const runtimeOrigin = useMemo(() => {
    try { return previewUrl ? new URL(previewUrl).origin : ""; }
    catch { return ""; }
  }, [previewUrl]);

  useEffect(() => setRecorderReady(false), [previewUrl]);

  const activeByType = useMemo(() => {
    const result: Partial<Record<ArtifactType, Artifact>> = {};
    artifacts.filter((item) => item.active).forEach((item) => { result[item.type] = item; });
    return result;
  }, [artifacts]);
  const recordingInputsReady = Boolean(
    activeByType.STUDENT_DATABASE
      && activeByType.HIDDEN_DATABASE
      && activeByType.GOLDEN_SOLUTION,
  );
  const refresh = useCallback(async (suiteId: string) => {
    const [suiteData, artifactData, readyData, validationData, runtimeData] = await Promise.all([
      api<Suite>(`/behavior-authoring/suites/${suiteId}`),
      api<Artifact[]>(`/behavior-authoring/suites/${suiteId}/artifacts`),
      api<Readiness>(`/behavior-authoring/suites/${suiteId}/artifacts/readiness`),
      api<GoldenValidation>(`/behavior-authoring/suites/${suiteId}/validate-golden`),
      api<RuntimeStatus>(`/behavior-authoring/suites/${suiteId}/runtime`),
    ]);
    setSuite(suiteData);
    setArtifacts(artifactData);
    setReadiness(readyData);
    setValidation(validationData);
    setRuntimeStatus(runtimeData);
    const active = (suiteData.recordings || []).find((item) => item.status === "ACTIVE");
    activeRecordingId.current = active?.id || null;
    acceptsRecorderEvents.current = Boolean(active);
    setRecording(active || null);
    const contractName = String(suiteData.database_contract?.database_name || "");
    if (contractName) setDatabaseName(contractName);
    if (suiteData.golden_app_id) {
      const golden = await api<GoldenApp>(`/behavior-authoring/golden-apps/${suiteData.golden_app_id}`);
      const hasUploadedGolden = artifactData.some((item) => item.active && item.type === "GOLDEN_SOLUTION");
      setRuntimeUrl(runtimeData.available
        ? (runtimeData.runtime_url || runtimeData.runtime_path || "")
        : (!hasUploadedGolden ? (golden.runtime_url || "") : ""));
    }
  }, []);

  useEffect(() => {
    if (suite) return;
    const load = async () => {
      try {
        const requestedExam = search.get("exam");
        const rows = await api<Suite[]>(`/behavior-authoring/suites${requestedExam ? `?examId=${encodeURIComponent(requestedExam)}` : ""}`);
        setAvailableSuites(rows);
        if (requestedSuite) {
          await refresh(requestedSuite);
          return;
        }
        if (requestedExam && rows.length) await refresh(rows[0].id);
      } catch (caught) {
        setError(caught instanceof Error ? caught.message : String(caught));
      }
    };
    void load();
  }, [refresh, requestedSuite, search, suite]);

  const run = async (key: string, task: () => Promise<void>) => {
    setBusy(key); setError(""); setNotice("");
    try { await task(); } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); }
    finally { setBusy(""); }
  };

  const createSuite = () => run("create", async () => {
    const cleanExam = examId.trim();
    if (!cleanExam || !name.trim()) throw new Error("Cần nhập mã đề và tên bộ chấm.");
    const golden = await api<GoldenApp>("/behavior-authoring/golden-apps", {
      method: "POST",
      body: JSON.stringify({ name: `${name.trim()} - Golden`, exam_id: cleanExam, runtime_url: runtimeUrl.trim() || null, platform: "WEB", ready: Boolean(runtimeUrl.trim()) }),
    });
    const created = await api<Suite>("/behavior-authoring/suites", {
      method: "POST",
      body: JSON.stringify({
        suite_code: `${cleanExam}_RAR`.toUpperCase().replace(/[^A-Z0-9_-]/g, "_"),
        exam_id: cleanExam,
        golden_app_id: golden.id,
        name: name.trim(),
        description: "Bộ chấm Record–Abstract–Replay",
        database_contract: { enabled: true, driver: "sqlite", database_name: databaseName.trim(), ignore_columns: ["created_at", "updated_at"] },
      }),
    });
    await refresh(created.id);
    setAvailableSuites((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(created.id)}`);
    setNotice("Đã tạo bộ chấm. Hãy cung cấp 3 artifact đầu vào rồi record luồng Golden Solution.");
  });

  const openSuite = async (selected: Suite) => {
    setError("");
    await refresh(selected.id);
    window.history.replaceState(null, "", `/teacher/behavior-authoring?suite=${encodeURIComponent(selected.id)}`);
  };

  const closeSuite = () => {
    activeRecordingId.current = null;
    acceptsRecorderEvents.current = false;
    setSuite(null);
    setRecording(null);
    setArtifacts([]);
    setReadiness(null);
    setValidation(null);
    setRuntimeStatus(null);
    setRuntimeUrl("");
    setExamId("");
    setName("");
    window.history.replaceState(null, "", "/teacher/archive");
  };

  const uploadArtifact = (type: ArtifactType, file?: File) => {
    if (!suite || !file) return;
    run(`upload-${type}`, async () => {
      const form = new FormData();
      form.append("file", file);
      await api(`/behavior-authoring/suites/${suite.id}/artifacts/${type}`, { method: "POST", body: form });
      await refresh(suite.id);
      setNotice(`Đã lưu ${file.name} thành version mới của ${type}.`);
    });
  };

  const startRecording = () => suite && run("record-start", async () => {
    const created = await api<Recording>(`/behavior-authoring/suites/${suite.id}/recordings`, {
      method: "POST", body: JSON.stringify({ name: scenarioName, viewport: { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1 }, initial_state: { reset_storage: true } }),
    });
    activeRecordingId.current = created.id;
    acceptsRecorderEvents.current = created.status === "ACTIVE";
    setRecording(created);
    await refresh(suite.id);
  });

  const deployGoldenRuntime = () => suite && run("runtime-deploy", async () => {
    setRuntimeUrl("");
    setRecorderReady(false);
    const deployed = await api<RuntimeStatus>(`/behavior-authoring/suites/${suite.id}/runtime/deploy`, { method: "POST" });
    setRuntimeStatus(deployed);
    if (!deployed.available) throw new Error(deployed.message || "Golden runtime chưa sẵn sàng.");
    setRuntimeUrl(deployed.runtime_url || deployed.runtime_path || "");
    setNotice(deployed.cached ? "Golden runtime đã sẵn sàng từ bản build hiện tại." : "Đã build Golden App và gắn semantic recorder.");
  });

  const captureUiSnapshot = () => {
    if (!recording || !goldenFrame.current?.contentWindow) return;
    goldenFrame.current.contentWindow.postMessage(
      { type: "GOLDEN_RECORDER_COMMAND", action: "snapshot_ui" },
      runtimeOrigin || "*",
    );
  };

  const appendAction = (payload?: JsonMap) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) return;
    run("record-event", async () => {
      const targetNeeded = ["tap", "enter_text", "clear_text", "scroll"].includes(action);
      const event = payload || {
        kind: "action",
        stage: "ACTION",
        action,
        target: targetNeeded ? { [locator]: locatorValue.trim() } : {},
        attribute: targetNeeded ? locator : "none",
        attributeValue: targetNeeded ? locatorValue.trim() : "",
        valueType: "string",
        value: action === "enter_text" ? inputValue : "",
        browser: "flutter_tester",
      };
      await api(`/behavior-authoring/recordings/${recordingId}/events`, { method: "POST", body: JSON.stringify(event) });
      await refresh(suite.id);
      setLocatorValue(""); setInputValue("");
    });
  };

  const appendUiCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    const textReady = Boolean(checkpointText.trim() || hiddenCheckpointText.trim());
    const componentReady = Boolean(checkpointLocatorValue.trim());
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite
        || (uiCheckpointType === "text" && !textReady)
        || (uiCheckpointType === "component" && !componentReady)) return;
    run("record-checkpoint", async () => {
      const event: JsonMap = {
        kind: "checkpoint", stage: "ASSERT", action: "observe_ui", browser: "flutter_tester",
        attribute: uiCheckpointType === "component" ? checkpointLocator : uiCheckpointType,
        attributeValue: uiCheckpointType === "component" ? checkpointLocatorValue.trim() : checkpointText.trim(),
        valueType: uiCheckpointType === "no_exception" ? "boolean" : "string",
        value: uiCheckpointType === "no_exception" ? true : checkpointText.trim(),
      };
      if (uiCheckpointType === "text") {
        event.expect = {
          visible_texts: checkpointText.split(",").map((item) => item.trim()).filter(Boolean),
          hidden_texts: hiddenCheckpointText.split(",").map((item) => item.trim()).filter(Boolean),
          no_exception: true,
        };
      } else if (uiCheckpointType === "component") {
        event.target = { [checkpointLocator]: checkpointLocatorValue.trim() };
        event.visible = checkpointVisible;
        event.expect = { visible: checkpointVisible, no_exception: true };
      } else {
        event.no_exception = true;
        event.expect = { no_exception: true };
      }
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify(event),
      });
      await refresh(suite.id);
      setCheckpointText(""); setHiddenCheckpointText(""); setCheckpointLocatorValue("");
    });
  };

  const deleteRecordedEvent = (sequence: number) => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite) return;
    run(`delete-event-${sequence}`, async () => {
      await api(`/behavior-authoring/recordings/${recordingId}/events/${sequence}`, { method: "DELETE" });
      await refresh(suite.id);
      setNotice(`Đã xóa thao tác/checkpoint số ${sequence}.`);
    });
  };

  const appendDatabaseCheckpoint = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !acceptsRecorderEvents.current || recording?.status !== "ACTIVE" || !suite || !databaseTable.trim()) return;
    run("record-db-checkpoint", async () => {
      let row: JsonMap = {};
      try {
        const parsed = JSON.parse(databaseRow || "{}");
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error();
        row = parsed as JsonMap;
      } catch {
        throw new Error("Row mong đợi phải là JSON object, ví dụ {\"uid\":\"SV01\"}.");
      }
      const count = databaseCount.trim() === "" ? undefined : Number(databaseCount);
      if (count !== undefined && (!Number.isInteger(count) || count < 0)) throw new Error("Count phải là số nguyên không âm.");
      await api(`/behavior-authoring/recordings/${recordingId}/events`, {
        method: "POST",
        body: JSON.stringify({
          kind: "database_observation", checkpoint: true, scope: "database",
          stage: "ASSERT", attribute: "table", attributeValue: databaseTable.trim(),
          valueType: "json", value: row, action: "observe_database", browser: "sqlite",
          table: databaseTable.trim(), operation: databaseOperation, row,
          absent: databaseOperation === "DELETE", ...(count === undefined ? {} : { count }),
        }),
      });
      await refresh(suite.id); setDatabaseRow("{}"); setDatabaseCount("");
    });
  };

  const stopAndAbstract = () => {
    const recordingId = activeRecordingId.current;
    if (!recordingId || !recording || recording.status !== "ACTIVE" || !suite) return;
    // Close the client-side gate before the first network request. This is
    // intentionally earlier than the backend transition to prevent iframe
    // messages racing with /stop and /abstract.
    acceptsRecorderEvents.current = false;
    activeRecordingId.current = null;
    setRecording(null);
    run("record-stop", async () => {
      await api(`/behavior-authoring/recordings/${recordingId}/stop`, { method: "POST", body: JSON.stringify({ final_observation: {} }) });
      await api(`/behavior-authoring/recordings/${recordingId}/abstract`, {
        method: "POST",
        body: JSON.stringify({
          scenario_code: scenarioCode.trim().toUpperCase(),
          name: scenarioName.trim(),
          weight: scenarioWeight,
          viewports: [
            { width: viewportWidth, height: viewportHeight, device_pixel_ratio: 1, name: "phone" },
            ...(testDesktop ? [{ width: desktopWidth, height: desktopHeight, device_pixel_ratio: 1, name: "desktop" }] : []),
          ],
        }),
      });
      await refresh(suite.id);
      setNotice("Đã replay Golden trên Database ẩn, sinh Output Database, oracle và testcase-definition.json.");
    });
  };

  const publish = () => suite && run("publish", async () => {
    await api(`/behavior-authoring/suites/${suite.id}/publish`, { method: "POST" });
    await refresh(suite.id);
    setNotice("Bộ chấm đã publish và materialize thành runner có thể dùng khi chấm batch.");
  });

  const validateGolden = () => suite && run("validate-golden", async () => {
    const result = await api<GoldenValidation>(`/behavior-authoring/suites/${suite.id}/validate-golden`, { method: "POST" });
    setValidation(result);
    await refresh(suite.id);
    if (result.status !== "PASSED") throw new Error(result.status === "UNAVAILABLE"
      ? "Không gọi được Docker để kiểm chứng Golden. Hãy bật Docker và thử lại."
      : `Golden preflight chưa pass (${result.passed_checkpoints || 0}/${result.total_checkpoints || 0} checkpoint).`);
    setNotice(`Golden Solution đã pass ${result.passed_checkpoints}/${result.total_checkpoints} checkpoint.`);
  });

  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (runtimeOrigin && event.origin !== runtimeOrigin) return;
      if (!event.data || typeof event.data !== "object") return;
      if (event.data.type === "GOLDEN_RECORDER_READY") {
        setRecorderReady(true);
        return;
      }
      if (event.data.type === "GOLDEN_RECORDER_WARNING") {
        const message = event.data.payload?.message;
        if (typeof message === "string") setNotice(message);
        return;
      }
      if (!acceptsRecorderEvents.current || !activeRecordingId.current || event.data.type !== "GOLDEN_RECORDER_EVENT") return;
      if (!event.data.payload || typeof event.data.payload !== "object" || Array.isArray(event.data.payload)) return;
      appendAction(event.data.payload as JsonMap);
    };
    window.addEventListener("message", receive);
    return () => window.removeEventListener("message", receive);
  }, [recording, runtimeOrigin]);

  return (
    <SidebarLayout activePath="/teacher/archive" title="Quản lý bộ chấm Golden" subtitle="Record thao tác thật, trừu tượng hóa hành vi và replay tự động trên bài sinh viên" contentClassName="!max-w-none">
      <div className="mx-auto max-w-[1500px] space-y-5 p-6 text-slate-800 dark:text-slate-100">
        {(error || notice) && (
          <div className={`flex items-center gap-3 rounded-xl border px-4 py-3 ${error ? "border-rose-300 bg-rose-50 text-rose-700 dark:border-rose-700 dark:bg-rose-950/40 dark:text-rose-200" : "border-emerald-300 bg-emerald-50 text-emerald-700 dark:border-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-200"}`}>
            {error ? <XCircle size={20} /> : <CheckCircle2 size={20} />}<span className="font-medium">{error || notice}</span>
          </div>
        )}

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 1</p><h2 className="text-xl font-bold">{suite ? "Thông tin bộ chấm" : "Chọn hoặc khởi tạo Golden suite"}</h2></div><div className="flex items-center gap-2">{suite && <><span className="rounded-full bg-indigo-100 px-3 py-1 text-sm font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{suite.suite_code} · {suite.status}</span><button onClick={closeSuite} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-bold hover:border-indigo-400 dark:border-slate-700">Danh sách bộ chấm</button></>}</div></div>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <input value={suite?.exam_id || examId} disabled={Boolean(suite)} onChange={(e) => setExamId(e.target.value)} placeholder="Mã đề, ví dụ PE_PRM393" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={suite?.name || name} disabled={Boolean(suite)} onChange={(e) => setName(e.target.value)} placeholder="Tên bộ chấm" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={runtimeUrl} disabled={Boolean(suite)} onChange={(e) => setRuntimeUrl(e.target.value)} placeholder="URL Golden App đã deploy (không bắt buộc)" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
            <input value={databaseName} disabled={Boolean(suite)} onChange={(e) => setDatabaseName(e.target.value)} placeholder="Tên file SQLite dùng chung, ví dụ app.db" className="rounded-xl border border-slate-300 bg-transparent px-4 py-3 outline-none focus:border-indigo-500 disabled:opacity-60 dark:border-slate-700" />
          </div>
          {!suite && <><button onClick={createSuite} disabled={Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-3 font-bold text-white hover:bg-indigo-500 disabled:opacity-50">{busy === "create" ? <Loader2 className="animate-spin" size={18} /> : <Plus size={18} />} Tạo bộ chấm mới</button>{availableSuites.length > 0 && <div className="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-3">{availableSuites.map((item) => <button key={item.id} onClick={() => void openSuite(item)} className="rounded-xl border border-slate-200 p-4 text-left transition hover:border-indigo-400 hover:bg-indigo-50/50 dark:border-slate-700 dark:hover:bg-indigo-950/20"><div className="flex items-center justify-between gap-2"><span className="font-bold">{item.name}</span><span className="rounded-full bg-slate-100 px-2 py-1 text-xs font-bold text-slate-600 dark:bg-slate-800 dark:text-slate-300">{item.status}</span></div><p className="mt-1 font-mono text-xs text-indigo-500">{item.suite_code}</p><p className="mt-2 text-xs text-slate-500">Mã đề: {item.exam_id || "chưa gắn"}</p></button>)}</div>}</>}
        </section>

        {suite && <>
          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="mb-4 flex flex-wrap items-end justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 2</p><h2 className="text-xl font-bold">Bảy thành phần của bộ chấm</h2><p className="mt-1 text-sm text-slate-500">Chỉ cần tải Database phát sinh viên, Database ẩn và Golden Solution. Khi kết thúc record, hệ thống tự sinh Automation Record, Testcase Definition và replay Golden với Database ẩn để capture Output Database.</p></div><span className="text-sm font-semibold">{7 - (readiness?.missing.length || 0)}/7 hợp lệ</span></div>
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              {ARTIFACTS.map((item) => {
                const current = activeByType[item.type]; const Icon = item.icon; const uploading = busy === `upload-${item.type}`;
                return <div key={item.type} className={`relative rounded-xl border p-4 ${item.owner === "system" ? "border-violet-200 bg-violet-50/60 dark:border-violet-900 dark:bg-violet-950/20" : "border-slate-200 dark:border-slate-700"}`}>
                  <div className="flex items-start justify-between"><Icon size={21} className={item.owner === "system" ? "text-violet-500" : "text-indigo-500"} />{current ? <CheckCircle2 size={19} className="text-emerald-500" /> : <Circle size={19} className="text-slate-300" />}</div>
                  <h3 className="mt-3 font-bold">{item.title}</h3><p className="mt-1 min-h-10 text-xs text-slate-500">{item.hint}</p>
                  {current && <p className="mt-2 truncate text-xs font-medium text-emerald-600" title={current.sha256}>{current.file_name} · v{current.version} · {bytes(current.size_bytes)}</p>}
                  {item.owner === "teacher" ? <label className="mt-3 inline-flex cursor-pointer items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-xs font-bold hover:border-indigo-400 dark:border-slate-700">{uploading ? <Loader2 size={15} className="animate-spin" /> : <UploadCloud size={15} />} {current ? "Tạo version mới" : "Chọn file"}<input type="file" accept={item.accept} className="hidden" onChange={(e) => uploadArtifact(item.type, e.target.files?.[0])} /></label> : <span className="mt-3 inline-block rounded-lg bg-violet-100 px-3 py-2 text-xs font-bold text-violet-700 dark:bg-violet-900/50 dark:text-violet-200">Tự động sinh</span>}
                </div>;
              })}
            </div>
          </section>

          <section className="grid min-w-0 gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
            <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 3</p><h2 className="text-xl font-bold">Thao tác trên Golden App</h2>
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button onClick={deployGoldenRuntime} disabled={!activeByType.GOLDEN_SOLUTION || Boolean(busy)} className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-40">{busy === "runtime-deploy" ? <Loader2 size={16} className="animate-spin" /> : <MonitorPlay size={16} />} Build & mở Golden</button>
                <span className={`rounded-full px-3 py-1 text-xs font-bold ${recorderReady ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300" : "bg-slate-100 text-slate-500 dark:bg-slate-800"}`}>{recorderReady ? "Recorder đã kết nối" : runtimeStatus?.status || "Chưa build"}</span>
                {recording && recorderReady && <button onClick={captureUiSnapshot} className="inline-flex items-center gap-2 rounded-lg border border-emerald-400 px-3 py-2 text-xs font-bold text-emerald-700 dark:text-emerald-300"><Check size={15} /> Chụp trạng thái UI</button>}
              </div>
              {previewUrl ? <div className="mt-4 w-full overflow-auto rounded-xl border border-slate-300 bg-slate-100 p-3 dark:border-slate-700 dark:bg-slate-950"><iframe ref={goldenFrame} title="Golden App" src={previewUrl} style={{ width: Math.min(viewportWidth, 900), minWidth: Math.min(viewportWidth, 900), height: Math.min(viewportHeight, 700) }} className="mx-auto block rounded-lg border border-slate-300 bg-white dark:border-slate-700" /></div> : <div className="mt-4 flex h-[300px] flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 text-center dark:border-slate-700"><MonitorPlay size={42} className="text-slate-400" /><p className="mt-3 font-bold">Golden Solution chưa được build để thao tác</p><p className="mt-1 max-w-md text-sm text-slate-500">Upload Golden ZIP rồi bấm “Build & mở Golden”. Hệ thống tự host app và ghi click/nhập liệu bằng semantic locator.</p></div>}
            </div>

            <div className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
              <div className="flex items-center justify-between"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 4</p><h2 className="text-xl font-bold">Record → Abstract</h2></div>{recording ? <span className="flex items-center gap-2 text-sm font-bold text-rose-500"><span className="h-2 w-2 animate-pulse rounded-full bg-rose-500" /> Đang ghi</span> : <span className="text-sm text-slate-500">Chưa ghi</span>}</div>
              <div className="mt-4 grid gap-3 sm:grid-cols-3"><input value={scenarioCode} onChange={(e) => setScenarioCode(e.target.value)} placeholder="Mã luồng" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input value={scenarioName} onChange={(e) => setScenarioName(e.target.value)} placeholder="Tên luồng" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input type="number" min={0.1} step={0.5} value={scenarioWeight} onChange={(e) => setScenarioWeight(Number(e.target.value))} aria-label="Trọng số scenario" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div>
              <div className="mt-2 grid gap-2 sm:grid-cols-2 xl:grid-cols-4"><label className="text-xs font-semibold text-slate-500">Rộng điện thoại<input type="number" min={240} value={viewportWidth} onChange={(e) => setViewportWidth(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label><label className="text-xs font-semibold text-slate-500">Cao điện thoại<input type="number" min={320} value={viewportHeight} onChange={(e) => setViewportHeight(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 dark:border-slate-700 dark:text-slate-100" /></label><label className="text-xs font-semibold text-slate-500">Rộng desktop<input type="number" min={600} value={desktopWidth} disabled={!testDesktop} onChange={(e) => setDesktopWidth(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 disabled:opacity-40 dark:border-slate-700 dark:text-slate-100" /></label><label className="text-xs font-semibold text-slate-500">Cao desktop<input type="number" min={480} value={desktopHeight} disabled={!testDesktop} onChange={(e) => setDesktopHeight(Number(e.target.value))} className="mt-1 w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-slate-800 disabled:opacity-40 dark:border-slate-700 dark:text-slate-100" /></label></div>
              <label className="mt-2 inline-flex items-center gap-2 text-xs font-semibold text-slate-600 dark:text-slate-300"><input type="checkbox" checked={testDesktop} onChange={(e) => setTestDesktop(e.target.checked)} /> Replay checkpoint UI trên cả điện thoại và desktop</label>
              {!recording ? <><button onClick={startRecording} disabled={!recordingInputsReady || Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 font-bold text-white disabled:opacity-40"><Radio size={18} /> Bắt đầu record</button>{!recordingInputsReady && <p className="mt-2 text-xs text-amber-600">Cần đủ Database phát sinh viên, Database ẩn và Golden Solution.</p>}</> : <>
                <div className="mt-5 rounded-xl border border-slate-200 p-4 dark:border-slate-700"><h3 className="font-bold">Thêm action</h3><div className="mt-3 grid gap-2 sm:grid-cols-2"><select value={action} onChange={(e) => setAction(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{ACTIONS.map((item) => <option key={item} value={item}>{item}</option>)}</select><select value={locator} onChange={(e) => setLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item}</option>)}</select><input value={locatorValue} onChange={(e) => setLocatorValue(e.target.value)} placeholder="Giá trị nhận diện" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /><input value={inputValue} onChange={(e) => setInputValue(e.target.value)} placeholder="Dữ liệu nhập (nếu có)" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" /></div><button onClick={() => appendAction()} className="mt-3 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-bold text-white"><Plus size={16} /> Thêm action</button></div>
                <div className="mt-3 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                  <div className="flex items-center justify-between gap-3">
                    <div><h3 className="font-bold">Thêm checkpoint</h3><p className="text-xs text-slate-500">Mỗi checkpoint trở thành một đầu điểm độc lập.</p></div>
                    <div className="flex rounded-lg bg-slate-100 p-1 text-xs font-bold dark:bg-slate-800">
                      <button onClick={() => setCheckpointMode("ui")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "ui" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>UI</button>
                      <button onClick={() => setCheckpointMode("database")} className={`rounded-md px-3 py-1.5 ${checkpointMode === "database" ? "bg-white text-indigo-600 shadow dark:bg-slate-700" : "text-slate-500"}`}>Database</button>
                    </div>
                  </div>
                  {checkpointMode === "ui" ? (
                    <div className="mt-3 space-y-2">
                      <select value={uiCheckpointType} onChange={(e) => setUiCheckpointType(e.target.value as "text" | "component" | "no_exception")} className="w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">
                        <option value="text">Nội dung text xuất hiện / không xuất hiện</option>
                        <option value="component">Thành phần UI tồn tại / không tồn tại</option>
                        <option value="no_exception">Luồng không phát sinh exception</option>
                      </select>
                      {uiCheckpointType === "text" && <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                        <input value={checkpointText} onChange={(e) => setCheckpointText(e.target.value)} placeholder="Text phải xuất hiện, cách nhau dấu phẩy" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <input value={hiddenCheckpointText} onChange={(e) => setHiddenCheckpointText(e.target.value)} placeholder="Text không được xuất hiện (tùy chọn)" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint UI" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                      {uiCheckpointType === "component" && <div className="grid gap-2 sm:grid-cols-[0.8fr_1.2fr_0.8fr_auto]">
                        <select value={checkpointLocator} onChange={(e) => setCheckpointLocator(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700">{LOCATORS.map((item) => <option key={item} value={item}>{item}</option>)}</select>
                        <input value={checkpointLocatorValue} onChange={(e) => setCheckpointLocatorValue(e.target.value)} placeholder="Giá trị nhận diện thành phần" className="min-w-0 rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <select value={checkpointVisible ? "visible" : "hidden"} onChange={(e) => setCheckpointVisible(e.target.value === "visible")} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700"><option value="visible">Phải tồn tại</option><option value="hidden">Không tồn tại</option></select>
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint thành phần" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                      {uiCheckpointType === "no_exception" && <div className="flex items-center justify-between gap-3 rounded-lg bg-slate-50 px-3 py-2 text-sm dark:bg-slate-800">
                        <span>Checkpoint pass khi luồng chạy tới đây mà ứng dụng không ném exception.</span>
                        <button onClick={appendUiCheckpoint} title="Lưu checkpoint không exception" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>}
                    </div>
                  ) : (
                    <div className="mt-3 space-y-2">
                      <div className="grid gap-2 sm:grid-cols-3">
                        <input value={databaseTable} onChange={(e) => setDatabaseTable(e.target.value)} placeholder="Tên bảng, ví dụ users" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                        <select value={databaseOperation} onChange={(e) => setDatabaseOperation(e.target.value)} className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700"><option>READ</option><option>INSERT</option><option>UPDATE</option><option>DELETE</option></select>
                        <input value={databaseCount} onChange={(e) => setDatabaseCount(e.target.value)} placeholder="Tổng row (tùy chọn)" inputMode="numeric" className="rounded-lg border border-slate-300 bg-transparent px-3 py-2 dark:border-slate-700" />
                      </div>
                      <div className="flex gap-2">
                        <textarea value={databaseRow} onChange={(e) => setDatabaseRow(e.target.value)} rows={2} placeholder={'Row JSON, ví dụ {"uid":"SV01"}'} className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-transparent px-3 py-2 font-mono text-xs dark:border-slate-700" />
                        <button onClick={appendDatabaseCheckpoint} title="Luu checkpoint database" className="rounded-lg bg-emerald-600 px-3 py-2 text-sm font-bold text-white"><Check size={16} /></button>
                      </div>
                      <p className="text-xs text-slate-500">Mục này dùng để bổ sung assertion DB có chủ đích. Dù bỏ qua, hệ thống vẫn tự replay Golden, so Hidden DB với Output DB và tách INSERT/UPDATE/DELETE thành checkpoint độc lập.</p>
                    </div>
                  )}
                </div>
                <div className="mt-4 max-h-48 space-y-2 overflow-auto">{(recording.raw_trace || []).map((item, index) => {
                  const sequence = Number(item.sequence || index + 1);
                  return <div key={sequence} className="flex items-center gap-3 rounded-lg bg-slate-50 px-3 py-2 text-xs dark:bg-slate-800">
                    <span className="font-mono text-indigo-500">{sequence}</span>
                    <span className="font-bold">{String(item.action || item.kind)}</span>
                    <span className="min-w-0 flex-1 truncate text-slate-500">{JSON.stringify(item.target || item.expect || {})}</span>
                    <button onClick={() => deleteRecordedEvent(sequence)} disabled={Boolean(busy)} title="Xóa thao tác/checkpoint này" className="rounded-md p-1.5 text-rose-500 hover:bg-rose-100 disabled:opacity-40 dark:hover:bg-rose-950"><Trash2 size={15} /></button>
                  </div>;
                })}</div>
                <button onClick={stopAndAbstract} disabled={Boolean(busy)} className="mt-4 inline-flex items-center gap-2 rounded-xl bg-slate-800 px-4 py-2.5 font-bold text-white disabled:opacity-40 dark:bg-slate-700">{busy === "record-stop" ? <Loader2 size={17} className="animate-spin" /> : <Square size={17} />} {busy === "record-stop" ? "Đang replay Golden và sinh Output DB…" : "Dừng, capture oracle và sinh testcase"}</button>
                <p className="mt-2 text-xs text-slate-500">Bước này có thể mất vài phút vì chạy chính Golden Solution trong Docker bằng Hidden DB; không cần tự xuất hoặc tải Output DB.</p>
              </>}
            </div>
          </section>

          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900">
            <div className="flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-widest text-indigo-500">Bước 5</p><h2 className="text-xl font-bold">Kiểm chứng Golden và publish</h2><p className="mt-1 text-sm text-slate-500">Còn thiếu: {readiness?.missing.join(", ") || "không"}. Preflight chạy chính plan trên Golden; publish chỉ mở khi toàn bộ checkpoint pass.</p><p className={`mt-2 text-sm font-bold ${validation?.status === "PASSED" && validation.current ? "text-emerald-600" : "text-amber-600"}`}>Preflight: {validation?.status || "NOT_RUN"}{validation?.total_checkpoints !== undefined ? ` · ${validation.passed_checkpoints}/${validation.total_checkpoints}` : ""}{validation && !validation.current ? " · plan đã thay đổi" : ""}</p></div><div className="flex flex-wrap gap-2"><button onClick={validateGolden} disabled={!readiness?.ready || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl border border-indigo-300 px-5 py-3 font-bold text-indigo-700 disabled:cursor-not-allowed disabled:opacity-40 dark:border-indigo-700 dark:text-indigo-300">{busy === "validate-golden" ? <Loader2 className="animate-spin" size={18} /> : <Play size={18} />} Chạy thử trên Golden</button><button onClick={publish} disabled={!readiness?.ready || !validation?.current || validation.status !== "PASSED" || Boolean(recording) || Boolean(busy)} className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-5 py-3 font-bold text-white disabled:cursor-not-allowed disabled:opacity-40">{busy === "publish" ? <Loader2 className="animate-spin" size={18} /> : <Send size={18} />} Publish bộ chấm</button></div></div>
            <div className="mt-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {(suite.scenarios || []).map((item, index) => <div key={String(item.id || index)} className="rounded-xl border border-slate-200 px-4 py-3 dark:border-slate-700"><div className="flex items-center justify-between gap-2"><span className="font-bold">{String(item.name || item.scenario_code)}</span><span className="rounded-full bg-indigo-100 px-2 py-1 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{String(item.weight)} điểm</span></div><p className="mt-1 text-xs text-slate-500">{Array.isArray(item.steps) ? item.steps.length : 0} action · {Array.isArray(item.checkpoints) ? item.checkpoints.length : 0} checkpoint</p></div>)}
              {!suite.scenarios?.length && <p className="text-sm text-slate-500">Chưa có scenario. Hãy record ít nhất một luồng và sinh testcase.</p>}
            </div>
          </section>
        </>}
      </div>
    </SidebarLayout>
  );
}

export default function BehaviorAuthoringPage() {
  return (
    <Suspense fallback={
      <SidebarLayout activePath="/teacher/archive" title="Golden Solution Recorder" subtitle="Đang nạp bộ soạn hành vi…">
        <div className="flex min-h-[50vh] items-center justify-center text-slate-400">
          <Loader2 className="animate-spin" size={28} />
        </div>
      </SidebarLayout>
    }>
      <BehaviorAuthoringEditor />
    </Suspense>
  );
}
