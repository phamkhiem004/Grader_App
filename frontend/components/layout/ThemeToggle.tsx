"use client";

import React, { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";

/** Nút bật/tắt giao diện sáng/tối cho toàn app. Lưu lựa chọn vào localStorage. */
export default function ThemeToggle() {
  const [dark, setDark] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    setDark(document.documentElement.classList.contains("dark"));
  }, []);

  const toggle = () => {
    const el = document.documentElement;
    const next = !el.classList.contains("dark");
    el.classList.toggle("dark", next);
    try {
      localStorage.setItem("theme", next ? "dark" : "light");
    } catch {}
    setDark(next);
  };

  return (
    <button
      onClick={toggle}
      aria-label={dark ? "Chuyển sang giao diện sáng" : "Chuyển sang giao diện tối"}
      title={dark ? "Giao diện sáng" : "Giao diện tối"}
      className="flex h-9 w-9 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-500 transition-all hover:border-indigo-300 hover:text-indigo-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:text-indigo-300"
    >
      {/* tránh lệch hydration: chỉ đổi icon sau khi mounted */}
      {mounted && dark ? <Sun size={18} /> : <Moon size={18} />}
    </button>
  );
}
