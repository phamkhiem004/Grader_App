# prune-grader.ps1 — Dọn rác Docker AN TOÀN cho app chấm thi
# Chỉ xóa: image dangling (<none>) + build cache. KHÔNG đụng volume / dự án khác.
Write-Host "== Trước ==" -ForegroundColor Cyan
docker system df

docker image prune -f        | Out-Null   # xóa image <none> (vd đề build lại)
docker builder prune -f      | Out-Null   # xóa build cache cũ

Write-Host "`n== Sau ==" -ForegroundColor Green
docker system df

Write-Host "`n== Ảnh đề hiện có (xóa đề không dùng: docker rmi grading-env-<maDe>) ==" -ForegroundColor Yellow
docker images "grading-env-*" --format "  {{.Repository}}  ({{.Size}})"
Write-Host "  (Mỗi ảnh đề dùng CHUNG layer nền ~4.5GB — không nhân lên theo số đề.)"
