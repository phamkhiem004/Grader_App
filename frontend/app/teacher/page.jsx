import { redirect } from "next/navigation";

/** Trang giáo viên mặc định mở dashboard thống kê. */
export default function TeacherHomeRedirect() {
  redirect("/statistics");
}
