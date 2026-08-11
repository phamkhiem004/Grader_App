import { redirect } from "next/navigation";

/** URL cấu hình cũ được giữ để bookmark không hỏng; thao tác Sandbox nay nằm tại Kho bộ testcase. */
export default function TeacherSetupRedirect() {
  redirect("/teacher/archive");
}
