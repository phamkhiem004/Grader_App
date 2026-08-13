"use client";

import React from "react";
import { AlertTriangle, CheckCircle2, Info, X } from "lucide-react";

/**
 * Băng thông báo kết quả một thao tác (xóa bộ testcase, chấm lại, lưu cấu hình...).
 *
 * Luôn có nút tắt: các thông báo này không tự biến mất, để trên màn mãi thì che mất
 * nội dung và người dùng không có cách nào dọn đi ngoài việc tải lại trang.
 */

type Tone = "ok" | "error" | "info";

const TONE: Record<Tone, { box: string; close: string; Icon: React.ElementType }> = {
  ok: {
    box: "border-emerald-100 bg-emerald-50 text-emerald-700",
    close: "hover:bg-emerald-100 hover:text-emerald-900",
    Icon: CheckCircle2,
  },
  error: {
    box: "border-rose-100 bg-rose-50 text-rose-600",
    close: "hover:bg-rose-100 hover:text-rose-800",
    Icon: AlertTriangle,
  },
  info: {
    box: "border-indigo-100 bg-indigo-50 text-indigo-700",
    close: "hover:bg-indigo-100 hover:text-indigo-900",
    Icon: Info,
  },
};

export interface BannerProps {
  tone?: Tone;
  children: React.ReactNode;
  /** Bỏ trống thì không hiện nút tắt (dùng cho băng trạng thái tự mất khi xong việc). */
  onClose?: () => void;
  className?: string;
}

export default function Banner({ tone = "info", children, onClose, className = "" }: BannerProps) {
  const t = TONE[tone];
  return (
    <div className={`mb-4 flex items-start gap-2 rounded-lg border p-3 text-sm ${t.box} ${className}`}>
      <t.Icon size={15} className="mt-0.5 shrink-0" />
      <div className="min-w-0 flex-1 leading-relaxed">{children}</div>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="Đóng thông báo"
          title="Đóng thông báo"
          className={`-my-0.5 -mr-1 flex h-6 w-6 shrink-0 items-center justify-center rounded-md transition-colors ${t.close}`}
        >
          <X size={14} />
        </button>
      )}
    </div>
  );
}
