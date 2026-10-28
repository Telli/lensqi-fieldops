import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme, type ThemePref } from "@/lib/theme";
import { cn } from "@/lib/utils";

const options: { value: ThemePref; label: string; icon: typeof Sun }[] = [
  { value: "system", label: "System", icon: Monitor },
  { value: "light", label: "Light", icon: Sun },
  { value: "dark", label: "Dark", icon: Moon },
];

/** Appearance switch. "System" follows the phone or computer setting and changes with it. */
export function ThemeControl({ compact, className }: { compact?: boolean; className?: string }) {
  const [pref, setPref] = useTheme();
  return (
    <div role="radiogroup" aria-label="Appearance" className={cn("inline-flex rounded-lg border border-border bg-surface p-0.5", className)}>
      {options.map(({ value, label, icon: Icon }) => (
        <button key={value} role="radio" aria-checked={pref === value} title={label} onClick={() => setPref(value)}
          className={cn("flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs transition-colors",
            pref === value ? "bg-elevated text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground")}>
          <Icon className="size-3.5" />{!compact && label}
        </button>
      ))}
    </div>
  );
}
