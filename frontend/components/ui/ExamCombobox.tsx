"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { Search, ChevronDown, Check } from "lucide-react";

export interface ExamComboOption {
  examId: string;
  examName?: string;
}

interface Props {
  options: ExamComboOption[];
  value: string;
  onChange: (v: string) => void;
  onEnter?: () => void;          // bấm Enter khi không chọn option nào (vd: chạy luôn)
  disabled?: boolean;
  placeholder?: string;
  ariaLabel?: string;
}

const LIST_ID = "exam-combobox-listbox";

/**
 * Ô chọn mã bộ testcase — combobox tự viết (đẹp + tự đổi màu theo theme sáng/tối) thay cho
 * <input list>/<datalist> native vốn xấu và lỗi màu ở chế độ tối.
 * Gõ để lọc theo mã/tên bộ testcase; click hoặc ↑/↓ + Enter để chọn; vẫn nhập tay được mã bất kỳ.
 */
export default function ExamCombobox({ options, value, onChange, onEnter, disabled, placeholder, ariaLabel = "Mã bộ testcase" }: Props) {
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  // Đang GÕ để tìm hay chỉ MỞ danh sách? Đây là hai việc khác nhau. Trước đây lọc thẳng theo
  // `value`, mà sau khi chọn xong `value` chính là mã đã chọn — nên mở lại danh sách chỉ thấy
  // đúng một dòng, tưởng như các bộ testcase khác đã biến mất.
  const [typing, setTyping] = useState(false);
  // Vị trí danh sách trên màn hình — danh sách render qua PORTAL (xem chú thích dưới) nên phải
  // tự bám theo ô input thay vì dựa vào absolute trong cây DOM.
  const [rect, setRect] = useState<{ top: number; left: number; width: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const filtered = useMemo(() => {
    const q = value.trim().toLowerCase();
    if (!typing || !q) return options;
    return options.filter(
      (o) => o.examId.toLowerCase().includes(q) || (o.examName || "").toLowerCase().includes(q),
    );
  }, [options, value, typing]);

  // Chốt ô đang sáng trong khoảng hợp lệ (list co lại khi lọc) — tính lúc render, không cần effect.
  const hi = filtered.length ? Math.max(0, Math.min(highlight, filtered.length - 1)) : 0;

  // Đóng khi click ra ngoài. Danh sách nằm trong portal (ngoài wrapRef) nên phải kiểm CẢ HAI:
  // thiếu listRef thì mousedown lên option bị coi là "ra ngoài" → list đóng trước khi click kịp
  // chọn, và không option nào bấm được nữa.
  useEffect(() => {
    const h = (e: MouseEvent) => {
      const t = e.target as Node;
      if (wrapRef.current?.contains(t) || listRef.current?.contains(t)) return;
      setOpen(false); setTyping(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, []);

  // Bám theo ô input khi cuộn/đổi cỡ cửa sổ lúc danh sách đang mở (scroll bắt ở pha capture vì
  // vùng cuộn thật là <main> bên trong layout, không phải window).
  useEffect(() => {
    if (!open) return;
    const update = () => {
      const r = wrapRef.current?.getBoundingClientRect();
      if (r) setRect({ top: r.bottom, left: r.left, width: r.width });
    };
    update();
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [open]);

  /**
   * Giữ option đang sáng trong tầm nhìn khi dùng phím — cuộn TAY trong đúng khung danh sách.
   *
   * <p>Không dùng `scrollIntoView`: nó cuộn MỌI tổ tiên cuộn được, mà thẻ bọc ô này là
   * `card overflow-hidden` — overflow-hidden vẫn cuộn được bằng mã. Hậu quả đo được: mở danh sách
   * là cả phần đầu thẻ lẫn phần thân bị đẩy lên ~39px, trong khi vùng cuộn của trang không hề nhúc
   * nhích. Ở đây chỉ đụng `scrollTop` của chính danh sách nên không gì bên ngoài xê dịch.
   */
  useEffect(() => {
    const list = listRef.current;
    if (!open || !list) return;
    const el = list.querySelector<HTMLElement>(`[data-idx="${hi}"]`);
    if (!el) return;
    const top = el.offsetTop;
    const bottom = top + el.offsetHeight;
    if (top < list.scrollTop) list.scrollTop = top;
    else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight;
  }, [hi, open]);

  const choose = (o: ExamComboOption) => { onChange(o.examId); setOpen(false); setTyping(false); };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setOpen(true); setHighlight(Math.min(hi + 1, filtered.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setHighlight(Math.max(hi - 1, 0)); }
    else if (e.key === "Enter") {
      const opt = open ? filtered[hi] : undefined;
      if (opt && opt.examId !== value) { e.preventDefault(); choose(opt); }   // chọn option đang sáng
      else onEnter?.();                                                       // mã đã đúng → chạy luôn
    } else if (e.key === "Escape") { setOpen(false); setTyping(false); }
  };

  return (
    <div ref={wrapRef} className="relative">
      <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
      <input
        ref={inputRef}
        value={value}
        onChange={(e) => { onChange(e.target.value); setOpen(true); setTyping(true); setHighlight(0); }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        role="combobox"
        aria-label={ariaLabel}
        aria-expanded={open}
        aria-controls={LIST_ID}
        autoComplete="off"
        className="w-full rounded-lg border border-slate-200 bg-white py-2 pl-9 pr-9 text-sm font-medium text-slate-800 outline-none transition-colors focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 disabled:bg-slate-50"
      />
      <button
        type="button" tabIndex={-1} disabled={disabled}
        // preventScroll: mở danh sách KHÔNG được kéo trang. Focus mặc định cuộn phần tử vào tầm
        // nhìn, nên khi trang đang dài (đang chấm dở) thì cả khối thiết lập nhảy chỗ.
        onClick={() => { setOpen((o) => !o); setTyping(false); inputRef.current?.focus({ preventScroll: true }); }}
        aria-label="Mở danh sách mã bộ testcase"
        className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-1 text-slate-400 transition-colors hover:text-slate-600 disabled:opacity-40"
      >
        <ChevronDown size={16} className={`transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {/* PORTAL ra body: thẻ bọc ngoài là `card overflow-hidden` nên danh sách absolute bị CẮT
          ngay mép card — chỉ thấy được một mẩu. Render ở body với position:fixed thì danh sách
          nổi đè lên mọi khối phía dưới (Thêm bài làm, Hiệu năng chấm) bất kể overflow của tổ tiên. */}
      {open && !disabled && rect && createPortal(
        <div
          id={LIST_ID} ref={listRef} role="listbox"
          style={{ top: rect.top + 6, left: rect.left, width: rect.width }}
          className="custom-scrollbar fixed z-50 max-h-72 overflow-auto rounded-xl border border-slate-200 bg-white py-1 shadow-xl"
        >
          {filtered.length === 0 ? (
            <div className="px-3 py-6 text-center text-xs text-slate-400">
              {options.length === 0 ? "Chưa có bộ testcase nào đã chấm" : "Không khớp mã/tên bộ testcase nào"}
            </div>
          ) : (
            filtered.map((o, i) => {
              const isHi = i === hi;
              const isSel = o.examId === value;
              return (
                <button
                  type="button" key={o.examId} data-idx={i} role="option" aria-selected={isSel}
                  onMouseEnter={() => setHighlight(i)} onClick={() => choose(o)}
                  className={`flex w-full items-center gap-3 px-3 py-2 text-left transition-colors ${isHi ? "bg-slate-100" : "hover:bg-slate-50"}`}
                >
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-indigo-50 font-mono text-[11px] font-bold text-indigo-600">
                    {o.examId.slice(0, 2).toUpperCase()}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-mono text-sm font-semibold text-slate-700">{o.examId}</span>
                    {o.examName && <span className="block truncate text-xs text-slate-400">{o.examName}</span>}
                  </span>
                  {isSel && <Check size={15} className="shrink-0 text-indigo-500" />}
                </button>
              );
            })
          )}
        </div>,
        document.body
      )}
    </div>
  );
}
