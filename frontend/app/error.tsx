"use client";

import React, { useEffect } from "react";
import { AlertTriangle, RotateCcw } from "lucide-react";

/**
 * Error Boundary cấp route (Next.js App Router). Khi 1 trang/ component ném lỗi
 * lúc render, hiển thị màn hình này thay vì "trắng màn", kèm nút thử lại.
 */
export default function GlobalRouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Ghi log ra console để dễ debug
    console.error("Lỗi trang:", error);
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-rose-50 text-rose-500">
          <AlertTriangle size={28} />
        </div>
        <h2 className="mb-1 text-lg font-bold text-slate-800">Đã xảy ra lỗi</h2>
        <p className="mb-6 text-sm text-slate-500">
          Trang gặp sự cố khi hiển thị. Bạn có thể thử lại — dữ liệu đã chấm vẫn được giữ trên máy chủ.
        </p>
        <button
          onClick={reset}
          className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-indigo-700 active:scale-95"
        >
          <RotateCcw size={16} /> Thử lại
        </button>
      </div>
    </div>
  );
}
