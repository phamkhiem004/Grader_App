# Đồng bộ KHO SKILL của bot theo syllabus HIỆN TẠI của grader (DB), rồi re-ingest RAG.
# Dùng SAU KHI sửa "Khung năng lực" để bot không lệch khung kiến thức với grader.
# Backend phải đang chạy. Chạy:  .\.venv\Scripts\python.exe scripts\sync_bot_skills.py [API_BASE]
import json, sys, io, os, urllib.request, subprocess

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # = feedback-bot


def default_api():
    # Ưu tiên đúng cổng backend mà start-all đã ghi cho frontend (.env.local).
    envf = os.path.join(ROOT, "..", "frontend", ".env.local")
    try:
        for line in open(envf, encoding="utf-8-sig"):
            if line.strip().startswith("NEXT_PUBLIC_API_BASE"):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return "http://localhost:8080/api"


api = (sys.argv[1] if len(sys.argv) > 1 else default_api()).rstrip("/")
with urllib.request.urlopen(api + "/syllabus", timeout=10) as r:
    syl = json.loads(r.read().decode("utf-8"))   # tree: categories[].skills[]

ver = (syl.get("meta") or {}).get("version") or "hiện hành"
lines = [
    f"# Bản đồ skill_code SYLLABUS ({ver}) — dùng để nhận xét đúng kỹ năng\n",
    "Mỗi mục là MỘT skill_code HIỆN HÀNH (đúng mã hệ thống chấm dùng). Khi nhận xét, bám vào "
    "skill_code trong kết quả chấm để biết em đã đạt / chưa đạt kỹ năng nào.\n",
]
n = 0
for c in syl.get("categories", []):
    cname = c.get("name") or c.get("code")
    for s in c.get("skills", []):
        code = (s.get("code") or "").strip()
        if not code:
            continue
        n += 1
        name = s.get("name") or code
        desc = (s.get("description") or "").strip() or name
        lines += [
            "\n---\n", f"## skill_code: `{code}`",
            f"**Tên kỹ năng:** {name} · **Nhóm năng lực:** {cname} (`{c.get('code')}`)", "",
            "### Ý nghĩa", desc, "",
            "### Khi viết nhận xét",
            f"- `{code}` PASS → ghi nhận em đã nắm vững “{name}”.",
            f"- `{code}` FAIL/ERROR → nêu em cần củng cố “{name}”; gợi ý ôn lại nhóm “{cname}”.", "",
            f"### Từ khóa\n{code}, {name}, {cname}",
        ]
out = os.path.join(ROOT, "data", "rag_docs", "skills", "10_skillcode_syllabus_hien_hanh.md")
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print(f"Đã đồng bộ {n} skill_code từ {api}/syllabus. Đang re-ingest RAG...")
subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "ingest_rag.py")], cwd=ROOT)
print("Xong. Khởi động lại bot để dùng index mới (đóng/mở cửa sổ bot, hoặc .\\stop rồi .\\run).")
