/**
 * NHÃN TRẠNG THÁI CHẤM — nguồn sự thật duy nhất cho mọi trang.
 *
 * Trước đây mỗi trang tự viết chuỗi ternary riêng nên cùng một bản ghi hiện hai kiểu: trang Chấm
 * tự động ghi "Chờ"/"Lỗi hệ thống", trang Lịch sử ghi "Đang chờ"/"Cần chấm tay", và các trạng thái
 * không nằm trong ternary (QUEUED, GRADING, CANCELLED) rơi thẳng ra tên enum tiếng Anh cho người
 * dùng đọc. Gom về đây để thêm/đổi một trạng thái là mọi trang đổi theo.
 *
 * Ưu tiên `outcome` (kết luận backend phát hành) hơn `status`: đó là thứ người chấm cần biết —
 * có điểm hay không — còn `status` chỉ dùng để tách PENDING thành "đang chờ" và "đang chấm".
 */

export type GradingOutcome = "PENDING" | "SCORED" | "SYSTEM_BLOCKED" | "STOPPED";

export type GradingStatusName =
  | "QUEUED" | "GRADING" | "DONE" | "ERROR" | "MANUAL_REVIEW" | "CANCELLED";

const LABEL_BY_OUTCOME: Record<string, string> = {
  SCORED: "Đã xong",
  SYSTEM_BLOCKED: "Lỗi hệ thống",
  STOPPED: "Đã dừng",
};

// Dùng khi bản ghi chưa mang `outcome` (dữ liệu cũ, hoặc endpoint chưa trả trường này).
const LABEL_BY_STATUS: Record<string, string> = {
  QUEUED: "Đang chờ",
  GRADING: "Đang chấm",
  DONE: "Đã xong",
  ERROR: "Lỗi hệ thống",
  MANUAL_REVIEW: "Lỗi hệ thống",
  CANCELLED: "Đã dừng",
};

export function gradingStatusLabel(status?: string | null, outcome?: string | null): string {
  if (outcome && outcome !== "PENDING") {
    const byOutcome = LABEL_BY_OUTCOME[outcome];
    if (byOutcome) return byOutcome;
  }
  const key = String(status ?? "");
  // Không còn đường nào để tên enum lọt ra màn hình: không tra được thì trả gạch ngang.
  return LABEL_BY_STATUS[key] ?? "—";
}

type Tone = { pill: string; dot: string };

const TONES: Record<string, Tone> = {
  done:     { pill: "bg-emerald-100 text-emerald-700", dot: "bg-emerald-500" },
  blocked:  { pill: "bg-amber-100 text-amber-800",     dot: "bg-amber-500" },
  grading:  { pill: "bg-blue-100 text-blue-700",       dot: "bg-blue-500 animate-pulse" },
  idle:     { pill: "bg-slate-100 text-slate-600",     dot: "bg-slate-400" },
};

export function gradingStatusTone(status?: string | null, outcome?: string | null): Tone {
  const label = gradingStatusLabel(status, outcome);
  if (label === "Đã xong") return TONES.done;
  if (label === "Lỗi hệ thống") return TONES.blocked;
  if (status === "GRADING") return TONES.grading;
  return TONES.idle;
}
