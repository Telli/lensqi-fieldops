/** Height and tools the person gave, kept in this browser and sent with each new task so the plan fits them. */
export interface Person { heightCm: number | null; tools: string[] }

export const toolChoices = ["step stool", "ladder"] as const;
const KEY = "lensqi.person";

/** A height as people say it: 6'2", 6 ft 2, 74 in, 188, 188 cm or 1.88 m. Null when unreadable or outside 90-250 cm. */
export function parseHeightCm(text: string): number | null {
  const t = text.trim().toLowerCase().replace(/[’′]/g, "'").replace(/[”″]/g, '"');
  if (!t) return null;
  let cm: number | null = null;
  const feet = /^(\d)\s*(?:'|ft|feet|foot)\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:"|''|in|inches|inch)?)?$/.exec(t);
  if (feet) cm = (Number(feet[1]) * 12 + Number(feet[2] ?? 0)) * 2.54;
  else if (/^\d{2,3}(?:\.\d+)?\s*(?:"|in|inches|inch)$/.test(t)) cm = parseFloat(t) * 2.54;
  else if (/^\d(?:\.\d+)?\s*m$/.test(t)) cm = parseFloat(t) * 100;
  else if (/^\d{2,3}\s*(?:cm)?$/.test(t)) cm = parseFloat(t);
  else if (/^1\.\d+$/.test(t)) cm = parseFloat(t) * 100;
  if (cm === null) return null;
  const r = Math.round(cm);
  return r >= 90 && r <= 250 ? r : null;
}

export function loadPerson(): Person {
  try { const p = JSON.parse(localStorage.getItem(KEY) ?? "null"); if (p && Array.isArray(p.tools)) return p; } catch { /* storage unavailable */ }
  return { heightCm: null, tools: [] };
}

export function savePerson(p: Person) { try { localStorage.setItem(KEY, JSON.stringify(p)); } catch { /* storage unavailable */ } }
