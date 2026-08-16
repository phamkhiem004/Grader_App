// Tiện ích xuất CSV dùng chung cho trang Chấm tự động và Lịch sử chấm.
//
// Hai trang cùng xuất bảng điểm nên cột và cách ghi phải giống hệt nhau — mỗi trang một bản sao
// là kiểu để hai file cùng tên mà lệch nội dung.

/** Bọc một ô CSV: chỉ thêm nháy khi cần, nháy trong nội dung được nhân đôi theo đúng chuẩn. */
export function csvCell(value: string | number | null | undefined): string {
  const s = value == null ? "" : String(value);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

export function csvRow(cells: (string | number | null | undefined)[]): string {
  return cells.map(csvCell).join(",");
}

/** Mốc thời gian đọc được cho người Việt: HH:mm:ss dd/MM/yyyy. Rỗng khi chưa có mốc. */
export function formatGradingTime(value: string | null | undefined): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} `
    + `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/**
 * Tải một chuỗi CSV về máy.
 *
 * <p>BOM ở đầu file là bắt buộc: thiếu nó thì Excel trên Windows đọc UTF-8 theo bảng mã ANSI và
 * tên sinh viên có dấu thành ký tự lạ.
 */
export function downloadCsv(content: string, filename: string) {
  const blob = new Blob(["﻿" + content], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
