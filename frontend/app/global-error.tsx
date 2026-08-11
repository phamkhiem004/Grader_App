"use client";

// global-error THAY THẾ root layout khi bung ra, nên phải tự khai <html>/<body>
// và tự nạp global styles — không thừa hưởng gì từ app/layout.tsx.
import "./globals.css";
import ErrorScreen from "@/components/ui/ErrorScreen";

/**
 * Lưới an toàn cuối cùng: lỗi ném từ chính root layout (app/error.tsx nằm BÊN TRONG
 * layout nên không bắt được). Hiếm khi thấy, nhưng thiếu nó thì người dùng nhận màn trắng.
 *
 * Là Client Component nên không export được `metadata` — đặt tiêu đề bằng thẻ <title>.
 */
export default function GlobalError({
  error,
  unstable_retry,
  reset,
}: {
  error: Error & { digest?: string };
  unstable_retry?: () => void;
  reset?: () => void;
}) {
  const retry = unstable_retry ?? reset;
  const detail = [error?.message, error?.digest && `digest: ${error.digest}`]
    .filter(Boolean)
    .join("\n");

  return (
    <html lang="vi">
      <body className="min-h-full">
        <title>Lỗi nghiêm trọng — Grader</title>
        <ErrorScreen
          kind="unknown"
          title="Ứng dụng không khởi động được"
          message="Lỗi xảy ra ở khung ngoài cùng của app nên toàn bộ giao diện dừng lại. Tải lại trang thường là đủ; nếu lặp lại thì khởi động lại frontend."
          detail={detail || null}
          onRetry={retry}
          variant="page"
        />
      </body>
    </html>
  );
}
