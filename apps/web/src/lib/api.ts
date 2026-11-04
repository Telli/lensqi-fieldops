// Typed client for the FieldOps inspection API. The server owns every stage and decision; this module only
// sends commands (each under an Idempotency-Key kept until the server answers, see `once`) and returns the server's
// view. Error codes are surfaced as-is.

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
  /** The status a live server answered with; absent when nothing answered. */
  httpStatus?: number | null;
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
  lastSequence: number; viewRequests: number; awaitingRequestedView?: boolean; currentClaimId?: string | null; repairClaimedAt?: string | null; technicianAcknowledged: boolean;
  photos: Photo[]; diagnostics: { evidenceId: string; result: DiagnosticResult; source: DiagnosticSource }[];
  checklist: { requirementId: string; status: ChecklistStatus; reviewedAt?: string; reviewedByEvidenceId?: string | null }[];
  segmentations: Segmentation[]; changeAnalyses: ChangeAnalysis[]; acceptedPostRepairPhotoId?: string | null;
  /** Newer post-repair photos still being compared with the earlier view; verification waits while any remain. */
  awaitingAnalysis?: string[];
  lastDecision?: Decision | null; outcomeKind?: OutcomeKind | null; evidenceRequests?: EvidenceRequest[];
  /** The server's verification gate: the next step and every reason verify cannot run yet. */
  readiness?: Readiness;
}
export type NextVerifyAction = "None" | "ClaimRepair" | "CapturePhoto" | "WaitForAnalysis" | "ReviewChecklist" | "Acknowledge" | "Verify";
export interface ReadinessCheck {
  diagnosticId: string; lastRunAt?: string | null; source?: DiagnosticSource | null; passed?: boolean | null; value?: string | null; afterClaim: boolean;
}
/** Ready never means verified: verify measures every fixture check again and decides on that measurement. */
export interface Readiness { ready: boolean; next: NextVerifyAction; blocking: string[]; checks: ReadinessCheck[]; measuresOnVerify: boolean }
export interface ReceiptIssue { manifestSha256: string; eventCount: number; issuedAt: string; receiptVersion: string; number: number }
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
  /** The stored photo's pixel size, the frame every box and mask is placed in (a JPEG's size before EXIF rotation). */
  photoWidth?: number | null; photoHeight?: number | null;
  boxes: Box[]; masks: Mask[]; crop?: { file: string; transform?: { sourceWidth: number; sourceHeight: number } | null } | null; label: string; purpose?: string;
}
export interface ObservationResponse { evidenceId: string; sha256: string; /** Digest of the bytes as uploaded; differs from sha256 when the server stored the photo turned upright. */ uploadedSha256?: string; width: number; height: number; quality?: ImageQuality | null; changeAnalysis?: ChangeAnalysis | null; state: Inspection }
export interface KnowledgeFact {
  factId: string; factKey: string; assertedValue: string; statement: string; claimKind: string; provenance: string; safety: string; tags: string[];
  citation: { documentTitle: string; publisher: string; sourceUrl?: string | null; sourceLocator: string; accessedOn: string };
}
/** A pack fact that was not applied to this unit, with the resolver's reason code (see `excludedReason` in labels). */
export interface KnowledgeExclusion { factId: string; verdict: "Applicable" | "Unverified" | "Inapplicable" | string; reason: string }
export interface Knowledge {
  inspectionId: string; status: string; packId?: string | null; packSha256?: string | null; packRevision?: number | null;
  /** "Synthetic" marks a demo pack written for the lab, not a vendor document; "VendorLinked" cites the vendor's own pages. */
  originKind?: string | null;
  facts: KnowledgeFact[]; excluded: KnowledgeExclusion[]; warnings: string[]; approvedDiagnostics?: string[];
}
export interface EventRecord { kind: string; sequence: number; occurredAt: string; [k: string]: unknown }
export interface ProposalRejection { code: string; message: string; offendingIds: string[] }
export interface ReasonResponse {
  accepted: boolean; proposalId: string; modelId: string; replayed: boolean; rejections: ProposalRejection[];
  proposal?: { nextAction: NextAction; hypothesis?: string | null; requestedVisualEvidence?: string | null; usedFactIds?: string[]; proposedDiagnosticId?: string | null } | null;
  prediction?: { predictionId: string; diagnosticId: string; predictedValue: string; basedOnFactIds: string[]; rationale?: string | null } | null;
  state: Inspection;
}
export interface ReceiptCheck { bundle: string; isValid: boolean; checks: string[]; failures: string[]; issued?: ReceiptIssue }
export interface PackDocument { documentId: string; publisher: string; title: string; url?: string | null; documentVersion?: string | null; accessedOn: string; rightsMode: string; factCount: number }
export interface KnowledgePack {
  packId: string; packRevision: number; originKind: string; sha256: string; manufacturerId: string; productCode: string;
  firmwareMajor?: string | null; factCount: number; documentCount: number; loadedAt: string; hardwareRevisions: string[]; documents: PackDocument[];
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

export const newKey = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);
export const isCancelled = (e: unknown) => e instanceof ApiError && e.code === "cancelled";

export interface RequestOptions { signal?: AbortSignal }

type Body = { code?: string; message?: string } | null;

/** The body as JSON: null when empty, `unreadable` for a proxy's HTML error page or a cut-off body, never a thrown SyntaxError. */
const unreadable = Symbol("unreadable");
function parseBody(text: string): Body | typeof unreadable {
  if (!text) return null;
  try { return JSON.parse(text); } catch { return unreadable; }
}

/**
 * A POST carries an Idempotency-Key: the caller's, so a retry of the same command is recognised, or a fresh one.
 * An aborted signal ends the call with the `cancelled` code; the server may still finish the command, and the caller's
 * key is how a later retry finds its answer.
 */
export async function request<T>(method: string, url: string, body?: unknown, form?: FormData, idempotencyKey?: string, opts?: RequestOptions): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method === "POST") headers["Idempotency-Key"] = idempotencyKey ?? newKey();
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let r: Response;
  let text: string;
  try {
    r = await fetch(url, { method, headers, body: form ?? (body !== undefined ? JSON.stringify(body) : undefined), signal: opts?.signal });
    text = await r.text();
  } catch (e) {
    if (opts?.signal?.aborted || (e instanceof Error && e.name === "AbortError")) throw new ApiError(0, "cancelled", "Cancelled.");
    throw new ApiError(0, "offline", "The inspection server cannot be reached. Check your connection.");
  }
  const parsed = parseBody(text);
  const data = parsed === unreadable ? null : parsed;
  if (!r.ok) throw new ApiError(r.status, data?.code ?? `http_${r.status}`, data?.message ?? (r.statusText || `The server answered with status ${r.status}.`));
  if (parsed === unreadable) throw new ApiError(r.status, "bad_response", "The server's answer could not be read.");
  return data as T;
}

const pending = new Map<string, string>();
const retryable = new Set(["provider_unavailable", "invalid_model_output", "model_timeout"]);

/**
 * Sends one command under a key that is kept until the server gives a final answer, so a retry after a lost response,
 * a cancel or a server error replays the recorded outcome (and reuses a saved photo) instead of acting twice. The
 * command name must say what the outcome depends on (the photo, the claim, the answers), never the event sequence:
 * a command's own effect advances that.
 */
export async function once<R>(command: string, send: (key: string) => Promise<R>): Promise<R> {
  const key = pending.get(command) ?? newKey();
  pending.set(command, key);
  try {
    const r = await send(key);
    pending.delete(command);
    return r;
  } catch (e) {
    // A refusal is final. An unreachable server, a cancel, a server error or a failed model call may have applied the
    // command or saved its photo, so the retry keeps the key.
    if (e instanceof ApiError && e.status >= 400 && e.status < 500 && !retryable.has(e.code)) pending.delete(command);
    throw e;
  }
}

/**
 * What a verification-round command's outcome depends on: the claim, the accepted photo, every review answer and the
 * confirmation. A retry under the same scope replays the recorded outcome; once any of these changed it is a new command.
 */
export function commandScope(i?: Inspection | null): string {
  if (!i) return "";
  const review = i.checklist.map((c) => `${c.requirementId}=${c.status}:${c.reviewedByEvidenceId ?? ""}`).sort().join(",");
  return `claim=${i.currentClaimId ?? ""};photo=${i.acceptedPostRepairPhotoId ?? ""};review=${review};ack=${i.technicianAcknowledged}`;
}

const I = (id: string) => `/v1/inspections/${encodeURIComponent(id)}`;

export const api = {
  health: () => request<Health>("GET", "/health"),
  fixtures: () => request<Fixture[]>("GET", "/v1/fixtures"),
  packs: () => request<KnowledgePack[]>("GET", "/api/vendors/knowledge-packs"),
  inspections: () => request<InspectionSummary[]>("GET", "/v1/inspections"),
  create: (fixtureId: string, assetId: string) => once(`create:${fixtureId}:${assetId}`, (k) => request<Inspection>("POST", "/v1/inspections", { fixtureId, assetId }, undefined, k)),
  inspection: (id: string) => request<Inspection>("GET", I(id)),
  events: (id: string) => request<EventRecord[]>("GET", `${I(id)}/events`),
  artifacts: (id: string) => request<string[]>("GET", `${I(id)}/artifacts`),
  geometry: (id: string, segmentationId: string) => request<SegmentationGeometry>("GET", `${I(id)}/artifacts/${segmentationId}.json`),
  /** With a key, a retry of the same capture is recognised by the server instead of recording a second photo. */
  upload: (id: string, file: Blob, name: string, source: Origin, key?: string, observedAt?: string, opts?: RequestOptions) => {
    const f = new FormData();
    f.append("image", file, name);
    f.append("source", source);
    f.append("observedAt", observedAt ?? new Date().toISOString());
    return request<ObservationResponse>("POST", `${I(id)}/observations`, undefined, f, key, opts);
  },
  /** The assessment is about the latest photo; naming it keeps a retry on the same photo and a newer photo a new command. */
  investigate: (id: string, photoEvidenceId?: string | null, signal?: AbortSignal) =>
    once(`${id}:investigate:${photoEvidenceId ?? ""}`, (k) => request<InvestigateResponse>("POST", `${I(id)}/investigate${photoEvidenceId ? `?photoEvidenceId=${encodeURIComponent(photoEvidenceId)}` : ""}`, undefined, undefined, k, { signal })),
  segment: (id: string, concept?: string, photoEvidenceId?: string, signal?: AbortSignal) =>
    once(`${id}:segment:${photoEvidenceId ?? ""}:${concept ?? ""}`, (k) => request<{ segmentation: SegmentationGeometry; state: Inspection }>("POST", `${I(id)}/segment`, { concept, photoEvidenceId }, undefined, k, { signal })),
  knowledge: (id: string) => request<Knowledge>("GET", `${I(id)}/knowledge`),
  // Commands of the verification round take the `commandScope` of the inspection they were decided on (see `once`).
  reason: (id: string, observation: string, scope = "") =>
    once(`${id}:reason:${scope}:${observation}`, (k) => request<ReasonResponse>("POST", `${I(id)}/knowledge/reason`, { observation }, undefined, k)),
  runDiagnostic: (id: string, diagnosticId: string, scope = "", signal?: AbortSignal) =>
    once(`${id}:diagnostic:${scope}:${diagnosticId}`, (k) => request<{ result: DiagnosticResult; state: Inspection }>("POST", `${I(id)}/diagnostics/${encodeURIComponent(diagnosticId)}:run`, undefined, undefined, k, { signal })),
  workOrder: (id: string, summary: string) => once(`${id}:workOrder:${summary}`, (k) => request<Inspection>("POST", `${I(id)}/work-order`, { summary }, undefined, k)),
  claim: (id: string, description: string, scope = "") =>
    once(`${id}:claim:${scope}:${description}`, (k) => request<{ claimId: string; state: Inspection }>("POST", `${I(id)}/repair-claims`, { description }, undefined, k)),
  review: (id: string, requirementId: string, status: ChecklistStatus, photoEvidenceId: string, scope = "") =>
    once(`${id}:review:${scope}:${requirementId}:${status}:${photoEvidenceId}`, (k) => request<Inspection>("POST", `${I(id)}/checklist`, { requirementId, status, photoEvidenceId }, undefined, k)),
  requestEvidence: (id: string, note: string) => once(`${id}:evidence:${note}`, (k) => request<Inspection>("POST", `${I(id)}/evidence-requests`, { note }, undefined, k)),
  /** "I can't safely get this view": closes a NeedsEvidence inspection as Inconclusive with the decline on record. The scope names the photo the view was asked after. */
  declineView: (id: string, scope = "") => once(`${id}:decline:${scope}`, (k) => request<Inspection>("POST", `${I(id)}/decline-view`, undefined, undefined, k)),
  acknowledge: (id: string, scope = "") => once(`${id}:acknowledge:${scope}`, (k) => request<Inspection>("POST", `${I(id)}/acknowledge`, undefined, undefined, k)),
  verify: (id: string, scope = "", signal?: AbortSignal) =>
    once(`${id}:verify:${scope}`, (k) => request<{ decision: Decision; state: Inspection }>("POST", `${I(id)}/verify`, undefined, undefined, k, { signal })),
  receipt: (id: string) => request<Record<string, unknown>>("GET", `${I(id)}/receipt`),
  verifyReceipt: (id: string) => request<ReceiptCheck>("GET", `${I(id)}/receipt/verify`),
  simulate: (diagnosticId: string, passed: boolean) => request<unknown>("POST", `/v1/simulation/diagnostics/${encodeURIComponent(diagnosticId)}`, { passed }),
  simulation: () => request<{ mode: string; outcomes: Record<string, boolean | null> }>("GET", "/v1/simulation/diagnostics"),
};

export const artifactUrl = (id: string, name: string) => `${I(id)}/artifacts/${encodeURIComponent(name)}`;
export const receiptHtmlUrl = (id: string) => `${I(id)}/receipt.html`;
export const receiptJsonUrl = (id: string) => `${I(id)}/receipt`;
