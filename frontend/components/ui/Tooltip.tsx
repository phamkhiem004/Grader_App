"use client";

import React from "react";

type Side = "top" | "bottom" | "left" | "right";

/** Tooltip thuần CSS (hover) — không phụ thuộc thư viện ngoài. */
export function Tooltip({
  children,
  label,
  side = "top",
}: {
  children: React.ReactNode;
  label: string;
  side?: Side;
}) {
  const pos: Record<Side, string> = {
    top: "bottom-full left-1/2 -translate-x-1/2 mb-2",
    bottom: "top-full left-1/2 -translate-x-1/2 mt-2",
    left: "right-full top-1/2 -translate-y-1/2 mr-2",
    right: "left-full top-1/2 -translate-y-1/2 ml-2",
  };
  return (
    <span className="group/tt relative inline-flex">
      {children}
      <span
        role="tooltip"
        className={`pointer-events-none absolute z-50 whitespace-nowrap rounded-md bg-slate-900 px-2 py-1 text-[11px] font-medium text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover/tt:opacity-100 ${pos[side]}`}
      >
        {label}
      </span>
    </span>
  );
}
