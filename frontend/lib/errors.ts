// frontend/lib/errors.ts
// Bảng ánh xạ DUY NHẤT "gặp lỗi gì → hiện màn nào". Gom về một chỗ để cùng một
// sự cố luôn ra cùng một thông điệp, dù nó xảy ra ở trang nào.

export type ErrorKind =
  | "offline"    // không gọi được backend (chưa chạy / sai cổng)
  | "notFound"   // 404 — không có route hoặc không có dữ liệu
  | "invalid"    // 400/422 — dữ liệu gửi lên sai
  | "conflict"   // 409 — xung đột trạng thái (mã đề trùng, đang build dở...)
  | "docker"     // Docker chưa bật / thiếu image grading-base
  | "server"     // 5xx — backend ném lỗi
  | "unknown";   // ngoài dự kiến

export interface ErrorCopy {
  title: string;
  message: string;
  /** Việc cụ thể người dùng làm được để thoát lỗi. Bỏ trống khi không có cách nào rõ ràng. */
  hint?: string;
}

const COPY: Record<ErrorKind, ErrorCopy> = {
  offline: {
    title: "Không kết nối được máy chủ",
    message:
      "Trình duyệt không gọi được backend. Thường là backend chưa chạy, hoặc đang chạy ở cổng khác với cấu hình của frontend.",
    hint: "Chạy run.cmd ở thư mục Grader_App, đợi backend báo \"Started GraderApplication\" rồi thử lại.",
  },
  notFound: {
    title: "Không tìm thấy",
    message:
      "Trang hoặc dữ liệu bạn mở không tồn tại. Có thể đề/bài nộp đã bị xóa, hoặc đường dẫn gõ sai.",
  },
  invalid: {
    title: "Dữ liệu không hợp lệ",
    message:
      "Máy chủ từ chối yêu cầu vì dữ liệu gửi lên chưa đúng. Sửa theo mô tả bên dưới rồi gửi lại.",
  },
  conflict: {
    title: "Đang bận hoặc bị trùng",
    message:
      "Thao tác xung đột với trạng thái hiện tại — ví dụ mã đề đã tồn tại, hoặc môi trường chấm đang build dở.",
    hint: "Đợi việc đang chạy kết thúc rồi thử lại.",
  },
  docker: {
    title: "Môi trường chấm chưa sẵn sàng",
    message:
      "Không chấm được vì Docker chưa bật hoặc thiếu image grading-base:latest.",
    hint: "Bật Docker Desktop. Nếu thiếu image thì chạy grader-base/build-base.ps1 (lâu, phải tải Flutter SDK).",
  },
  server: {
    title: "Máy chủ gặp lỗi",
    message:
      "Backend ném lỗi khi xử lý yêu cầu. Kết quả đã chấm trước đó vẫn còn nguyên trong cơ sở dữ liệu.",
    hint: "Xem log ở cửa sổ backend để biết nguyên nhân.",
  },
  unknown: {
    title: "Đã xảy ra lỗi",
    message:
      "Có sự cố ngoài dự kiến. Thử lại một lần; nếu vẫn lỗi thì mở phần chi tiết kỹ thuật bên dưới.",
  },
};

export function errorCopy(kind: ErrorKind): ErrorCopy {
  return COPY[kind];
}

// Mất kết nối và lỗi Docker nhận ra qua NỘI DUNG chứ không qua mã HTTP: fetch hỏng
// thì không có status nào cả, còn lỗi Docker về dưới dạng 500 kèm thông điệp.
const OFFLINE_RE = /failed to fetch|networkerror|load failed|fetch failed|err_connection|econnrefused/i;
const DOCKER_RE = /docker|grading-base/i;

/** Rút thông điệp an toàn từ bất cứ thứ gì bị ném ra hoặc nhận về từ API. */
export function messageOf(input: unknown): string {
  if (input == null) return "";
  if (typeof input === "string") return input;
  if (input instanceof Error) return input.message;
  if (typeof input === "object") {
    const body = input as Record<string, unknown>;
    if (typeof body.error === "string") return body.error;
    if (typeof body.message === "string") return body.message;
  }
  return String(input);
}

/**
 * Quy một lỗi bất kỳ về đúng một loại màn hình.
 *
 * @param input  Error bị ném, chuỗi thông điệp, hoặc body JSON {error} của backend
 * @param status Mã HTTP nếu có (khi res.ok === false)
 */
export function classifyError(input?: unknown, status?: number): ErrorKind {
  const msg = messageOf(input);
  if (input instanceof TypeError || OFFLINE_RE.test(msg)) return "offline";
  if (DOCKER_RE.test(msg)) return "docker";
  if (status) {
    if (status === 404) return "notFound";
    if (status === 409) return "conflict";
    if (status === 400 || status === 422) return "invalid";
    if (status >= 500) return "server";
  }
  return "unknown";
}

/** Error đã gắn sẵn loại màn — trang bắt được là hiện đúng màn, không phải đoán lại. */
export interface AppError extends Error {
  kind: ErrorKind;
  status?: number;
}

export function appError(input: unknown, status?: number): AppError {
  const kind = classifyError(input, status);
  const err = new Error(messageOf(input) || errorCopy(kind).message) as AppError;
  err.kind = kind;
  if (status) err.status = status;
  return err;
}

/** Loại màn của một lỗi đã bắt được; nhận cả AppError lẫn Error thường. */
export function kindOf(input: unknown): ErrorKind {
  if (input && typeof input === "object" && "kind" in input) {
    return (input as AppError).kind;
  }
  return classifyError(input);
}
