import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { Router } from "wouter";
import { TaskRow } from "./home";
import type { TaskSummary } from "@/lib/tasks";

it("uses the session topic as the row title for finished free-form jobs", () => {
  const t: TaskSummary = {
    taskId: "T-42", mode: "Freeform", phase: "Completed", goal: "Free-form help",
    title: "Diagnosing a switch cable", updatedAt: "2026-11-03T00:00:00Z",
    steps: 0, finished: 0, currentStepTitle: null, inspectionId: null, site: null,
  };
  const row = renderToStaticMarkup(<Router ssrPath="/m"><TaskRow t={t} /></Router>);
  expect(row).toContain("Diagnosing a switch cable");
  expect(row).toContain("/m/t/T-42/done");
});
