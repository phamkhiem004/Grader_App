import React from "react";

/** Khối skeleton nhấp nháy — dùng làm placeholder khi đang tải dữ liệu. */
export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-slate-200/80 ${className}`} />;
}

/** Vài dòng skeleton cho 1 hàng danh sách (avatar + 2 dòng chữ). */
export function SkeletonRow() {
  return (
    <div className="flex items-center gap-3 p-3">
      <Skeleton className="h-9 w-9 shrink-0 rounded-full" />
      <div className="flex-1 space-y-2">
        <Skeleton className="h-3.5 w-2/3" />
        <Skeleton className="h-3 w-1/3" />
      </div>
    </div>
  );
}

/** Khối skeleton dạng thẻ thống kê. */
export function SkeletonCard() {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <Skeleton className="mb-3 h-3 w-1/2" />
      <Skeleton className="h-8 w-1/3" />
    </div>
  );
}
