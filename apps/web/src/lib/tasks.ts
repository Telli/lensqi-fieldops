import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ApiError, newKey, request } from "./api";
import { friendlyError } from "./hooks";

export type TaskMode = "Diy" | "Technician" | "Freeform";
export type TaskPhase = "Planned" | "InProgress" | "Completed" | "Abandoned";
export type RiskLevel = "Low" | "Moderate" | "High";
export type CautionKind = "Hot" | "Electrical" | "Lifting" | "Chemical" | "Height" | "Sharp" | "Pressure" | "Other";
export type StepStatus = "Todo" | "Active" | "Done" | "Skipped";
export type Verdict = "LooksDone" | "NotYet" | "CantTell";

export interface Caution { kind: CautionKind; text: string }
export interface StepCheck { stepId: string; photoId: string; verdict: Verdict; reason: string; modelId: string; at: string }
export interface TaskStep {
  id: string; title: string; instruction: string; handsOn: boolean; lookFor: string[]; cautions: Caution[]; stopIf: string[];
  check: { required: boolean; whatDoneLooksLike: string } | null; status: StepStatus; lastCheck: StepCheck | null;
  /** On a ladder or step-stool step: the part it reaches. */
  accessFor?: string | null;
  /** Why the step was skipped, with measured numbers when the phone took them. */
  skipReason?: { text: string; partHeightCm: number | null; reachCm: number | null } | null;
}
export interface TaskPlan {
  title: string; summary: string; risk: RiskLevel; estimatedMinutes: number; restricted: string; professionalRequired: boolean;
  professionalReason: string | null; tools: string[]; parts: string[]; sourceNote: string; modelId: string; needsSafetyAck: boolean; steps: TaskStep[];
}
export interface TaskPhoto { evidenceId: string; sha256: string; width: number; height: number; purpose: "StepCheck" | "Manual" | "Question"; stepId: string | null; recordedAt: string }
export interface TaskAnswer { stepId: string | null; question: string; answer: string; photoId: string | null; modelId: string; at: string }
export interface Task {
  taskId: string; mode: TaskMode; goal: string; details: string | null; site: { fixtureId: string; assetId: string } | null; phase: TaskPhase;
  currentStepId: string | null; inspectionId: string | null; createdAt: string; updatedAt: string; safetyAcknowledged: boolean; abandonReason: string | null;
  plan: TaskPlan | null; photos: TaskPhoto[]; answers: TaskAnswer[];
  person?: { heightCm: number | null; standingReachCm: number | null; tools: string[] } | null;
}
export interface TaskSummary {
  taskId: string; mode: TaskMode; goal: string; title: string | null; phase: TaskPhase; steps: number; finished: number; currentStepTitle: string | null;
  site: { fixtureId: string; assetId: string } | null; inspectionId: string | null; updatedAt: string;
}
export interface PlanError { code: string; message: string }

const T = (id: string) => `/v1/tasks/${encodeURIComponent(id)}`;

const pending = new Map<string, string>();
const retryable = new Set(["provider_unavailable", "invalid_model_output"]);

/**
 * Sends one command under a key that is kept until the server gives a final answer, so a retry after a lost response
 * or a server error replays the recorded outcome (and reuses a saved photo) instead of acting twice.
 */
export async function once<R>(command: string, send: (key: string) => Promise<R>): Promise<R> {
  const key = pending.get(command) ?? newKey();
  pending.set(command, key);
  try {
    const r = await send(key);
    pending.delete(command);
    return r;
  } catch (e) {
    // A refusal is final. An unreachable server, a server error or a failed model call may have applied the command
    // or saved its photo, so the retry keeps the key.
    if (e instanceof ApiError && e.status >= 400 && e.status < 500 && !retryable.has(e.code)) pending.delete(command);
    throw e;
  }
}

export const tasks = {
  list: (mode?: TaskMode) => request<TaskSummary[]>("GET", `/v1/tasks${mode ? `?mode=${mode}` : ""}`),
  get: (id: string) => request<Task>("GET", T(id)),
  create: (input: { mode: TaskMode; goal: string; details?: string; fixtureId?: string; assetId?: string; manuals?: File[]; heightCm?: number | null; tools?: string[] }) => {
    const f = new FormData();
    f.append("mode", input.mode); f.append("goal", input.goal);
    if (input.details) f.append("details", input.details);
    if (input.fixtureId && input.assetId) { f.append("fixtureId", input.fixtureId); f.append("assetId", input.assetId); }
    input.manuals?.forEach((m) => f.append("manual", m));
    // The person's height and tools, so the plan fits who is doing the job.
    if (input.heightCm) f.append("heightCm", String(input.heightCm));
    input.tools?.forEach((t) => f.append("tools", t));
    // A retried create returns the task the first attempt made, not a second one.
    return once(`create:${input.mode}:${input.goal}`, (k) => request<{ task: Task; planError: PlanError | null }>("POST", "/v1/tasks", undefined, f, k));
  },
  plan: (id: string) => request<Task>("POST", `${T(id)}/plan`),
  accept: (id: string, safetyAcknowledged: boolean) => once(`${id}:accept`, (k) => request<Task>("POST", `${T(id)}/accept`, { safetyAcknowledged }, undefined, k)),
  start: (id: string, stepId: string) => once(`${id}:start:${stepId}`, (k) => request<Task>("POST", `${T(id)}/steps/${stepId}:start`, undefined, undefined, k)),
  complete: (id: string, stepId: string) => once(`${id}:complete:${stepId}`, (k) => request<Task>("POST", `${T(id)}/steps/${stepId}:complete`, undefined, undefined, k)),
  skip: (id: string, stepId: string) => once(`${id}:skip:${stepId}`, (k) => request<Task>("POST", `${T(id)}/steps/${stepId}:skip`, undefined, undefined, k)),
  finish: (id: string) => once(`${id}:finish`, (k) => request<Task>("POST", `${T(id)}/finish`, undefined, undefined, k)),
  abandon: (id: string, reason?: string) => once(`${id}:abandon`, (k) => request<Task>("POST", `${T(id)}/abandon`, { reason }, undefined, k)),
  check: (id: string, stepId: string, photo: Blob) => {
    const f = new FormData(); f.append("image", photo, "step.jpg");
    return once(`${id}:check:${stepId}`, (k) => request<{ check: StepCheck & { source: string }; task: Task }>("POST", `${T(id)}/steps/${stepId}/check`, undefined, f, k));
  },
  ask: (id: string, question: string, frame?: Blob | null) => {
    const f = new FormData(); f.append("question", question);
    if (frame) f.append("image", frame, "frame.jpg");
    return once(`${id}:ask:${question}`, (k) => request<{ answer: string; focus: string[]; stepId: string | null; photoId: string | null; modelId: string }>("POST", `${T(id)}/ask`, undefined, f, k));
  },
  linkInspection: (id: string) => once(`${id}:link`, (k) => request<{ inspectionId: string; task: Task }>("POST", `${T(id)}/link-inspection`, undefined, undefined, k)),
  photoUrl: (id: string, evidenceId: string) => `${T(id)}/artifacts/${encodeURIComponent(evidenceId)}`,
};

export const taskKeys = { list: ["tasks"] as const, one: (id: string) => ["task", id] as const };

export function useTask(id: string) {
  return useQuery({ queryKey: taskKeys.one(id), queryFn: () => tasks.get(id) });
}

export function useTasks(mode?: TaskMode) {
  return useQuery({ queryKey: [...taskKeys.list, mode ?? "all"], queryFn: () => tasks.list(mode) });
}

/** A task command: the returned task replaces the cached one; errors become a toast in plain words. */
export function useTaskAct<A extends unknown[]>(id: string, fn: (...args: A) => Promise<Task>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: A) => fn(...args),
    onSuccess: (t) => qc.setQueryData(taskKeys.one(id), t),
    onError: (e) => { toast.error(friendlyError(e)); qc.invalidateQueries({ queryKey: taskKeys.one(id) }); },
    onSettled: () => qc.invalidateQueries({ queryKey: taskKeys.list }),
  });
}

export const riskTone: Record<RiskLevel, string> = { Low: "text-success", Moderate: "text-warning", High: "text-failure" };
export const cautionLabel: Record<CautionKind, string> = {
  Hot: "Hot", Electrical: "Electrical", Lifting: "Lifting", Chemical: "Chemical", Height: "Height", Sharp: "Sharp", Pressure: "Pressure", Other: "Caution",
};
export const verdictLabel: Record<Verdict, { text: string; tone: string }> = {
  LooksDone: { text: "Looks done", tone: "text-success" }, NotYet: { text: "Not yet", tone: "text-warning" }, CantTell: { text: "Can't tell", tone: "text-muted-foreground" },
};

export const stepIndex = (t: Task | undefined, stepId: string | null | undefined) => t?.plan?.steps.findIndex((s) => s.id === stepId) ?? -1;
