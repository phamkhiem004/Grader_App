/**
 * BẢN NHÁP CỦA TRỢ LÝ AI SOẠN ĐỀ (localStorage), lưu theo từng bộ testcase.
 *
 * <p>Soạn xong đề rồi phân tích key rồi sinh khung starter là chuỗi việc kéo dài chục phút và tốn
 * nhiều lượt gọi AI. Lỡ tải lại trang, đóng nhầm tab hay bấm sang trang khác mà mất sạch thì phải
 * làm lại từ đầu — vừa mất thời gian vừa tốn tiền API. Nháp này giữ nguyên bước đang dở, mở lại
 * trang (hoặc bấm "Sửa" đúng bộ đó) là đi tiếp.
 *
 * <p>Vì sao localStorage chứ không phải server: nháp là thứ CHƯA ĐƯỢC DUYỆT — chưa vào Khu vực 0,
 * chưa vào Khu vực 3, chưa lưu bộ testcase. Đẩy nó lên server sẽ phải nghĩ chuyện dọn rác, quyền
 * xem, và cả rủi ro bản nháp bị hiểu nhầm thành bộ đề thật.
 */

const KEY = "grader_ai_author_drafts";
/** Giữ nháp của 5 bộ gần nhất — đủ cho việc làm xen kẽ, không phình localStorage vô hạn. */
const MAX_EXAMS = 5;

export interface AiAuthorDraft {
  /** Bước đang dở, chỉ để hiện lại đúng chỗ; mọi cờ điều kiện đều nằm trong `state`. */
  updatedAt: number;
  state: Record<string, unknown>;
}

type DraftMap = Record<string, AiAuthorDraft>;

function readAll(): DraftMap {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || "null");
    return raw && typeof raw === "object" && raw.drafts ? (raw.drafts as DraftMap) : {};
  } catch {
    return {};
  }
}

function writeAll(drafts: DraftMap): boolean {
  try {
    if (!Object.keys(drafts).length) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, JSON.stringify({ v: 1, drafts }));
    return true;
  } catch {
    return false;                                     // hết quota — nơi gọi tự rút bớt rồi thử lại
  }
}

export function readAiDraft(examId: string): AiAuthorDraft | null {
  const id = examId.trim();
  if (!id) return null;
  return readAll()[id] || null;
}

/**
 * Ghi nháp của MỘT bộ, giữ nháp của các bộ khác.
 *
 * <p>Hình minh họa là phần nặng nhất (SVG, có khi kèm ảnh giáo viên tải lên dạng data URI) nên
 * quota rất dễ vỡ. Vỡ thì bỏ dần thứ nặng — hình trước, khung starter sau — chứ KHÔNG bỏ cả bản
 * nháp: mất hình còn vẽ lại được bằng một cú bấm, mất đề bài thì phải gọi lại AI.
 *
 * @returns phần đã phải bỏ bớt để ghi vừa, rỗng nếu ghi được nguyên vẹn
 */
export function writeAiDraft(examId: string, state: Record<string, unknown>): string[] {
  const id = examId.trim();
  if (!id) return [];

  const dropped: string[] = [];
  const attempts: Record<string, unknown>[] = [
    state,
    { ...state, screens: [] },
    { ...state, screens: [], starterFiles: [], starterSpec: null },
  ];
  const labels = ["", "hình minh họa", "hình minh họa và khung starter"];

  for (let i = 0; i < attempts.length; i++) {
    const drafts = readAll();
    drafts[id] = { updatedAt: Date.now(), state: attempts[i] };

    // Quá số bộ cho phép → bỏ bản cũ nhất. Bộ ĐANG GHI được loại khỏi danh sách xét ngay từ đầu:
    // xếp theo updatedAt rồi cắt đuôi thì mấy bản ghi cùng một mili-giây sẽ hoà, và cái vừa ghi
    // có thể rơi vào phần bị cắt — tức là bấm lưu xong lại mất chính bản vừa lưu.
    const others = Object.keys(drafts)
      .filter((other) => other !== id)
      .sort((a, b) => (drafts[b].updatedAt || 0) - (drafts[a].updatedAt || 0));
    for (const stale of others.slice(MAX_EXAMS - 1)) delete drafts[stale];

    if (writeAll(drafts)) {
      if (labels[i]) dropped.push(labels[i]);
      return dropped;
    }
  }
  return ["toàn bộ bản nháp"];                        // hết cách: localStorage đã đầy vì thứ khác
}

export function clearAiDraft(examId: string): void {
  const id = examId.trim();
  if (!id) return;
  const drafts = readAll();
  if (!drafts[id]) return;
  delete drafts[id];
  writeAll(drafts);
}
