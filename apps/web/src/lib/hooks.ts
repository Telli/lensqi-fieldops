import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, ApiError, isCancelled, type Assessment, type EventRecord, type Inspection } from "./api";

export const qk = {
  health: ["health"] as const,
  fixtures: ["fixtures"] as const,
  inspections: ["inspections"] as const,
  inspection: (id: string) => ["inspection", id] as const,
  events: (id: string) => ["events", id] as const,
  knowledge: (id: string) => ["knowledge", id] as const,
  artifacts: (id: string) => ["artifacts", id] as const,
};

export const useHealth = () => useQuery({ queryKey: qk.health, queryFn: api.health, refetchInterval: 30_000 });
export const useFixtures = () => useQuery({ queryKey: qk.fixtures, queryFn: api.fixtures, staleTime: 60_000 });
export const useInspections = () => useQuery({ queryKey: qk.inspections, queryFn: api.inspections, refetchInterval: 15_000 });
export const useInspection = (id: string, opts?: { enabled?: boolean }) => useQuery({ queryKey: qk.inspection(id), queryFn: () => api.inspection(id), enabled: opts?.enabled ?? true });
export const useEvents = (id: string) => useQuery({ queryKey: qk.events(id), queryFn: () => api.events(id) });
export const useKnowledge = (id: string) => useQuery({ queryKey: qk.knowledge(id), queryFn: () => api.knowledge(id), staleTime: 60_000 });
export const useArtifacts = (id: string) => useQuery({ queryKey: qk.artifacts(id), queryFn: () => api.artifacts(id) });

export function useFixture(fixtureId?: string) {
  const f = useFixtures();
  return { ...f, fixture: f.data?.find((x) => x.fixtureId === fixtureId) };
}

/** Last assessment recorded for the inspection, read from the event trail. */
export function latestAssessment(events?: EventRecord[]): (Assessment & { at: string; sequence: number }) | undefined {
  const e = [...(events ?? [])].reverse().find((x) => x.kind === "AssessmentRecorded");
  return e ? { ...(e["assessment"] as Assessment), at: e.occurredAt, sequence: e.sequence } : undefined;
}

export function friendlyError(e: unknown): string {
  if (e instanceof ApiError) {
    const map: Record<string, string> = {
      offline: "Can't reach the inspection server. Your work is safe; try again when you're connected.",
      cancelled: "Cancelled.",
      bad_response: "The server's answer could not be read. Try again.",
      rate_limited: "Too many AI requests for this inspection right now. Wait a minute and try again.",
      model_timeout: "The AI took too long to answer. Try again.",
      provider_unavailable: "The AI service is unavailable right now. Try again shortly.",
      image_too_small: "That photo is too small. Move closer or use a higher resolution.",
      image_unreadable: "That file isn't a readable photo.",
      change_evidence_rejected: "That photo wasn't accepted as post-repair evidence. Take a new one.",
      invalid_state: e.message,
    };
    return map[e.code] ?? e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/**
 * A command against an inspection: shows the server's answer, refreshes the views that depend on it. `signal()` is the
 * abort signal of the command in flight and `cancel()` aborts it; the server ignores a cancel once the model or probe has
 * answered, so the views refresh either way and a retry under the same key finds the recorded outcome.
 */
export function useAct<A extends unknown[], R>(id: string, fn: (...args: A) => Promise<R>, opts?: { success?: string | ((r: R) => string) }) {
  const qc = useQueryClient();
  const controller = useRef<AbortController | null>(null);
  // Leaving the screen ends the command here: no further call in a chain starts and no toast lands on the next screen.
  useEffect(() => () => controller.current?.abort(), []);
  const m = useMutation({
    mutationFn: (args: A) => { controller.current = new AbortController(); return fn(...args); },
    onSuccess: (r) => {
      const s = r as { state?: Inspection } | Inspection;
      if (s && "inspectionId" in (s as Inspection)) qc.setQueryData(qk.inspection(id), s);
      else if (s && "state" in s && s.state) qc.setQueryData(qk.inspection(id), s.state);
      if (opts?.success) toast.success(typeof opts.success === "function" ? opts.success(r) : opts.success);
    },
    onError: (e) => { if (!isCancelled(e)) toast.error(friendlyError(e)); },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: qk.inspection(id) });
      qc.invalidateQueries({ queryKey: qk.events(id) });
      qc.invalidateQueries({ queryKey: qk.artifacts(id) });
      qc.invalidateQueries({ queryKey: qk.inspections });
    },
  });
  return { ...m, signal: () => controller.current?.signal, cancel: () => controller.current?.abort() };
}

/** Whole seconds since `active` became true, 0 otherwise. Real elapsed time, never an estimate of progress. */
export function useElapsed(active: boolean) {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!active) { setSeconds(0); return; }
    const start = Date.now();
    const t = setInterval(() => setSeconds(Math.floor((Date.now() - start) / 1000)), 500);
    return () => clearInterval(t);
  }, [active]);
  return seconds;
}
