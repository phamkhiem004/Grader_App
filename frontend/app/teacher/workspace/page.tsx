"use client";

import React, { useEffect, useMemo, useState } from "react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import { API_BASE, PASS_THRESHOLD } from "@/lib/config";
import { Skeleton, SkeletonRow } from "@/components/ui/Skeleton";
import { Tooltip } from "@/components/ui/Tooltip";
import SelectMenu from "@/components/ui/SelectMenu";
import {
  FileCode2, ClipboardCheck, Save, Loader2, CheckCircle2, AlertCircle, XCircle,
  Users, Award, RotateCcw, FileText,
} from "lucide-react";

interface ExamOption { examId: string; examName: string; }
interface StudentRow {
  studentId: string; studentName: string | null; status: string;
  score: number | null; manualScore: number | null;
  /** Kết luận backend (SCORED / SYSTEM_BLOCKED / ...) — vắng ở dữ liệu cũ thì suy từ status. */
  outcome?: string | null;
}
interface Criterion {
  testId: string; name: string; skill: string; weight: number;
  description: string; expected: string;
}
interface Detail {
  studentName: string | null; status: string;
  autoScore: number | null; manualScore: number | null;
  resultJson: string | null; manualJson: string | null;
  manualBy: string | null; manualAt: string | null;
}
interface CodeFile { name: string; content: string; }

const round2 = (n: number) => Math.round(n * 100) / 100;

// Kênh nhận lệnh "mở bài của SV này" từ ô tìm kiếm header — chuỗi phải khớp SidebarLayout.gotoResult.
const OPEN_SUBMISSION_KEY = "grader_open_submission";
const OPEN_SUBMISSION_EVENT = "grader:open-submission";

function WorkspaceContent() {
  const [exams, setExams] = useState<ExamOption[]>([]);
  const [examId, setExamId] = useState<string>("");
  const [students, setStudents] = useState<StudentRow[]>([]);
  const [criteria, setCriteria] = useState<Criterion[]>([]);
  const [loadingStudents, setLoadingStudents] = useState(false);

  const [studentId, setStudentId] = useState<string | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [files, setFiles] = useState<CodeFile[]>([]);
  const [activeFile, setActiveFile] = useState(0);
  const [loadingDetail, setLoadingDetail] = useState(false);

  // Lọc danh sách bài bên trái: all | blocked (lỗi hệ thống) | manual (đã chấm tay)
  const [listFilter, setListFilter] = useState<"all" | "blocked" | "manual">("all");
  // SV cần mở sẵn khi được điều hướng tới từ ô tìm kiếm header (?examId=..&studentId=..)
  const [pendingStudent, setPendingStudent] = useState<string | null>(null);

  const [points, setPoints] = useState<Record<string, number>>({});
  const [touched, setTouched] = useState(false);   // GV đã chỉnh điểm tiêu chí chưa
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<{ type: "ok" | "err"; text: string } | null>(null);

  // Lệnh "mở bài của SV này" từ ô tìm kiếm header — truyền qua localStorage + custom event thay
  // vì query string: đứng sẵn ở trang này thì điều hướng chỉ đổi query nên effect mount không
  // chạy lại, còn useSearchParams thì kéo theo Suspense cho cả trang. Consume cả lúc mount
  // (đi từ trang khác tới) lẫn khi event bắn (đang đứng ngay tại trang).
  useEffect(() => {
    const consume = () => {
      try {
        const raw = localStorage.getItem(OPEN_SUBMISSION_KEY);
        if (!raw) return;
        localStorage.removeItem(OPEN_SUBMISSION_KEY);
        const target = JSON.parse(raw);
        if (target?.examId) {
          setExamId(target.examId);
          setPendingStudent(target.studentId || null);
        }
      } catch { /* bỏ qua */ }
    };
    consume();
    window.addEventListener(OPEN_SUBMISSION_EVENT, consume);
    return () => window.removeEventListener(OPEN_SUBMISSION_EVENT, consume);
  }, []);

  // Danh sách SV của đề nạp xong thì mới chọn được bài đang chờ mở. CHỈ tiêu pending khi chọn
  // được: lúc mount effect này chạy trước khi list kịp load (students còn rỗng, loadingStudents
  // còn false) — xoá pending ở nhánh không-tìm-thấy là lệnh mở bài từ ô tìm kiếm bị nuốt mất.
  useEffect(() => {
    if (!pendingStudent || loadingStudents) return;
    if (students.some((s) => s.studentId === pendingStudent)) {
      setStudentId(pendingStudent);
      setPendingStudent(null);
    }
  }, [pendingStudent, loadingStudents, students]);

  // Nạp danh sách bộ testcase đã chấm
  useEffect(() => {
    fetch(`${API_BASE}/statistics/exams`)
      .then((r) => r.json())
      .then((d: ExamOption[]) => {
        setExams(Array.isArray(d) ? d : []);
        if (Array.isArray(d) && d.length) setExamId((prev) => prev || d[0].examId);
      })
      .catch(() => setExams([]));
  }, []);

  // Khi đổi đề → nạp danh sách SV + rubric tiêu chí
  useEffect(() => {
    if (!examId) return;
    let ignore = false;
    setLoadingStudents(true);
    setStudentId(null); setDetail(null); setFiles([]);
    setListFilter("all");   // filter của đề trước dễ ra danh sách trống khó hiểu ở đề mới
    Promise.all([
      fetch(`${API_BASE}/results/exam/${encodeURIComponent(examId)}`).then((r) => (r.ok ? r.json() : [])),
      fetch(`${API_BASE}/exam-setup/criteria/${encodeURIComponent(examId)}`).then((r) => (r.ok ? r.json() : [])),
    ])
      .then(([rows, crit]) => {
        if (ignore) return;
        setStudents(Array.isArray(rows) ? rows : []);
        setCriteria(Array.isArray(crit) ? crit : []);
      })
      .catch(() => { if (!ignore) { setStudents([]); setCriteria([]); } })
      .finally(() => { if (!ignore) setLoadingStudents(false); });
    return () => { ignore = true; };
  }, [examId]);

  // Điểm tối đa mỗi tiêu chí = ĐÚNG weight giảng viên đặt trong skills_matrix (số đẹp: 0.5, 0.2…).
  // KHÔNG chia lại tỉ lệ ở đây (trước kia chia weight/Σweight×10 nên 0.5 thành 0.47, 0.2 thành 0.19).
  // Điểm cuối được CHUẨN HOÁ về thang 10 ở `total` — giống hệt điểm tự động (Σđạt/Σweight×10).
  const sumW = useMemo(() => criteria.reduce((s, c) => s + (c.weight || 0), 0), [criteria]);
  const maxPointsOf = (c: Criterion) => c.weight || 0;

  // auto pass/fail theo test_id (từ result_json)
  const autoStatus = useMemo(() => {
    const map: Record<string, boolean> = {};
    try {
      const rj = JSON.parse(detail?.resultJson || "{}");
      (rj.test_cases || []).forEach((tc: any) => {
        const id = tc.test_id || tc.testId;
        if (id) map[id] = tc.status === "passed" || tc.status === "PASS";
      });
    } catch { /* bỏ qua */ }
    return map;
  }, [detail]);

  // Khi chọn SV → nạp chi tiết + mã nguồn
  useEffect(() => {
    if (!examId || !studentId) return;
    let ignore = false;
    setLoadingDetail(true);
    setSaveMsg(null);
    setActiveFile(0);
    Promise.all([
      fetch(`${API_BASE}/results/${encodeURIComponent(examId)}/${encodeURIComponent(studentId)}/detail`).then((r) => (r.ok ? r.json() : null)),
      fetch(`${API_BASE}/batch/submission/${encodeURIComponent(examId)}/${encodeURIComponent(studentId)}/files`).then((r) => (r.ok ? r.json() : [])),
    ])
      .then(([d, fs]) => {
        if (ignore) return;
        setDetail(d && !d.error ? d : null);
        setFiles(Array.isArray(fs) ? fs : []);
      })
      .catch(() => { if (!ignore) { setDetail(null); setFiles([]); } })
      .finally(() => { if (!ignore) setLoadingDetail(false); });
    return () => { ignore = true; };
  }, [examId, studentId]);

  // Khởi tạo điểm từng tiêu chí khi có detail + criteria
  useEffect(() => {
    if (!detail) return;
    let savedCriteria: any[] | null = null;
    let savedNote = "";
    try {
      const mj = JSON.parse(detail.manualJson || "{}");
      savedCriteria = mj.criteria || null;
      savedNote = mj.note || "";
    } catch { /* bỏ qua */ }

    const init: Record<string, number> = {};
    criteria.forEach((c) => {
      const w = c.weight || 0;
      const saved = savedCriteria?.find((x) => x.testId === c.testId);
      if (saved && typeof saved.points === "number") {
        // Điểm đã lưu có thể theo THANG CŨ (đã chuẩn hoá /10) → quy về thang weight hiện tại theo
        // TỈ LỆ đạt được (points/maxPoints) để total không lệch với điểm chấm tay đã lưu.
        const oldMax = typeof saved.maxPoints === "number" && saved.maxPoints > 0 ? saved.maxPoints : w;
        const frac = oldMax > 0 ? Math.max(0, Math.min(1, saved.points / oldMax)) : 0;
        init[c.testId] = round2(frac * w);
      } else {
        init[c.testId] = autoStatus[c.testId] ? w : 0;
      }
    });
    setPoints(init);
    setNote(savedNote);
    setTouched(false);   // vừa nạp từ DB → coi như chưa chỉnh
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail, criteria]);

  // Tổng điểm THÔ (thang Σweight) → CHUẨN HOÁ về 10 cho khớp điểm tự động. Pass hết = đúng 10.
  const rawTotal = useMemo(
    () => Object.values(points).reduce((s, p) => s + (p || 0), 0),
    [points]
  );
  const total = useMemo(
    () => (sumW > 0 ? round2((rawTotal / sumW) * 10) : 0),
    [rawTotal, sumW]
  );

  // Giá trị sẽ GHI khi bấm "Lưu điểm", và là số chạy realtime ở dòng "Tổng" cạnh nút đó.
  // CHƯA chỉnh tay → giữ ĐÚNG điểm đã lưu (tránh tự suy lại sai khi dữ liệu cũ lưu thiếu tiêu chí);
  // GV bắt đầu chỉnh → lấy total tính trực tiếp.
  const scoreToSave = touched || detail?.manualScore == null ? total : detail.manualScore;

  // Điểm HIỂN THỊ ở thẻ "Chấm tay" trên đầu trang: CHỈ điểm đã lưu vào DB, chưa lưu thì "—".
  // Trước đây nó rơi về `total` — mà `total` khởi tạo từ pass/fail của máy — nên bài chưa ai chấm
  // tay vẫn hiện sẵn đúng bằng điểm tự động, trông như đã chấm rồi. Số đang chỉnh dở vẫn nhìn được
  // ở dòng "Tổng" phía dưới, nên thẻ này giữ đúng nghĩa "điểm chấm tay đã chốt".
  const savedManualScore = detail?.manualScore ?? null;

  const setPoint = (testId: string, val: number, max: number) => {
    const v = Math.max(0, Math.min(max, round2(isNaN(val) ? 0 : val)));
    setTouched(true);
    setPoints((p) => ({ ...p, [testId]: v }));
  };

  const resetAuto = () => {
    const init: Record<string, number> = {};
    criteria.forEach((c) => { init[c.testId] = autoStatus[c.testId] ? maxPointsOf(c) : 0; });
    setTouched(true);
    setPoints(init);
  };

  const save = async () => {
    if (!studentId) return;
    setSaving(true); setSaveMsg(null);
    const criteriaPayload = criteria.map((c) => ({
      testId: c.testId, name: c.name, skill: c.skill,
      maxPoints: maxPointsOf(c), points: points[c.testId] ?? 0, passed: !!autoStatus[c.testId],
    }));
    try {
      const res = await fetch(`${API_BASE}/results/${encodeURIComponent(examId)}/${encodeURIComponent(studentId)}/manual`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ score: scoreToSave, criteria: criteriaPayload, note }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Lưu thất bại");
      setSaveMsg({ type: "ok", text: `Đã lưu điểm chấm tay: ${scoreToSave.toFixed(1)}` });
      // cập nhật badge trong danh sách + detail
      setStudents((list) => list.map((s) => (s.studentId === studentId ? { ...s, manualScore: scoreToSave } : s)));
      setDetail((d) => (d ? { ...d, manualScore: scoreToSave } : d));
      setTouched(false);
    } catch (e: any) {
      setSaveMsg({ type: "err", text: e?.message || "Lưu thất bại" });
    } finally {
      setSaving(false);
    }
  };

  const selectedStudent = students.find((s) => s.studentId === studentId);

  // ── Lọc + sắp xếp danh sách bài bên trái ──
  const isBlockedRow = (s: StudentRow) =>
    s.outcome ? s.outcome === "SYSTEM_BLOCKED" : s.status === "ERROR" || s.status === "MANUAL_REVIEW";
  const manualCount = students.filter((s) => s.manualScore != null).length;
  const blockedCount = students.filter(isBlockedRow).length;
  const visibleStudents = useMemo(() => {
    const base =
      listFilter === "manual" ? students.filter((s) => s.manualScore != null)
      : listFilter === "blocked" ? students.filter(isBlockedRow)
      : students;
    // Bài ĐÃ chấm tay dồn lên đầu — sort ổn định nên phần còn lại giữ nguyên thứ tự cũ.
    return [...base].sort((a, b) => Number(b.manualScore != null) - Number(a.manualScore != null));
  }, [students, listFilter]);

  return (
    <SidebarLayout
      title="Chấm thủ công"
      subtitle="Chọn bộ testcase → xem bài làm sinh viên → chấm thủ công theo từng tiêu chí"
      activePath="/teacher/workspace"
      /* Cùng khuôn với trang Chấm tự động: trần bề ngang rộng hơn, cột trái chốt 320px.
         Ở trang này phần dôi ra còn quan trọng hơn — cột phải chứa mã nguồn bài làm. */
      contentClassName="max-w-[1600px]"
    >
      <div className="grid min-w-0 grid-cols-1 gap-6 xl:grid-cols-[320px_minmax(0,1fr)]">
        {/* Cột trái: chọn đề + danh sách SV */}
        <div className="min-w-0">
          <div className="card overflow-hidden">
            {/* Hai ô CHỈ CHỌN, dựng từ cùng một component nên hình dáng khớp nhau và khớp ô chọn
                bộ testcase bên trang Chấm tự động: cao bằng nhau, bo lg, viền slate-200, icon
                trái, mũi tên phải, danh sách xổ ra cùng một kiểu. */}
            <div className="space-y-4 border-b border-slate-100 bg-slate-50/60 p-5">
              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Bộ testcase</label>
                <SelectMenu
                  icon={FileText}
                  ariaLabel="Bộ testcase"
                  value={examId}
                  // Đổi bộ thì huỷ lệnh mở bài đang chờ, kẻo nó "cướp" lựa chọn về sau.
                  onChange={(v) => { setExamId(v); setPendingStudent(null); }}
                  placeholder="— Chọn bộ testcase —"
                  emptyText="Chưa có bộ testcase đã chấm"
                  options={exams.map((e) => ({
                    value: e.examId,
                    label: e.examId,
                    sublabel: e.examName,
                    badge: e.examId.slice(0, 2).toUpperCase(),
                  }))}
                />
              </div>

              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">Danh sách bài</label>
                <SelectMenu
                  icon={Users}
                  ariaLabel="Lọc danh sách bài"
                  value={listFilter}
                  onChange={(v) => setListFilter(v as "all" | "blocked" | "manual")}
                  options={[
                    { value: "all", label: `Tất cả (${students.length})` },
                    { value: "blocked", label: `Lỗi hệ thống (${blockedCount})` },
                    { value: "manual", label: `Đã được chấm tay (${manualCount})` },
                  ]}
                />
              </div>
            </div>
            <div className="custom-scrollbar max-h-[68vh] overflow-y-auto p-2">
              {loadingStudents ? (
                Array.from({ length: 6 }).map((_, i) => <SkeletonRow key={i} />)
              ) : visibleStudents.length === 0 ? (
                <div className="p-6 text-center text-sm text-slate-400">
                  {students.length === 0 ? "Chưa có bài nộp."
                    : listFilter === "manual" ? "Chưa có bài nào được chấm tay."
                    : "Không có bài nào lỗi hệ thống."}
                </div>
              ) : (
                visibleStudents.map((s) => {
                  const active = s.studentId === studentId;
                  const initials = (s.studentName || s.studentId || "?").trim().charAt(0).toUpperCase();
                  return (
                    <button
                      key={s.studentId}
                      onClick={() => { setStudentId(s.studentId); setPendingStudent(null); }}
                      className={`mb-1 flex w-full items-center gap-3 rounded-lg p-2.5 text-left transition-colors ${
                        active ? "bg-indigo-50 ring-1 ring-indigo-200" : "hover:bg-slate-50"
                      }`}
                    >
                      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-slate-200 text-xs font-bold text-slate-500">
                        {initials}
                      </div>
                      <div className="min-w-0 flex-1">
                        {/* Tím = đã có điểm chấm tay, cùng tông với badge điểm bên phải. */}
                        <p className={`truncate text-sm font-semibold ${s.manualScore != null ? "text-violet-700" : "text-slate-800"}`}>
                          {s.studentName || "—"}
                        </p>
                        <p className="truncate font-mono text-xs text-slate-400">{s.studentId}</p>
                      </div>
                      <div className="shrink-0 text-right">
                        {s.manualScore != null ? (
                          <Tooltip label="Điểm chấm tay" side="left">
                            <span className="rounded bg-violet-100 px-1.5 py-0.5 text-xs font-bold text-violet-700">{s.manualScore.toFixed(1)}</span>
                          </Tooltip>
                        ) : s.score != null ? (
                          <Tooltip label="Điểm tự động" side="left">
                            <span className="text-xs font-semibold text-slate-400">{s.score.toFixed(1)}</span>
                          </Tooltip>
                        ) : null}
                      </div>
                    </button>
                  );
                })
              )}
            </div>
          </div>
        </div>

        {/* Cột phải: bài làm + chấm tiêu chí */}
        <div className="min-w-0">
          {!studentId ? (
            <div className="flex h-full min-h-[60vh] flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
              <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-50 to-blue-50 text-indigo-400">
                <ClipboardCheck size={32} />
              </div>
              <h3 className="mb-1 text-base font-bold text-slate-700">Chọn một sinh viên để bắt đầu chấm</h3>
              <p className="max-w-sm text-sm text-slate-500">Bên trái là danh sách bài nộp của bộ testcase. Chọn 1 SV để xem mã nguồn và chấm điểm theo từng tiêu chí.</p>
            </div>
          ) : (
            <div className="space-y-5">
              {/* Header SV */}
              <div className="card flex flex-wrap items-center justify-between gap-3 p-5">
                <div className="flex items-center gap-3">
                  <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-blue-600 text-sm font-bold text-white">
                    {(selectedStudent?.studentName || studentId).trim().charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <p className="text-base font-bold text-slate-800">{selectedStudent?.studentName || "—"}</p>
                    <p className="font-mono text-xs text-slate-400">{studentId} · {examId}</p>
                  </div>
                </div>
                <div className="flex items-center gap-5">
                  <ScorePill label="Tự động" value={detail?.autoScore} tone="slate" />
                  <ScorePill label="Chấm tay" value={savedManualScore} tone="violet" highlight />
                </div>
              </div>

              {/* Mã nguồn cần bề ngang hơn bảng tiêu chí: dòng code dài mà cột hẹp thì phải cuộn
                  ngang liên tục, trong khi cột tiêu chí chỉ có tên + một ô số. Chia 60/40. */}
              <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
                {/* Bài làm: mã nguồn */}
                <div className="card flex max-h-[70vh] flex-col overflow-hidden">
                  <div className="flex items-center gap-2 border-b border-slate-100 bg-slate-50/60 px-4 py-3">
                    <FileCode2 size={16} className="text-indigo-500" />
                    <h3 className="text-sm font-bold text-slate-700">Bài làm của sinh viên</h3>
                  </div>
                  {loadingDetail ? (
                    <div className="space-y-2 p-4">
                      {Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-3.5 w-full" />)}
                    </div>
                  ) : files.length === 0 ? (
                    <div className="p-6 text-center text-sm text-slate-400">
                      Không đọc được mã nguồn (bài nộp có thể đã bị dọn hoặc không lưu).
                    </div>
                  ) : (
                    <>
                      <div className="custom-scrollbar flex gap-1 overflow-x-auto border-b border-slate-100 px-2 py-2">
                        {files.map((f, i) => (
                          <button
                            key={f.name}
                            onClick={() => setActiveFile(i)}
                            className={`shrink-0 rounded-md px-2.5 py-1 font-mono text-xs transition-colors ${
                              i === activeFile ? "bg-indigo-100 text-indigo-700" : "text-slate-500 hover:bg-slate-100"
                            }`}
                          >
                            {f.name.split("/").pop()}
                          </button>
                        ))}
                      </div>
                      <pre className="custom-scrollbar flex-1 overflow-auto bg-slate-900 p-4 text-xs leading-relaxed text-slate-100">
                        <code>{files[activeFile]?.content}</code>
                      </pre>
                    </>
                  )}
                </div>

                {/* Chấm theo tiêu chí */}
                <div className="card flex max-h-[70vh] flex-col overflow-hidden">
                  <div className="flex items-center justify-between border-b border-slate-100 bg-slate-50/60 px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Award size={16} className="text-violet-500" />
                      <h3 className="text-sm font-bold text-slate-700">Chấm theo tiêu chí</h3>
                    </div>
                    <Tooltip label="Đặt lại theo kết quả tự động">
                      <button onClick={resetAuto} className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-slate-100">
                        <RotateCcw size={13} /> Auto
                      </button>
                    </Tooltip>
                  </div>

                  <div className="custom-scrollbar flex-1 overflow-y-auto p-3">
                    {loadingDetail ? (
                      <div className="space-y-3">
                        {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-16 w-full rounded-xl" />)}
                      </div>
                    ) : criteria.length === 0 ? (
                      <div className="p-6 text-center text-sm text-slate-400">
                        Bộ testcase này chưa có rubric (skills_matrix.json). Không thể chấm theo tiêu chí.
                      </div>
                    ) : (
                      <div className="space-y-2.5">
                        {criteria.map((c) => {
                          const max = maxPointsOf(c);
                          const passed = autoStatus[c.testId];
                          return (
                            <div key={c.testId} className="rounded-xl border border-slate-200 bg-slate-50/50 p-3">
                              <div className="mb-2 flex items-start justify-between gap-2">
                                <div className="min-w-0">
                                  <div className="flex items-center gap-1.5">
                                    {passed === true ? (
                                      <CheckCircle2 size={14} className="shrink-0 text-emerald-500" />
                                    ) : passed === false ? (
                                      <XCircle size={14} className="shrink-0 text-rose-400" />
                                    ) : null}
                                    <p className="truncate text-sm font-semibold text-slate-800" title={c.name}>{c.name}</p>
                                  </div>
                                  <p className="mt-0.5 text-xs text-slate-500">
                                    <span className="rounded bg-slate-200/70 px-1.5 py-0.5 font-medium text-slate-600">{c.skill}</span>
                                    {c.description && <span className="ml-1.5">{c.description}</span>}
                                  </p>
                                </div>
                              </div>
                              <div className="flex items-center gap-2">
                                <input
                                  type="number"
                                  step={0.25}
                                  min={0}
                                  max={max}
                                  value={points[c.testId] ?? 0}
                                  onChange={(e) => setPoint(c.testId, parseFloat(e.target.value), max)}
                                  className="w-20 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm font-semibold text-slate-800 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                                />
                                <span className="text-xs text-slate-400">/ {max} điểm</span>
                                <div className="ml-auto flex gap-1">
                                  <Tooltip label="Cho đủ điểm">
                                    <button onClick={() => setPoint(c.testId, max, max)} className="rounded-md bg-emerald-50 px-2 py-1 text-xs font-semibold text-emerald-600 hover:bg-emerald-100">Đủ</button>
                                  </Tooltip>
                                  <Tooltip label="Cho 0 điểm">
                                    <button onClick={() => setPoint(c.testId, 0, max)} className="rounded-md bg-rose-50 px-2 py-1 text-xs font-semibold text-rose-500 hover:bg-rose-100">0</button>
                                  </Tooltip>
                                </div>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </div>

                  {/* Nhận xét + lưu */}
                  <div className="border-t border-slate-100 p-3">
                    <textarea
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="Nhận xét chung cho sinh viên (tuỳ chọn)..."
                      rows={2}
                      className="mb-3 w-full resize-none rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-800 outline-none focus:border-indigo-400 focus:bg-white focus:ring-2 focus:ring-indigo-100"
                    />
                    {saveMsg && (
                      <div className={`mb-3 flex items-center gap-2 rounded-lg border p-2.5 text-xs font-medium ${
                        saveMsg.type === "ok" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-rose-200 bg-rose-50 text-rose-600"
                      }`}>
                        {saveMsg.type === "ok" ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />}
                        {saveMsg.text}
                      </div>
                    )}
                    <div className="flex items-center justify-between">
                      <div className="text-sm">
                        <span className="text-slate-500">Tổng: </span>
                        <span className={`text-xl font-bold ${scoreToSave >= PASS_THRESHOLD ? "text-emerald-600" : "text-rose-600"}`}>{scoreToSave.toFixed(1)}</span>
                        <span className="text-slate-400"> / 10</span>
                        {touched && sumW > 0 && Math.abs(sumW - 10) > 0.001 && (
                          <span className="ml-2 text-[11px] text-slate-400">
                            (thô {round2(rawTotal)}/{round2(sumW)}, đã chuẩn hoá về 10)
                          </span>
                        )}
                      </div>
                      <button
                        onClick={save}
                        disabled={saving || criteria.length === 0}
                        className="flex items-center gap-2 rounded-xl bg-violet-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-violet-700 active:scale-95 disabled:opacity-50"
                      >
                        {saving ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} Lưu điểm
                      </button>
                    </div>
                    {detail?.manualBy && (
                      <p className="mt-2 text-[11px] text-slate-400">
                        Chấm tay bởi {detail.manualBy}
                        {detail.manualAt ? ` · ${new Date(detail.manualAt).toLocaleString("vi-VN")}` : ""}
                      </p>
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </SidebarLayout>
  );
}

export default function WorkspacePage() {
  return <WorkspaceContent />;
}

function ScorePill({ label, value, tone, highlight }: {
  label: string; value: number | null | undefined; tone: "slate" | "violet"; highlight?: boolean;
}) {
  const tones = {
    slate: "text-slate-600",
    violet: "text-violet-600",
  };
  return (
    <div className="text-center">
      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p>
      <p className={`text-2xl font-bold ${tones[tone]} ${highlight ? "" : ""}`}>
        {value != null ? Number(value).toFixed(1) : "—"}
      </p>
    </div>
  );
}
