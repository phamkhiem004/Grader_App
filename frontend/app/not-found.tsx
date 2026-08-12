import ErrorScreen from "@/components/ui/ErrorScreen";

/**
 * 404 — hiện khi URL không khớp route nào, hoặc khi một trang gọi notFound().
 * Không nhận props (quy ước của Next.js).
 */
export default function NotFound() {
  return <ErrorScreen kind="notFound" variant="page" />;
}
