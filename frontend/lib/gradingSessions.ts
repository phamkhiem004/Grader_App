/**
 * KHO PHIÊN CHẤM dùng chung giữa các trang (localStorage).
 *
 * <p>Trang Chấm tự động giữ mỗi bộ testcase một phiên; trang Lịch sử bấm "Chấm lại" cũng tạo ra
 * một phiên như thế. Nếu mỗi bên tự đọc/ghi localStorage theo định dạng của mình thì bài chấm lại
 * sẽ không bao giờ hiện ở màn hình theo dõi — đúng lỗi đã gặp. Gom vào đây để hai bên nói cùng
 * một thứ tiếng.
 */

export const ACTIVE_BATCH_KEY = "grader_active_batch";

export interface StoredSession {
  batchId: string;
  parseErrors?: string[];
}

export interface StoredSessionMap {
  lastExam?: string;
  sessions: Record<string, StoredSession>;
}

/** Đọc kho phiên; hiểu cả bản lưu MỘT phiên đời cũ ({batchId, examId}). */
export function readStoredSessions(): StoredSessionMap {
  try {
    const raw = JSON.parse(localStorage.getItem(ACTIVE_BATCH_KEY) || "null");
    if (raw?.sessions) return { lastExam: raw.lastExam, sessions: raw.sessions };
    if (raw?.batchId && raw?.examId) {
      return { lastExam: raw.examId, sessions: { [raw.examId]: { batchId: raw.batchId, parseErrors: raw.parseErrors || [] } } };
    }
  } catch { /* bỏ qua */ }
  return { sessions: {} };
}

export function writeStoredSessions(map: StoredSessionMap): void {
  try {
    if (!Object.keys(map.sessions).length) localStorage.removeItem(ACTIVE_BATCH_KEY);
    else localStorage.setItem(ACTIVE_BATCH_KEY, JSON.stringify({ v: 2, ...map }));
  } catch { /* bỏ qua */ }
}

/**
 * Ghi/thay phiên của MỘT bộ testcase, giữ nguyên phiên của các bộ khác. Đặt luôn `lastExam` để
 * trang Chấm tự động mở đúng bộ vừa được chấm lại.
 */
export function upsertStoredSession(examId: string, batchId: string): void {
  const map = readStoredSessions();
  map.sessions[examId] = { batchId, parseErrors: [] };
  map.lastExam = examId;
  writeStoredSessions(map);
}

/**
 * Bộ testcase đang có phiên CHẠY DỞ (nếu có) — trang Lịch sử hỏi trước khi chấm lại, để giữ đúng
 * luật "mỗi lúc chỉ một phiên" của màn hình Chấm tự động.
 */
export async function findRunningSession(apiBase: string): Promise<{ examId: string; batchId: string } | null> {
  const { sessions } = readStoredSessions();
  for (const [examId, s] of Object.entries(sessions)) {
    if (!s?.batchId) continue;
    try {
      const data = await fetch(`${apiBase}/batch/progress/${encodeURIComponent(s.batchId)}`).then((r) => r.json());
      if ((data?.queued || 0) + (data?.grading || 0) > 0) return { examId, batchId: s.batchId };
    } catch { /* batch hỏng/đã xoá → coi như không chạy */ }
  }
  return null;
}
