import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import type { Stage } from "@/lib/api";
import { DeclineViewAction } from "./investigate";

const render = (stage: Stage, awaiting = true) => renderToStaticMarkup(<DeclineViewAction stage={stage} awaiting={awaiting} onConfirm={() => {}} />);

it("offers to decline the view only while LensQi is waiting for one", () => {
  expect(render("NeedsEvidence", false)).toBe("");
  expect(render("NeedsEvidence")).toContain("safely get this view");
  for (const stage of ["Created", "InitialScan", "FaultSuspected", "FaultConfirmed", "AwaitingRepair", "ReadyToVerify", "VerificationFailed", "VerifiedComplete", "Inconclusive"] as Stage[])
    expect(render(stage)).toBe("");
});

it("starts as a quiet secondary action, not a confirm step", () => {
  const html = render("NeedsEvidence");
  expect(html).not.toContain("Close as inconclusive");
  expect(html).not.toContain("Keep going");
});
