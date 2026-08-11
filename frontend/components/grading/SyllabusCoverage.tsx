"use client";

import React, { useEffect, useState, useCallback } from "react";
import { API_BASE } from "@/lib/config";
import {
  Target, Loader2, AlertTriangle, RefreshCw, PieChart,
} from "lucide-react";

interface ExamItem { examId: string; examName?: string | null; status?: string; hasTestcase: boolean; }
interface CovTestcase {
  test_id: string; skill_code?: string | null; skill_name?: string | null;
  category?: string | null; category_label?: string | null;
  difficulty: string; weight: number; status: string;
}
interface CovCategory { category: string; label: string; test_count: number; weight: number; percent: number; skills_covered: string[]; }
interface CovGap { category: string; label: string; missing_skills: { code: string; name: string }[]; }
interface CovIssue { testId: string; skillCode?: string | null; issue: string; }
interface Coverage {
  summary: { total_testcases: number; total_weight: number; categories_covered: number; categories_total: number; issues: number };
  by_category: CovCategory[];
  by_difficulty: Record<string, number>;
  gaps: CovGap[];
  issues: CovIssue[];
  testcases: CovTestcase[];
  error?: string;
}

const DIFF_BADGE: Record<string, string> = {
  basic: "bg-slate-100 text-slate-500",
  intermediate: "bg-amber-100 text-amber-700",
  advanced: "bg-rose-100 text-rose-700",
};
const DIFF_VI: Record<string, string> = { basic: "Cơ bản", intermediate: "Trung bình", advanced: "Nâng cao" };
const STATUS_BADGE: Record<string, { cls: string; text: string }> = {
  ok:         { cls: "bg-emerald-100 text-emerald-700", text: "OK" },
  aliased:    { cls: "bg-amber-100 text-amber-700",     text: "code cũ" },
  inferred:   { cls: "bg-slate-100 text-slate-500",     text: "suy luận" },
  deprecated: { cls: "bg-rose-100 text-rose-700",       text: "deprecated" },
  unknown:    { cls: "bg-rose-100 text-rose-700",       text: "không rõ" },
  unmapped:   { cls: "bg-slate-100 text-slate-500",     text: "chưa map" },
};

/** Đánh giá ĐỘ PHỦ của 1 đề theo syllabus. `refreshKey` đổi → tự nạp lại (phản chiếu syllabus mới). */
export default function SyllabusCoverage({ refreshKey = 0 }: { refreshKey?: number }) {
  const [exams, setExams] = useState<ExamItem[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [cov, setCov] = useState<Coverage | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Nạp danh sách bộ testcase đã cấu hình
  useEffect(() => {
    fetch(`${API_BASE}/exam-setup/list`)
      .then((r) => r.json())
      .then((d: ExamItem[]) => {
        const list = Array.isArray(d) ? d.filter((e) => e.hasTestcase) : [];
        setExams(list);
        setSelected((prev) => prev || (list[0]?.examId ?? ""));
      })
      .catch(() => setExams([]));
  }, []);

  const loadCoverage = useCallback(() => {
    if (!selected) { setCov(null); return; }
    setLoading(true);
    setErr(null);
    fetch(`${API_BASE}/exam-setup/coverage/${encodeURIComponent(selected)}`)
      .then(async (r) => {
        const d = await r.json();
        if (!r.ok) throw new Error(d.error || "Lỗi tải coverage");
        return d as Coverage;
      })
      .then((d) => setCov(d))
      .catch((e) => { setErr((e as Error).message); setCov(null); })
      .finally(() => setLoading(false));
  }, [selected]);

  // Nạp lại khi đổi đề HOẶC khi syllabus thay đổi (refreshKey)
  useEffect(() => { loadCoverage(); }, [loadCoverage, refreshKey]);

  return (
    <div className="card overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/60 px-5 py-4">
        <div className="flex items-center gap-2">
          <Target size={16} className="text-indigo-500" />
          <h3 className="text-sm font-bold text-slate-700">Đánh giá bộ testcase theo syllabus</h3>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 outline-none focus:border-indigo-400"
          >
            {exams.length === 0 && <option value="">— chưa có bộ testcase —</option>}
            {exams.map((e) => (
              <option key={e.examId} value={e.examId}>
                {e.examName ? `${e.examId} — ${e.examName}` : e.examId}
              </option>
            ))}
          </select>
          <button onClick={loadCoverage} title="Làm mới" className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-indigo-50 hover:text-indigo-600">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      <div className="p-5">
        {loading ? (
          <div className="flex items-center justify-center py-10 text-slate-400"><Loader2 size={20} className="animate-spin" /></div>
        ) : err ? (
          <div className="rounded-lg border border-rose-100 bg-rose-50 p-4 text-center text-sm text-rose-600">{err}</div>
        ) : !cov || cov.error ? (
          <div className="py-8 text-center text-sm text-slate-400">{cov?.error || "Chọn một bộ testcase đã cấu hình để xem độ phủ."}</div>
        ) : (
          <div className="space-y-5">
            {/* Tóm tắt */}
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Stat label="Testcase" value={cov.summary.total_testcases} />
              <Stat label="Tổng điểm thô" value={cov.summary.total_weight} />
              <Stat label="Category phủ" value={`${cov.summary.categories_covered}/${cov.summary.categories_total}`} />
              <Stat label="Cảnh báo" value={cov.summary.issues} tone={cov.summary.issues > 0 ? "rose" : "emerald"} />
            </div>

            {/* Độ phủ theo category */}
            <div>
              <p className="mb-2 flex items-center gap-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
                <PieChart size={13} /> Phân bổ theo mảng kiến thức
              </p>
              <div className="space-y-2">
                {cov.by_category.map((c) => (
                  <div key={c.category} className="flex items-center gap-3">
                    <div className="w-40 shrink-0 truncate text-xs font-semibold text-slate-700" title={c.label}>{c.label}</div>
                    <div className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100">
                      <div className={`h-full rounded-full ${c.category === "OTHER" ? "bg-slate-400" : "bg-indigo-500"}`} style={{ width: `${c.percent}%` }} />
                    </div>
                    <div className="w-28 shrink-0 text-right text-[11px] text-slate-500">
                      {c.percent}% · {c.test_count} tc
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Phân bổ theo độ khó */}
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Độ khó:</span>
              {["basic", "intermediate", "advanced"].map((d) => (
                <span key={d} className={`rounded-md px-2 py-0.5 text-[11px] font-medium ${DIFF_BADGE[d]}`}>
                  {DIFF_VI[d]}: <span className="font-bold">{cov.by_difficulty?.[d] ?? 0}</span>
                </span>
              ))}
            </div>

            {/* Cảnh báo */}
            {cov.issues.length > 0 && (
              <div className="rounded-lg border border-amber-100 bg-amber-50 p-3">
                <p className="mb-1.5 flex items-center gap-1.5 text-xs font-bold text-amber-700">
                  <AlertTriangle size={13} /> Cảnh báo ({cov.issues.length})
                </p>
                <ul className="space-y-1 text-[11px] text-amber-700/90">
                  {cov.issues.map((i, idx) => (
                    <li key={idx}><span className="font-mono font-semibold">{i.testId}</span>{i.skillCode ? ` (${i.skillCode})` : ""} — {i.issue}</li>
                  ))}
                </ul>
              </div>
            )}

            {/* Skill chưa được phủ (gaps) */}
            {cov.gaps.length > 0 && (
              <details className="rounded-lg border border-slate-100 bg-slate-50/60 p-3">
                <summary className="cursor-pointer text-xs font-bold text-slate-600">
                  Kỹ năng (auto) CHƯA được bộ testcase phủ — {cov.gaps.reduce((s, g) => s + g.missing_skills.length, 0)} skill
                </summary>
                <div className="mt-2 space-y-2">
                  {cov.gaps.map((g) => (
                    <div key={g.category}>
                      <p className="text-[11px] font-semibold text-slate-500">{g.label}</p>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {g.missing_skills.map((s) => (
                          <span key={s.code} className="rounded bg-white px-1.5 py-0.5 font-mono text-[10px] text-slate-400 ring-1 ring-slate-200" title={s.name}>{s.code}</span>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </details>
            )}

            {/* Bảng testcase */}
            <details open className="rounded-lg border border-slate-100">
              <summary className="cursor-pointer bg-slate-50/60 px-3 py-2 text-xs font-bold text-slate-600">
                Chi tiết {cov.testcases.length} testcase
              </summary>
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-left text-xs">
                  <thead className="sticky top-0 bg-white">
                    <tr className="border-b border-slate-100 text-[10px] uppercase tracking-wider text-slate-400">
                      <th className="px-3 py-2">Test</th>
                      <th className="px-3 py-2">Kiến thức</th>
                      <th className="px-3 py-2">Độ khó</th>
                      <th className="px-3 py-2 text-right">Điểm</th>
                      <th className="px-3 py-2 text-center">Map</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-50">
                    {cov.testcases.map((t) => {
                      const st = STATUS_BADGE[t.status] || STATUS_BADGE.unmapped;
                      return (
                        <tr key={t.test_id} className="hover:bg-slate-50/60">
                          <td className="px-3 py-2 font-mono text-[11px] text-slate-500">{t.test_id}</td>
                          <td className="px-3 py-2">
                            <span className="font-medium text-slate-700">{t.skill_name || t.skill_code || "—"}</span>
                            {t.category_label && <span className="ml-1 text-[10px] text-slate-400">· {t.category_label}</span>}
                          </td>
                          <td className="px-3 py-2">
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${DIFF_BADGE[t.difficulty] || DIFF_BADGE.basic}`}>
                              {DIFF_VI[t.difficulty] || t.difficulty}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-right font-semibold text-slate-600">{t.weight}</td>
                          <td className="px-3 py-2 text-center">
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${st.cls}`}>{st.text}</span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </details>
          </div>
        )}
      </div>
    </div>
  );
}

function Stat({ label, value, tone = "slate" }: { label: string; value: number | string; tone?: "slate" | "rose" | "emerald" }) {
  const valueCls = tone === "rose" ? "text-rose-600" : tone === "emerald" ? "text-emerald-600" : "text-slate-800";
  return (
    <div className="rounded-xl border border-slate-100 bg-white p-3">
      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p>
      <p className={`mt-1 text-xl font-bold ${valueCls}`}>{value}</p>
    </div>
  );
}
