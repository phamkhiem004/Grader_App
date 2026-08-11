import { redirect } from "next/navigation";

/** Trang đầu của hệ thống luôn mở dashboard thống kê. */
export default function HomeRedirect() {
  redirect("/statistics");
}
