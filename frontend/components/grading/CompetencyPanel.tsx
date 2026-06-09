"use client";

import React from "react";
import { GraduationCap, AlertTriangle } from "lucide-react";

/** Một dòng đánh giá năng lực theo category (khớp competency_assessment[] từ backend). */
export interface CompetencyItem {
  category: string;
  label: string;
  passed_weight: number;
  total_weight: number;
  ratio: number;
  level: string; // "YẾU" | "TRUNG BÌNH" | "TỐT"
  passed_tests?: number;
  total_tests?: number;
  by_difficulty?: Record<string, string>;
  weak_skills?: string[];
}

const LEVEL_STYLE: Record<string, { bar: string; badge: string; dot: string }> = {
  "TỐT":        { bar: "bg-emerald-500", badge: "bg-emerald-100 text-emerald-700", dot: "bg-emerald-500" },
  "TRUNG BÌNH": { bar: "bg-amber-500",   badge: "bg-amber-100 text-amber-700",     dot: "bg-amber-500" },
  "YẾU":        { bar: "bg-rose-500",    badge: "bg-rose-100 text-rose-700",       dot: "bg-rose-500" },
};

const DIFF_LABEL: Record<string, string> = {
  basic: "Cơ bản",
  intermediate: "Trung bình",
  advanced: "Nâng cao",
};

export default function CompetencyPanel({ items }: { items?: CompetencyItem[] }) {
  if (!items || items.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-400">
        Bài này chưa có dữ liệu đánh giá năng lực.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-sm font-bold text-slate-700">
        <GraduationCap size={16} className="text-indigo-500" />
        Đánh giá năng lực theo mảng kiến thức
      </div>

      {items.map((it) => {
        const style = LEVEL_STYLE[it.level] || LEVEL_STYLE["TRUNG BÌNH"];
        const pct = Math.round((it.ratio || 0) * 100);
        return (
          <div key={it.category} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <div className="mb-2 flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-bold text-slate-800">{it.label}</p>
                <p className="font-mono text-[11px] text-slate-400">{it.category}</p>
              </div>
              <span className={`inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-bold ${style.badge}`}>
                <span className={`h-1.5 w-1.5 rounded-full ${style.dot}`} />
                {it.level}
              </span>
            </div>

            {/* Thanh tỉ lệ pass */}
            <div className="flex items-center gap-3">
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100">
                <div className={`h-full rounded-full ${style.bar}`} style={{ width: `${pct}%` }} />
              </div>
              <span className="w-28 shrink-0 text-right text-xs font-semibold text-slate-500">
                {pct}% · {it.passed_tests ?? fmt(it.passed_weight)}/{it.total_tests ?? fmt(it.total_weight)} testcase
              </span>
            </div>

            {/* Theo độ khó */}
            {it.by_difficulty && Object.keys(it.by_difficulty).length > 0 && (
              <div className="mt-3 flex flex-wrap gap-1.5">
                {Object.entries(it.by_difficulty).map(([diff, val]) => (
                  <span
                    key={diff}
                    className="rounded-md bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600"
                    title={`${DIFF_LABEL[diff] || diff}: ${val} testcase pass`}
                  >
                    {DIFF_LABEL[diff] || diff}: <span className="font-bold">{val}</span>
                  </span>
                ))}
              </div>
            )}

            {/* Kỹ năng còn yếu */}
            {it.weak_skills && it.weak_skills.length > 0 && (
              <div className="mt-2 flex items-start gap-1.5 text-[11px] text-rose-500">
                <AlertTriangle size={13} className="mt-0.5 shrink-0" />
                <span>
                  Cần bổ sung:{" "}
                  <span className="font-semibold">{it.weak_skills.join(", ")}</span>
                </span>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function fmt(n: number): string {
  if (n == null || isNaN(n)) return "0";
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/\.?0+$/, "");
}
