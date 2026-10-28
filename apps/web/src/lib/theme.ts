import { useEffect, useState } from "react";

export type ThemePref = "system" | "light" | "dark";
const KEY = "lensqi-theme";
const media = () => (typeof matchMedia === "undefined" ? null : matchMedia("(prefers-color-scheme: dark)"));

/** The saved choice; "system" follows the device. Storage can be blocked (private mode), so every access is guarded. */
export function readTheme(): ThemePref {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

export function applyTheme(pref: ThemePref) {
  const dark = pref === "dark" || (pref === "system" && !!media()?.matches);
  document.documentElement.classList.toggle("dark", dark);
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? "#0b0f14" : "#f5f7fa");
}

/** Called once at startup: applies the saved choice and keeps "system" in step with the device setting. */
export function initTheme() {
  // ?theme=light|dark comes from the Android app's embedded receipt, so the page matches the app; it is not saved.
  const forced = new URLSearchParams(location.search).get("theme");
  if (forced === "light" || forced === "dark") return applyTheme(forced);
  applyTheme(readTheme());
  media()?.addEventListener("change", () => { if (readTheme() === "system") applyTheme("system"); });
}

export function useTheme() {
  const [pref, setPref] = useState<ThemePref>(readTheme);
  useEffect(() => {
    try { if (pref === "system") localStorage.removeItem(KEY); else localStorage.setItem(KEY, pref); } catch { /* not persisted */ }
    applyTheme(pref);
  }, [pref]);
  return [pref, setPref] as const;
}
