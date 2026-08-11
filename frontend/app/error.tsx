"use client";

import React, { useEffect } from "react";
import ErrorScreen from "@/components/ui/ErrorScreen";
import ThemeToggle from "@/components/layout/ThemeToggle";
import { classifyError } from "@/lib/errors";

/**
 * Error boundary cấp route: một trang/component ném lỗi lúc RENDER thì hiện màn này
 * thay vì trắng màn. Không bắt lỗi trong event handler hay code async — chỗ đó phải
 * tự bắt rồi hiện <ErrorScreen variant="inline">.
 *
 * Next 16.2 đổi cách thử lại: `unstable_retry` nạp LẠI DỮ LIỆU rồi render lại, còn
 * `reset` cũ chỉ dựng lại cây component với dữ liệu hỏng sẵn có. Nhận cả hai để
 * không phụ thuộc phiên bản.
 */
export default function RouteError({
  error,
  unstable_retry,
  reset,
}: {
  error: Error & { digest?: string };
  unstable_retry?: () => void;
  reset?: () => void;
}) {
  useEffect(() => {
    console.error("Lỗi trang:", error);
  }, [error]);

  const retry = unstable_retry ?? reset;
  const detail = [error.message, error.digest && `digest: ${error.digest}`]
    .filter(Boolean)
    .join("\n");

  return (
    <>
      <div className="fixed right-4 top-4 z-50">
        <ThemeToggle />
      </div>
      <ErrorScreen
        kind={classifyError(error)}
        detail={detail || null}
        onRetry={retry}
        variant="page"
      />
    </>
  );
}
