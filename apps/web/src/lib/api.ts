// Typed client for the FieldOps inspection API. The server owns every stage and decision; this module only
// sends commands (each with its own Idempotency-Key) and returns the server's view. Error codes are surfaced as-is.

export type Stage =
  | "Created" | "InitialScan" | "NeedsEvidence" | "FaultSuspected" | "FaultConfirmed"
  | "AwaitingRepair" | "ReadyToVerify" | "VerificationFailed" | "VerifiedComplete" | "Inconclusive";
export type Origin = "Phone" | "Glasses" | "RecordedFixture" | "MockWearables" | "Technician" | string;
export type ObservationStatus = "Observed" | "Suspected" | "Unknown" | "Conflicting";
export type NextAction = "RequestView" | "SegmentRegion" | "RunApprovedProbe" | "RequestHumanReview" | "PrepareWorkOrder" | "StopInconclusive";
export type ChecklistStatus = "Satisfied" | "NotSatisfied" | "Unknown" | "Conflicting";
export type DiagnosticSource = "LiveLocal" | "Simulated" | "RecordedReplay" | "VendorReadOnly";
export type OutcomeKind = "VerifiedCompleteLive" | "VerifiedCompleteSimulated";
export type ChangeVerdict = "Changed" | "NoVisualChange" | "DuplicateImage" | "Unscorable";

export interface Health { status: string; packs: number; fixtures: number; diagnosticsMode: DiagnosticSource }
export interface FixtureAsset { assetId: string; kind: string; displayName: string }
export interface FixtureDiagnostic { diagnosticId: string; type: string; targetAlias: string; claimVerified: string }
export interface VisualRequirement { requirementId: string; mandatory: boolean; segmentationPrompt?: string | null }
export interface Fixture {
  fixtureId: string; fixtureRevision: number; sha256: string; fixtureDescription: string;
  assets: FixtureAsset[]; diagnostics: FixtureDiagnostic[]; visualRequirements: VisualRequirement[]; connections?: FixtureConnection[];
}
/** The site's expected installation state: what should be plugged where. Documentation of intent, not proof. */
export interface FixtureConnection { connectionId: string; fromAssetId: string; fromPortLabel: string; toAssetId: string; cableLabel: string; expectedState: string }
export interface EvidenceRequest { requestId: string; note: string; requestedBy: string; requestedAt: string; answeredByPhotoId?: string | null; answeredAt?: string | null }
export interface ImageQuality { blurLaplacianVariance: number; meanLuminance: number; analyzerVersion: string }
export interface Photo { evidenceId: string; origin: Origin; artifactSha256: string; observedAt: string; quality?: ImageQuality | null }
export interface DiagnosticResult {
  fixtureId: string; diagnosticId: string; targetAlias: string; passed: boolean | null; completedAt: string;
  source: DiagnosticSource; traceId: string; normalizedValue?: string | null; rawDigest?: string | null;
}
export interface Decision {
  result: "Verified" | "Denied" | "NeedsHumanReview"; outcomeKind: OutcomeKind | null; reasons: string[];
  verifiedClaims: string[]; policyVersion: string; fixtureDigest: string; isVerified: boolean;
}
export interface ChangeAnalysis {
  claimId: string; photoEvidenceId: string; referencePhotoEvidenceId?: string | null; targetPrompt?: string | null;
  perceptualHashDistance?: number | null; duplicateOfEvidenceId?: string | null; targetRegionIoU?: number | null;
  componentShiftNormalized?: number | null; verdict: ChangeVerdict; reason: string; thresholdsVersion: string;
}
export interface Segmentation { evidenceId: string; sourcePhotoEvidenceId: string; concept: string; matched: boolean; maskSha256?: string | null }
export interface Inspection {
  inspectionId: string; fixtureId: string; fixtureRevision: number; fixtureDigest: string; assetId: string; stage: Stage;
  lastSequence: number; viewRequests: number; currentClaimId?: string | null; repairClaimedAt?: string | null; technicianAcknowledged: boolean;
  photos: Photo[]; diagnostics: { evidenceId: string; result: DiagnosticResult; source: DiagnosticSource }[];
  checklist: { requirementId: string; status: ChecklistStatus; reviewedAt?: string; reviewedByEvidenceId?: string | null }[];
  segmentations: Segmentation[]; changeAnalyses: ChangeAnalysis[]; acceptedPostRepairPhotoId?: string | null;
  lastDecision?: Decision | null; outcomeKind?: OutcomeKind | null; evidenceRequests?: EvidenceRequest[];
}
export interface InspectionSummary {
  inspectionId: string; fixtureId: string; assetId: string; stage: Stage; outcomeKind?: OutcomeKind | null;
  createdAt: string; updatedAt: string; photos: number; diagnostics: number; lastDecision?: Decision["result"] | null;
  latestChange?: ChangeVerdict | null; currentClaimId?: string | null; workOrder?: string | null; claim?: string | null;
  verificationAttempts?: number; openEvidenceRequest?: EvidenceRequest | null;
}
export interface Hypothesis { featureId: string; status: ObservationStatus; reason: string; evidenceIds: string[] }
export interface Assessment {
  assetId: string; hypotheses: Hypothesis[]; nextAction: NextAction; requestedView?: string | null;
  requestedSegmentationConcept?: string | null; modelId: string; schemaVersion: string;
}
export interface InvestigateResponse {
  evidenceId: string; modelId: string; promptDigest: string; responseDigest: string;
  usage?: { inputTokenCount?: number; outputTokenCount?: number } | null; assessment: Assessment; state: Inspection;
}
export interface Box { objectId: string; left: number; top: number; right: number; bottom: number; confidence?: number | null }
export interface Mask { objectId: string; width: number; height: number; left: number; top: number; right: number; bottom: number; rle: { size: [number, number]; counts: string }; confidence?: number | null }
export interface SegmentationGeometry {
  segmentationId: string; photoEvidenceId: string; concept: string; model: string; elapsedSeconds: number; matched: boolean;
  boxes: Box[]; masks: Mask[]; crop?: { file: string } | null; label: string; purpose?: string;
}
export interface ObservationResponse { evidenceId: string; sha256: string; width: number; height: number; quality?: ImageQuality | null; changeAnalysis?: ChangeAnalysis | null; state: Inspection }
export interface KnowledgeFact {
  factId: string; factKey: string; assertedValue: string; statement: string; claimKind: string; provenance: string; safety: string; tags: string[];
  citation: { documentTitle: string; publisher: string; sourceUrl?: string | null; sourceLocator: string; accessedOn: string };
}
export interface Knowledge { inspectionId: string; status: string; packId?: string | null; packSha256?: string | null; packRevision?: number | null; facts: KnowledgeFact[]; warnings: string[] }
export interface EventRecord { kind: string; sequence: number; occurredAt: string; [k: string]: unknown }
export interface ReceiptCheck { bundle: string; isValid: boolean; checks: string[]; failures: string[] }
export interface PackDocument { documentId: string; publisher: string; title: string; url?: string | null; documentVersion?: string | null; accessedOn: string; rightsMode: string; factCount: number }
export interface KnowledgePack {
  packId: string; packRevision: number; originKind: string; sha256: string; manufacturerId: string; productCode: string;
  firmwareMajor?: string | null; factCount: number; documentCount: number; loadedAt: string; hardwareRevisions: string[]; documents: PackDocument[];
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

const key = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);

export async function request<T>(method: string, url: string, body?: unknown, form?: FormData): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method === "POST") headers["Idempotency-Key"] = key();
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let r: Response;
  try {
    r = await fetch(url, { method, headers, body: form ?? (body !== undefined ? JSON.stringify(body) : undefined) });
  } catch {
    throw new ApiError(0, "offline", "The inspection server cannot be reached. Check your connection.");
  }
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (!r.ok) throw new ApiError(r.status, data?.code ?? `http_${r.status}`, data?.message ?? r.statusText);
  return data as T;
}

const I = (id: string) => `/v1/inspections/${encodeURIComponent(id)}`;

export const api = {
  health: () => request<Health>("GET", "/health"),
  fixtures: () => request<Fixture[]>("GET", "/v1/fixtures"),
  packs: () => request<KnowledgePack[]>("GET", "/api/vendors/knowledge-packs"),
  inspections: () => request<InspectionSummary[]>("GET", "/v1/inspections"),
  create: (fixtureId: string, assetId: string) => request<Inspection>("POST", "/v1/inspections", { fixtureId, assetId }),
  inspection: (id: string) => request<Inspection>("GET", I(id)),
  events: (id: string) => request<EventRecord[]>("GET", `${I(id)}/events`),
  artifacts: (id: string) => request<string[]>("GET", `${I(id)}/artifacts`),
  geometry: (id: string, segmentationId: string) => request<SegmentationGeometry>("GET", `${I(id)}/artifacts/${segmentationId}.json`),
  upload: (id: string, file: Blob, name: string, source: Origin) => {
    const f = new FormData();
    f.append("image", file, name);
    f.append("source", source);
    f.append("observedAt", new Date().toISOString());
    return request<ObservationResponse>("POST", `${I(id)}/observations`, undefined, f);
  },
  investigate: (id: string) => request<InvestigateResponse>("POST", `${I(id)}/investigate`),
  segment: (id: string, concept?: string, photoEvidenceId?: string) => request<{ segmentation: SegmentationGeometry; state: Inspection }>("POST", `${I(id)}/segment`, { concept, photoEvidenceId }),
  knowledge: (id: string) => request<Knowledge>("GET", `${I(id)}/knowledge`),
  runDiagnostic: (id: string, diagnosticId: string) => request<{ result: DiagnosticResult; state: Inspection }>("POST", `${I(id)}/diagnostics/${encodeURIComponent(diagnosticId)}:run`),
  workOrder: (id: string, summary: string) => request<Inspection>("POST", `${I(id)}/work-order`, { summary }),
  claim: (id: string, description: string) => request<{ claimId: string; state: Inspection }>("POST", `${I(id)}/repair-claims`, { description }),
  review: (id: string, requirementId: string, status: ChecklistStatus, photoEvidenceId: string) =>
    request<Inspection>("POST", `${I(id)}/checklist`, { requirementId, status, photoEvidenceId }),
  requestEvidence: (id: string, note: string) => request<Inspection>("POST", `${I(id)}/evidence-requests`, { note }),
  acknowledge: (id: string) => request<Inspection>("POST", `${I(id)}/acknowledge`),
  verify: (id: string) => request<{ decision: Decision; state: Inspection }>("POST", `${I(id)}/verify`),
  receipt: (id: string) => request<Record<string, unknown>>("GET", `${I(id)}/receipt`),
  verifyReceipt: (id: string) => request<ReceiptCheck>("GET", `${I(id)}/receipt/verify`),
  simulate: (diagnosticId: string, passed: boolean) => request<unknown>("POST", `/v1/simulation/diagnostics/${encodeURIComponent(diagnosticId)}`, { passed }),
  simulation: () => request<{ mode: string; outcomes: Record<string, boolean | null> }>("GET", "/v1/simulation/diagnostics"),
};

export const artifactUrl = (id: string, name: string) => `${I(id)}/artifacts/${encodeURIComponent(name)}`;
export const receiptHtmlUrl = (id: string) => `${I(id)}/receipt.html`;
export const receiptJsonUrl = (id: string) => `${I(id)}/receipt`;
