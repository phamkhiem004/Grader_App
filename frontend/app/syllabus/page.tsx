"use client";

import React, { useEffect, useState, useCallback } from "react";
import { createPortal } from "react-dom";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE } from "@/lib/config";
import SyllabusCoverage from "@/components/grading/SyllabusCoverage";
import ErrorScreen from "@/components/ui/ErrorScreen";
import { appError, kindOf, messageOf } from "@/lib/errors";
import {
  BookOpen, Plus, Pencil, Trash2, Loader2, X, Layers, GraduationCap,
  AlertTriangle, EyeOff,
} from "lucide-react";

// ── Kiểu dữ liệu ───────────────────────────────────────────────
interface SkillT {
  code: string; category: string; name: string; description?: string;
  default_difficulty: string; testable: string; deprecated: boolean;
  display_order: number; resources?: string[];
}
interface CategoryT {
  code: string; name: string; competency_label?: string; description?: string;
  display_order: number; weak_threshold: number; good_threshold: number;
  active: boolean; skills: SkillT[];
}
interface MetaT {
  version?: string;
  subject?: { code?: string; name?: string; note?: string };
  difficulty_levels?: { code: string; label: string; criteria: string }[];
}
interface TreeT { meta: MetaT; categories: CategoryT[]; }

const DIFFS = ["basic", "intermediate", "advanced"];
const DIFF_LABEL: Record<string, string> = { basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao" };

// ── Gọi API cho thao tác ghi ──────────────────────────────────
async function apiJson(path: string, method: string, body?: unknown) {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Có lỗi xảy ra");
  return data;
}

export default function SyllabusPage() {
  const [tree, setTree] = useState<TreeT | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<unknown>(null);
  // Tăng mỗi khi syllabus được nạp lại (sau CRUD) → coverage tự phản chiếu thay đổi
  const [refreshKey, setRefreshKey] = useState(0);

  // Modal state
  const [catModal, setCatModal] = useState<{ mode: "create" | "edit"; data: Partial<CategoryT> } | null>(null);
  const [skillModal, setSkillModal] = useState<{ mode: "create" | "edit"; data: Partial<SkillT> } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setErr(null);
    fetch(`${API_BASE}/syllabus?includeInactive=true`)
      // Phải xem res.ok: backend lỗi vẫn trả JSON, cứ parse thẳng thì lỗi 500
      // biến thành "tree rỗng" và trang im lặng hiện trống thay vì báo hỏng.
      .then(async (r) => {
        const d = await r.json().catch(() => ({}));
        if (!r.ok) throw appError(d, r.status);
        return d as TreeT;
      })
      .then((d) => { setTree(d); setRefreshKey((k) => k + 1); })
      .catch((e) => setErr(e))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const categories = tree?.categories || [];

  // ── Thao tác ghi ────────────────────────────────────────────
  const saveCategory = async (body: Partial<CategoryT>, mode: "create" | "edit") => {
    if (mode === "create") await apiJson("/syllabus/categories", "POST", body);
    else await apiJson(`/syllabus/categories/${encodeURIComponent(body.code!)}`, "PUT", body);
    setCatModal(null);
    load();
  };
  const saveSkill = async (body: Partial<SkillT>, mode: "create" | "edit") => {
    if (mode === "create") await apiJson("/syllabus/skills", "POST", body);
    else await apiJson(`/syllabus/skills/${encodeURIComponent(body.code!)}`, "PUT", body);
    setSkillModal(null);
    load();
  };
  const deprecateSkill = async (s: SkillT) => {
    if (!confirm(`Ẩn (deprecate) skill "${s.code}"? Bộ testcase cũ trỏ vào vẫn map được, chỉ ẩn khỏi danh sách chọn.`)) return;
    try { await apiJson(`/syllabus/skills/${encodeURIComponent(s.code)}`, "DELETE"); load(); }
    catch (e) { alert((e as Error).message); }
  };
  const deactivateCategory = async (c: CategoryT) => {
    if (!confirm(`Ẩn category "${c.code}"?`)) return;
    try { await apiJson(`/syllabus/categories/${encodeURIComponent(c.code)}`, "DELETE"); load(); }
    catch (e) { alert((e as Error).message); }
  };

  return (
    <SidebarLayout
      title="Khung năng lực (Syllabus)"
      activePath="/syllabus"
    >
      {/* Thanh tiêu đề + nút thêm category */}
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <BookOpen size={16} className="text-indigo-500" />
          {tree?.meta?.subject?.name || "Môn học"}{" "}
          {tree?.meta?.version && <span className="font-mono text-xs text-slate-400">· v{tree.meta.version}</span>}
        </div>
        <button
          onClick={() => setCatModal({ mode: "create", data: {} })}
          className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3.5 py-2 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95"
        >
          <Plus size={16} /> Thêm category
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20 text-slate-400">
          <Loader2 size={24} className="animate-spin" />
        </div>
      ) : err ? (
        <ErrorScreen kind={kindOf(err)} detail={messageOf(err)} onRetry={load} />
      ) : (
        <div className="space-y-5">
          {categories.map((c) => (
            <div
              key={c.code}
              className={`card overflow-hidden ${c.active ? "" : "opacity-60"}`}
            >
              {/* Header category */}
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/60 px-5 py-4">
                <div className="flex min-w-0 items-center gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-100 text-indigo-600">
                    <Layers size={17} />
                  </span>
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <h3 className="truncate text-sm font-bold text-slate-800">
                        {c.competency_label || c.name}
                      </h3>
                      <span className="rounded bg-slate-200/70 px-1.5 py-0.5 font-mono text-[10px] text-slate-500">{c.code}</span>
                      {!c.active && <span className="rounded bg-slate-200 px-1.5 py-0.5 text-[10px] text-slate-500">đã ẩn</span>}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <button
                    onClick={() => setSkillModal({ mode: "create", data: { category: c.code, default_difficulty: "basic", testable: "auto" } })}
                    className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:text-indigo-600"
                  >
                    <Plus size={13} /> Skill
                  </button>
                  <IconBtn title="Sửa category" onClick={() => setCatModal({ mode: "edit", data: c })}><Pencil size={14} /></IconBtn>
                  <IconBtn title="Ẩn category" tone="rose" onClick={() => deactivateCategory(c)}><EyeOff size={14} /></IconBtn>
                </div>
              </div>

              {/* Danh sách skill */}
              <div className="divide-y divide-slate-50">
                {c.skills.length === 0 ? (
                  <div className="px-5 py-4 text-xs text-slate-400">Chưa có skill nào.</div>
                ) : (
                  c.skills.map((s) => (
                    <div
                      key={s.code}
                      className={`flex items-center gap-3 px-5 py-3 transition-colors hover:bg-slate-50/60 ${s.deprecated ? "opacity-50" : ""}`}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="truncate text-sm font-semibold text-slate-700">{s.name}</span>
                          <span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[10px] text-indigo-600">{s.code}</span>
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{DIFF_LABEL[s.default_difficulty] || s.default_difficulty}</span>
                          {s.testable === "manual" && (
                            <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-700" title="Cần package ngoài/mạng — chấm tay">chấm tay</span>
                          )}
                          {s.deprecated && <span className="rounded bg-rose-100 px-1.5 py-0.5 text-[10px] text-rose-600">deprecated</span>}
                        </div>
                        {/* Phần lớn skill đang để mô tả trùng y hệt tên → dòng phụ chỉ là nhiễu.
                            Chỉ hiện khi mô tả thật sự nói thêm điều gì so với tên. */}
                        {s.description?.trim() && s.description.trim() !== s.name.trim() && (
                          <p className="mt-0.5 line-clamp-1 text-xs text-slate-400">{s.description}</p>
                        )}
                      </div>
                      <div className="flex shrink-0 items-center gap-1.5">
                        <IconBtn title="Sửa skill" onClick={() => setSkillModal({ mode: "edit", data: s })}><Pencil size={13} /></IconBtn>
                        {!s.deprecated && (
                          <IconBtn title="Deprecate skill" tone="rose" onClick={() => deprecateSkill(s)}><Trash2 size={13} /></IconBtn>
                        )}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          ))}
          {categories.length === 0 && (
            <div className="rounded-xl border border-dashed border-slate-200 p-10 text-center text-sm text-slate-400">
              Chưa có category nào. Bấm “Thêm category” để bắt đầu.
            </div>
          )}
        </div>
      )}

      {/* Đánh giá bộ testcase theo syllabus — tự phản chiếu khi syllabus đổi (refreshKey) */}
      <div className="mt-8">
        <SyllabusCoverage refreshKey={refreshKey} />
      </div>

      {catModal && (
        <CategoryModal
          mode={catModal.mode}
          initial={catModal.data}
          onClose={() => setCatModal(null)}
          onSave={saveCategory}
        />
      )}
      {skillModal && (
        <SkillModal
          mode={skillModal.mode}
          initial={skillModal.data}
          categories={categories}
          onClose={() => setSkillModal(null)}
          onSave={saveSkill}
        />
      )}
    </SidebarLayout>
  );
}

// ── Nút icon nhỏ ───────────────────────────────────────────────
function IconBtn({ children, onClick, title, tone = "slate" }: {
  children: React.ReactNode; onClick: () => void; title: string; tone?: "slate" | "rose";
}) {
  const hover = tone === "rose" ? "hover:bg-rose-50 hover:text-rose-600" : "hover:bg-indigo-50 hover:text-indigo-600";
  return (
    <button title={title} onClick={onClick}
      className={`flex h-7 w-7 items-center justify-center rounded-lg text-slate-400 transition-colors ${hover}`}>
      {children}
    </button>
  );
}

// ── Khung modal chung ──────────────────────────────────────────
// Render qua Portal ra document.body để KHÔNG bị "giam" trong khung max-w-6xl
// (khung này có animate-fade-in-up → transform → tạo containing block cho position:fixed).
function ModalShell({ title, icon, onClose, children }: {
  title: string; icon: React.ReactNode; onClose: () => void; children: React.ReactNode;
}) {
  // Khóa cuộn trang nền khi mở modal
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, []);

  if (typeof document === "undefined") return null;
  return createPortal(
    <div className="animate-modal-overlay fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm" onClick={onClose}>
      <div className="animate-modal-pop flex max-h-[90vh] w-full max-w-lg flex-col overflow-hidden rounded-2xl bg-white shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex shrink-0 items-center justify-between border-b border-slate-100 px-6 py-4">
          <h3 className="flex items-center gap-2 text-sm font-bold text-slate-800">{icon}{title}</h3>
          <button onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 hover:bg-slate-100 hover:text-slate-700"><X size={18} /></button>
        </div>
        <div className="custom-scrollbar flex-1 overflow-y-auto p-6">{children}</div>
      </div>
    </div>,
    document.body
  );
}

function Field({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold text-slate-600">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[11px] text-slate-400">{hint}</span>}
    </label>
  );
}

const inputCls =
  "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100";

// ── Modal Category ─────────────────────────────────────────────
function CategoryModal({ mode, initial, onClose, onSave }: {
  mode: "create" | "edit"; initial: Partial<CategoryT>;
  onClose: () => void; onSave: (b: Partial<CategoryT>, m: "create" | "edit") => Promise<void>;
}) {
  const [f, setF] = useState<Partial<CategoryT>>({
    weak_threshold: 0.4, good_threshold: 0.7, ...initial,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof CategoryT, v: unknown) => setF((p) => ({ ...p, [k]: v }));

  const submit = async () => {
    setSaving(true); setError(null);
    try { await onSave(f, mode); }
    catch (e) { setError((e as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <ModalShell title={mode === "create" ? "Thêm category" : "Sửa category"} icon={<Layers size={16} className="text-indigo-500" />} onClose={onClose}>
      <div className="space-y-4">
        <Field label="Mã code (ID ổn định, KHÔNG đổi sau khi tạo)" hint="VD: DART, UI, VALIDATION">
          <input className={inputCls} value={f.code || ""} disabled={mode === "edit"}
            onChange={(e) => set("code", e.target.value.toUpperCase())} placeholder="DART" />
        </Field>
        <Field label="Tên đầy đủ">
          <input className={inputCls} value={f.name || ""} onChange={(e) => set("name", e.target.value)} placeholder="Lập trình Dart" />
        </Field>
        <Field label="Nhãn năng lực (hiển thị khi đánh giá)">
          <input className={inputCls} value={f.competency_label || ""} onChange={(e) => set("competency_label", e.target.value)} placeholder="Code Dart" />
        </Field>
        <Field label="Mô tả">
          <textarea className={inputCls} rows={2} value={f.description || ""} onChange={(e) => set("description", e.target.value)} />
        </Field>
        <div className="grid grid-cols-3 gap-3">
          <Field label="Thứ tự"><input type="number" className={inputCls} value={f.display_order ?? 0} onChange={(e) => set("display_order", Number(e.target.value))} /></Field>
          <Field label="Ngưỡng YẾU <" hint="0–1"><input type="number" step="0.05" className={inputCls} value={f.weak_threshold ?? 0.4} onChange={(e) => set("weak_threshold", Number(e.target.value))} /></Field>
          <Field label="Ngưỡng TỐT ≥" hint="0–1"><input type="number" step="0.05" className={inputCls} value={f.good_threshold ?? 0.7} onChange={(e) => set("good_threshold", Number(e.target.value))} /></Field>
        </div>
        {error && <p className="text-xs text-rose-500">{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <button onClick={onClose} className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">Hủy</button>
          <button onClick={submit} disabled={saving}
            className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">
            {saving && <Loader2 size={14} className="animate-spin" />} Lưu
          </button>
        </div>
      </div>
    </ModalShell>
  );
}

// ── Modal Skill ────────────────────────────────────────────────
function SkillModal({ mode, initial, categories, onClose, onSave }: {
  mode: "create" | "edit"; initial: Partial<SkillT>; categories: CategoryT[];
  onClose: () => void; onSave: (b: Partial<SkillT>, m: "create" | "edit") => Promise<void>;
}) {
  const [f, setF] = useState<Partial<SkillT> & { resourcesText?: string }>({
    default_difficulty: "basic", testable: "auto", ...initial,
    resourcesText: (initial.resources || []).join("\n"),
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: string, v: unknown) => setF((p) => ({ ...p, [k]: v }));

  const submit = async () => {
    setSaving(true); setError(null);
    const body: Partial<SkillT> = {
      code: f.code, category: f.category, name: f.name, description: f.description,
      default_difficulty: f.default_difficulty, testable: f.testable,
      resources: (f.resourcesText || "").split("\n").map((s) => s.trim()).filter(Boolean),
      deprecated: f.deprecated,
    };
    try { await onSave(body, mode); }
    catch (e) { setError((e as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <ModalShell title={mode === "create" ? "Thêm skill" : "Sửa skill"} icon={<GraduationCap size={16} className="text-indigo-500" />} onClose={onClose}>
      <div className="space-y-4">
        <Field label="Mã code (ID ổn định, KHÔNG đổi)" hint="VD: DART_LOGIC, UI_BASIC">
          <input className={inputCls} value={f.code || ""} disabled={mode === "edit"}
            onChange={(e) => set("code", e.target.value.toUpperCase())} placeholder="DART_LOGIC" />
        </Field>
        <Field label="Thuộc category">
          <select className={inputCls} value={f.category || ""} onChange={(e) => set("category", e.target.value)}>
            <option value="">— chọn category —</option>
            {categories.map((c) => <option key={c.code} value={c.code}>{c.competency_label || c.name} ({c.code})</option>)}
          </select>
        </Field>
        <Field label="Tên skill">
          <input className={inputCls} value={f.name || ""} onChange={(e) => set("name", e.target.value)} placeholder="Logic & hàm thuần" />
        </Field>
        <Field label="Mô tả">
          <textarea className={inputCls} rows={2} value={f.description || ""} onChange={(e) => set("description", e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Độ khó mặc định">
            <select className={inputCls} value={f.default_difficulty || "basic"} onChange={(e) => set("default_difficulty", e.target.value)}>
              {DIFFS.map((d) => <option key={d} value={d}>{DIFF_LABEL[d]}</option>)}
            </select>
          </Field>
          <Field label="Chấm" hint="manual = cần package ngoài/mạng">
            <select className={inputCls} value={f.testable || "auto"} onChange={(e) => set("testable", e.target.value)}>
              <option value="auto">auto (tự động)</option>
              <option value="manual">manual (chấm tay)</option>
            </select>
          </Field>
        </div>
        <Field label="Học liệu (mỗi dòng 1 link/slide)">
          <textarea className={inputCls} rows={3} value={f.resourcesText || ""} onChange={(e) => set("resourcesText", e.target.value)} placeholder={"dart.dev/codelabs/iterables\nSlide buổi 3"} />
        </Field>
        {error && <p className="flex items-center gap-1.5 text-xs text-rose-500"><AlertTriangle size={13} />{error}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <button onClick={onClose} className="rounded-lg px-4 py-2 text-sm font-semibold text-slate-500 hover:bg-slate-100">Hủy</button>
          <button onClick={submit} disabled={saving}
            className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">
            {saving && <Loader2 size={14} className="animate-spin" />} Lưu
          </button>
        </div>
      </div>
    </ModalShell>
  );
}
