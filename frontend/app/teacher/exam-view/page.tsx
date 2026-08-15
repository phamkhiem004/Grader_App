"use client";

// Trang XEM ĐỀ của một bộ testcase: đề bài + hình minh họa đã gộp thành một tài liệu.
//
// Nút "Tải .docx" đổi SVG → PNG NGAY TRONG TRÌNH DUYỆT rồi gửi lên: máy chủ không có thư viện
// rasterize SVG (repo build offline, không thêm được dependency), mà Word thì không hiển thị SVG
// ổn định. Trình duyệt vốn đang vẽ đúng hình đó nên để nó chụp lại là chuẩn nhất.

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Download, FileText, Loader2, Printer } from "lucide-react";
import SidebarLayout from "@/components/layout/SidebarLayout";
import Banner from "@/components/ui/Banner";
import { API_BASE } from "@/lib/config";

interface Mockup { id: string; title: string; svg: string }
interface ViewData { exam_id: string; has_de_bai: boolean; de_bai: string; html: string; mockups: Mockup[] }

/** Vẽ một chuỗi SVG ra PNG bằng canvas. Trả về data URI + kích thước thật để đặt vào .docx. */
function svgToPng(svg: string): Promise<{ png: string; width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    // Nhân 2 cho nét khi in; SVG không có tài nguyên ngoài nên canvas không bị "nhiễm bẩn".
    const scale = 2;
    image.onload = () => {
      const width = image.width || 900;
      const height = image.height || 600;
      const canvas = document.createElement("canvas");
      canvas.width = width * scale;
      canvas.height = height * scale;
      const ctx = canvas.getContext("2d");
      if (!ctx) { reject(new Error("Trình duyệt không hỗ trợ canvas.")); return; }
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      resolve({ png: canvas.toDataURL("image/png"), width, height });
    };
    image.onerror = () => reject(new Error("Không dựng được ảnh từ hình minh họa."));
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  });
}

function ExamView() {
  const examId = (useSearchParams().get("exam") || "").trim();
  const [data, setData] = useState<ViewData | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    if (!examId) { setErr("Thiếu mã bộ testcase."); setLoading(false); return; }
    let ignore = false;
    fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId)}/de-bai/view`)
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw new Error(body?.error || "Không đọc được đề bài.");
        return body as ViewData;
      })
      .then((body) => { if (!ignore) setData(body); })
      .catch((e: unknown) => { if (!ignore) setErr(e instanceof Error ? e.message : "Không đọc được đề bài."); })
      .finally(() => { if (!ignore) setLoading(false); });
    return () => { ignore = true; };
  }, [examId]);

  const downloadDocx = useCallback(async () => {
    if (!data) return;
    setDownloading(true);
    setErr(null);
    try {
      const images = [];
      for (const mockup of data.mockups || []) {
        try {
          const shot = await svgToPng(mockup.svg);
          images.push({ png_base64: shot.png, width: shot.width, height: shot.height });
        } catch {
          // Một hình lỗi không được làm hỏng cả bản tải về — bỏ qua hình đó thôi.
        }
      }
      const res = await fetch(`${API_BASE}/exam-setup/${encodeURIComponent(examId)}/de-bai/docx`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ images }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body?.error || "Không tạo được file .docx.");
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${examId}_de_bai.docx`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Không tạo được file .docx.");
    } finally {
      setDownloading(false);
    }
  }, [data, examId]);

  return (
    <SidebarLayout title="Xem đề bài" activePath="/teacher/archive" contentClassName="max-w-5xl">
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Link href="/teacher/archive"
          className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50">
          <ArrowLeft size={15} /> Về Kho bộ testcase
        </Link>
        <span className="rounded-lg bg-slate-100 px-2.5 py-1 font-mono text-xs font-semibold text-slate-600">{examId}</span>
        <div className="ml-auto flex gap-2">
          <button onClick={() => window.print()} disabled={!data?.has_de_bai}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-40">
            <Printer size={15} /> In
          </button>
          <button onClick={downloadDocx} disabled={!data?.has_de_bai || downloading}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3.5 py-2 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">
            {downloading ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
            Tải đề (.docx)
          </button>
        </div>
      </div>

      {err && <Banner tone="error" onClose={() => setErr(null)}>{err}</Banner>}

      {loading ? (
        <div className="flex items-center justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>
      ) : !data?.has_de_bai ? (
        <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-slate-300/70 bg-white/60 p-12 text-center">
          <FileText size={36} className="mb-3 text-slate-300" />
          <h3 className="mb-1 text-base font-bold text-slate-700">Bộ này chưa có đề bài</h3>
          <p className="text-sm text-slate-500">
            Soạn đề ở trang “Tạo bộ testcase” → Trợ lý AI, rồi bấm “Lưu đề bài + hình”.
          </p>
        </div>
      ) : (
        // HTML do chính máy chủ dựng (chữ giáo viên đã được escape), không phải chuỗi tuỳ ý từ ngoài.
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
          <iframe title={`Đề bài ${examId}`} srcDoc={data.html}
            className="h-[78vh] w-full border-0" />
        </div>
      )}
    </SidebarLayout>
  );
}

export default function ExamViewPage() {
  return (
    <Suspense fallback={<div className="flex justify-center py-20 text-slate-400"><Loader2 size={24} className="animate-spin" /></div>}>
      <ExamView />
    </Suspense>
  );
}
