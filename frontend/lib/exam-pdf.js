// frontend/lib/exam-pdf.js
// Xuất ĐỀ BÀI (de_bai — định dạng Markdown) ra PDF bằng cơ chế IN của trình duyệt.
//
// Vì sao IN trình duyệt thay vì tự sinh file PDF: đề bài có TIẾNG VIỆT — trình duyệt render
// font Unicode chuẩn 100% mà KHÔNG phải nhúng font (như iText/jsPDF) và KHÔNG thêm thư viện nào.
// Người dùng bấm nút → mở hộp thoại in → chọn "Lưu thành PDF" (Microsoft Print to PDF / Save as PDF).
// Tên file gợi ý lấy theo <title> của trang in = "{examId}_de_bai".

// Escape ký tự đặc biệt của HTML. RẤT QUAN TRỌNG vì đề bài đầy "List<...>", "Map<...>", "&".
function escapeHtml(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// Định dạng inline trên CHUỖI ĐÃ ESCAPE: `code`, **đậm**, *nghiêng*, [text](url).
// Cố ý BỎ QUA _nghiêng_ (gạch dưới): đề bài hay có định danh kiểu _items, nowProvider... dễ bị hiểu nhầm.
function inline(escaped) {
  // Tách chuỗi thành các đoạn xen kẽ: trong cặp backtick (code) và ngoài (văn bản thường).
  // Chỉ áp **đậm**/*nghiêng*/link cho đoạn văn bản — nội dung code giữ nguyên, tránh đụng nhầm.
  return escaped.split(/(`[^`]+`)/g).map((part) => {
    if (part.length >= 2 && part.charAt(0) === "`" && part.charAt(part.length - 1) === "`") {
      return "<code>" + part.slice(1, -1) + "</code>";
    }
    let s = part;
    s = s.replace(/\*\*([^*]+?)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/\*(?!\s)([^*]+?)(?<!\s)\*/g, "<em>$1</em>");
    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2">$1</a>');
    return s;
  }).join("");
}

function leadingWs(line) {
  const m = /^(\s*)/.exec(line);
  return m ? m[1].length : 0;
}

// Nhận diện 1 dòng có phải mục danh sách không: gạch đầu dòng (-, *, +, •, ◦, ▪, ‣, ·) hoặc "1." / "1)".
function matchItem(line) {
  if (line == null) return null;
  let m = /^(\s*)(?:[-*+]|[•◦▪‣·])\s+(.*)$/.exec(line);
  if (m) return { indent: m[1].length, ordered: false, content: m[2] };
  m = /^(\s*)\d+[.)]\s+(.*)$/.exec(line);
  if (m) return { indent: m[1].length, ordered: true, content: m[2] };
  return null;
}

// Đọc danh sách (ĐỆ QUY theo độ thụt lề) bắt đầu tại lines[start].
// Trả [htmlChuoi, chỉ_số_dòng_kế_tiếp]. Hỗ trợ lồng nhiều cấp & dòng trống xen giữa các mục
// (đề bài thường ngăn các nhóm "-" cấp 1 bằng 1 dòng trống — vẫn coi là CÙNG một danh sách).
function parseList(lines, start) {
  const first = matchItem(lines[start]);
  const base = first.indent;
  const type = first.ordered ? "ol" : "ul";
  let html = "<" + type + ">";
  let i = start;
  while (i < lines.length) {
    // Dòng trống: nhìn xuống dòng kế; nếu vẫn là mục cùng/sâu hơn cấp này thì danh sách còn tiếp.
    if (/^\s*$/.test(lines[i])) {
      let j = i + 1;
      while (j < lines.length && /^\s*$/.test(lines[j])) j++;
      const peek = j < lines.length ? matchItem(lines[j]) : null;
      if (peek && peek.indent >= base) { i = j; } else break;
    }
    const m = matchItem(lines[i]);
    if (!m || m.indent < base) break;        // hết danh sách / mục này thuộc về cấp cha
    if (m.indent > base) {                     // an toàn: gặp mục sâu hơn ngay đầu → coi là lồng
      const sub = parseList(lines, i);
      html += "<li>" + sub[0] + "</li>";
      i = sub[1];
      continue;
    }
    let li = inline(escapeHtml(m.content));
    i++;
    // Gom phần con của mục: danh sách lồng (thụt sâu hơn) hoặc dòng văn bản nối tiếp.
    let nested = "";
    while (i < lines.length) {
      if (/^\s*$/.test(lines[i])) {
        let j = i + 1;
        while (j < lines.length && /^\s*$/.test(lines[j])) j++;
        const peek = j < lines.length ? matchItem(lines[j]) : null;
        if (peek && peek.indent > base) { i = j; } else break;
      }
      const mm = matchItem(lines[i]);
      if (mm && mm.indent > base) {
        const sub = parseList(lines, i);
        nested += sub[0];
        i = sub[1];
      } else if (!mm && !/^\s*$/.test(lines[i]) && leadingWs(lines[i]) > base) {
        li += "<br>" + inline(escapeHtml(lines[i].trim()));   // dòng nối tiếp của mục
        i++;
      } else break;
    }
    html += "<li>" + li + nested + "</li>";
  }
  html += "</" + type + ">";
  return [html, i];
}

// Markdown → HTML (an toàn, đã escape). Hỗ trợ: heading, đường kẻ ngang, trích dẫn,
// khối code ```, danh sách lồng nhau, đoạn văn, và định dạng inline.
export function mdToHtml(md) {
  const lines = String(md == null ? "" : md).replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0;
  const isSpecial = (ln) =>
    /^\s*```/.test(ln) || /^(#{1,6})\s+/.test(ln) || /^\s*([-*_])\1{2,}\s*$/.test(ln) ||
    /^\s*>\s?/.test(ln) || matchItem(ln) != null;
  while (i < lines.length) {
    const line = lines[i];
    // Khối code ``` ... ```
    if (/^\s*```/.test(line)) {
      const buf = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) { buf.push(lines[i]); i++; }
      i++; // bỏ dòng ``` đóng
      out.push('<pre class="code"><code>' + escapeHtml(buf.join("\n")) + "</code></pre>");
      continue;
    }
    if (/^\s*$/.test(line)) { i++; continue; }                       // dòng trống
    let m = /^(#{1,6})\s+(.*)$/.exec(line);                          // heading
    if (m) { const lv = m[1].length; out.push("<h" + lv + ">" + inline(escapeHtml(m[2].trim())) + "</h" + lv + ">"); i++; continue; }
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) { out.push("<hr>"); i++; continue; }   // đường kẻ ngang (---, ***, ___)
    if (/^\s*>\s?/.test(line)) {                                     // trích dẫn
      const buf = [];
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) { buf.push(lines[i].replace(/^\s*>\s?/, "")); i++; }
      out.push("<blockquote>" + inline(escapeHtml(buf.join("\n"))).replace(/\n/g, "<br>") + "</blockquote>");
      continue;
    }
    if (matchItem(line)) { const r = parseList(lines, i); out.push(r[0]); i = r[1]; continue; }   // danh sách
    // Đoạn văn: gom các dòng liền nhau tới khi gặp dòng trống / khối đặc biệt.
    const para = [];
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !isSpecial(lines[i])) {
      para.push(inline(escapeHtml(lines[i].trim())));
      i++;
    }
    if (para.length) out.push("<p>" + para.join("<br>") + "</p>");
  }
  return out.join("\n");
}

// Dựng HTML hoàn chỉnh (kèm CSS in đẹp) cho trang đề bài.
function buildHtml(opts) {
  const fileTitle = (opts.examId ? opts.examId + "_" : "") + "de_bai";
  const name = escapeHtml(opts.examName && String(opts.examName).trim() ? String(opts.examName).trim() : "Đề bài");
  const meta = opts.examId ? '<div class="exam-meta">Mã đề: ' + escapeHtml(opts.examId) + "</div>" : "";
  const body = mdToHtml(opts.markdown);
  return '<!DOCTYPE html>\n' +
'<html lang="vi">\n' +
'<head>\n' +
'<meta charset="utf-8">\n' +
'<title>' + escapeHtml(fileTitle) + '</title>\n' +
'<style>\n' +
'  *{box-sizing:border-box}\n' +
'  html,body{margin:0;padding:0}\n' +
'  body{font-family:-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,"Noto Sans",sans-serif;color:#1e293b;font-size:13px;line-height:1.6}\n' +
'  .exam-head{border-bottom:2px solid #4f46e5;padding-bottom:10px;margin:0 0 18px}\n' +
'  .exam-title{margin:0 0 4px;font-size:20px;font-weight:800;color:#312e81}\n' +
'  .exam-meta{font-size:12px;color:#64748b;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}\n' +
'  .exam-body h1{font-size:18px}.exam-body h2{font-size:16px}.exam-body h3{font-size:14px}\n' +
'  .exam-body h4,.exam-body h5,.exam-body h6{font-size:13px}\n' +
'  .exam-body h1,.exam-body h2,.exam-body h3,.exam-body h4,.exam-body h5,.exam-body h6{margin:14px 0 6px;color:#1e293b;font-weight:700}\n' +
'  .exam-body p{margin:8px 0}\n' +
'  .exam-body ul,.exam-body ol{margin:6px 0;padding-left:22px}\n' +
'  .exam-body li{margin:3px 0}\n' +
'  .exam-body li>ul,.exam-body li>ol{margin:3px 0}\n' +
'  .exam-body code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;background:#f1f5f9;padding:1px 4px;border-radius:4px;font-size:.92em}\n' +
'  .exam-body pre.code{background:#f8fafc;border:1px solid #e2e8f0;border-radius:6px;padding:10px;overflow:auto;white-space:pre-wrap}\n' +
'  .exam-body pre.code code{background:none;padding:0}\n' +
'  .exam-body blockquote{margin:8px 0;padding:2px 12px;border-left:3px solid #c7d2fe;color:#475569}\n' +
'  a{color:#4f46e5;text-decoration:none}\n' +
'  @page{margin:18mm 16mm}\n' +
'  @media print{body{-webkit-print-color-adjust:exact;print-color-adjust:exact}}\n' +
'</style>\n' +
'</head>\n' +
'<body>\n' +
'  <header class="exam-head">\n' +
'    <h1 class="exam-title">' + name + '</h1>\n' +
'    ' + meta + '\n' +
'  </header>\n' +
'  <main class="exam-body">' + body + '</main>\n' +
'</body>\n' +
'</html>';
}

// Mở hộp thoại in cho đề bài (để người dùng "Lưu thành PDF").
// opts = { examId?, examName?, markdown }. In qua IFRAME ẩn: không rời trang, không bị popup-blocker.
export function openExamPdf(opts) {
  if (typeof window === "undefined" || !opts || !opts.markdown) return;
  const html = buildHtml(opts);
  const iframe = document.createElement("iframe");
  iframe.setAttribute("aria-hidden", "true");
  iframe.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden";
  const cleanup = () => { if (iframe.parentNode) iframe.parentNode.removeChild(iframe); };
  iframe.onload = () => {
    const win = iframe.contentWindow;
    const doc = iframe.contentDocument || (win && win.document);
    // Bỏ qua lần load "about:blank" lúc mới chèn iframe — chỉ in khi nội dung thật đã sẵn sàng.
    if (!win || !doc || !doc.querySelector(".exam-body")) return;
    try { win.focus(); win.print(); } catch { /* ignore */ }
    try { win.onafterprint = cleanup; } catch { /* ignore */ }
    setTimeout(cleanup, 60000);   // dọn iframe phòng khi onafterprint không bắn (vài trình duyệt)
  };
  document.body.appendChild(iframe);
  iframe.srcdoc = html;   // gán sau khi đã vào DOM để onload chạy đúng
}
