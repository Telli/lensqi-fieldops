import { useQuery } from "@tanstack/react-query";
import { api, artifactUrl, type EventRecord, type Inspection, type SegmentationGeometry } from "./api";
import { latestAssessment, qk, useArtifacts, useEvents, useFixture, useInspection } from "./hooks";

/** Everything an inspection screen draws, from the server only. */
export function useInspectionBundle(id: string) {
  const insp = useInspection(id);
  const events = useEvents(id);
  const artifacts = useArtifacts(id);
  const { fixture } = useFixture(insp.data?.fixtureId);
  const assessment = latestAssessment(events.data);
  const photoUrl = (evidenceId?: string | null) => {
    if (!evidenceId) return undefined;
    const name = artifacts.data?.find((n) => n.startsWith(`${evidenceId}-`) && !n.includes("-crop-"));
    return name ? artifactUrl(id, name) : undefined;
  };
  return { insp, events, artifacts, fixture, assessment, photoUrl, i: insp.data, ev: events.data ?? [] };
}

/** Stored SAM outline for a photo (the newest matched segmentation of it), if any. */
export function useGeometry(id: string, inspection?: Inspection, photoEvidenceId?: string | null) {
  const seg = [...(inspection?.segmentations ?? [])].reverse().find((s) => s.sourcePhotoEvidenceId === photoEvidenceId && s.matched);
  return useQuery<SegmentationGeometry | null>({
    queryKey: ["geometry", id, seg?.evidenceId ?? "none"],
    queryFn: () => (seg ? api.geometry(id, seg.evidenceId) : Promise.resolve(null)),
    enabled: !!inspection,
    staleTime: Infinity,
  });
}

export const firstPhoto = (i?: Inspection) => i?.photos[0];
export const lastPhoto = (i?: Inspection) => i?.photos[i.photos.length - 1];
export const latestDiagnostic = (i?: Inspection) => i?.diagnostics[i.diagnostics.length - 1];
/** Photos taken before the current claim (the "before" side of the story). */
export const beforePhoto = (i?: Inspection) =>
  i?.repairClaimedAt ? [...i.photos].reverse().find((p) => new Date(p.observedAt) <= new Date(i.repairClaimedAt!)) ?? i.photos[0] : i?.photos[i.photos.length - 1];
export const verifications = (ev: EventRecord[]) => ev.filter((e) => e.kind === "VerificationEvaluated");
export const isClosed = (i?: Inspection) => i?.stage === "VerifiedComplete" || i?.stage === "Inconclusive";
export { qk };
