// LensQi FieldOps browser P0 surface. Plain JS over the inspection API; the page never decides anything itself.
const $ = (id) => document.getElementById(id);
const STAGES = ["Created","InitialScan","NeedsEvidence","FaultSuspected","FaultConfirmed","AwaitingRepair","ReadyToVerify","VerificationFailed","VerifiedComplete","Inconclusive"];
let state = null, fixtures = [], inspectionId = null, health = null, photoUrls = {};

async function api(method, url, body, isForm) {
  const opts = { method, headers: {} };
  if (body !== undefined) { if (isForm) opts.body = body; else { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); } }
  const r = await fetch(url, opts);
  const text = await r.text();
  let data = null; try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }
  if (!r.ok) throw Object.assign(new Error((data && (data.message || data.code)) || r.statusText), { status: r.status, data });
  return data;
}
function esc(s) { return String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c])); }
function banner(cls, text, small) { const b = $("banner"); b.className = "banner " + cls; b.innerHTML = esc(text) + (small ? `<small>${esc(small)}</small>` : ""); }
function showError(e) { banner("err", `${e.data?.code || "error"}: ${e.message}`); }

async function loadFixtures() {
  health = await api("GET", "/health");
  $("mode").innerHTML = `diagnostics mode: <b>${esc(health.diagnosticsMode)}</b> · packs ${health.packs} · fixtures ${health.fixtures}`;
  fixtures = await api("GET", "/v1/fixtures");
  $("fixture").innerHTML = fixtures.map(f => `<option value="${esc(f.fixtureId)}">${esc(f.fixtureId)} rev ${f.fixtureRevision}</option>`).join("");
  onFixtureChange();
  $("demo").classList.toggle("hidden", health.diagnosticsMode !== "Simulated");
}
function onFixtureChange() {
  const f = fixtures.find(x => x.fixtureId === $("fixture").value);
  $("asset").innerHTML = f.assets.map(a => `<option value="${esc(a.assetId)}">${esc(a.displayName)} (${esc(a.assetId)})</option>`).join("");
  $("fixtureNote").textContent = `${f.fixtureDescription} Fixture digest ${f.sha256.slice(0, 12)}…`;
}

async function refresh() {
  state = await api("GET", `/v1/inspections/${inspectionId}`);
  // Photos uploaded by another client (the Android app) are shown from the server's stored artifacts.
  if (state.photos.some(p => !photoUrls[p.evidenceId])) {
    const names = await api("GET", `/v1/inspections/${inspectionId}/artifacts`);
    for (const p of state.photos) { const n = names.find(x => x.startsWith(p.evidenceId + "-")); if (n && !photoUrls[p.evidenceId]) photoUrls[p.evidenceId] = `/v1/inspections/${inspectionId}/artifacts/${n}`; }
  }
  $("inspId").textContent = inspectionId;
  $("stages").innerHTML = STAGES.map(s => `<span class="stage ${s === state.stage ? (s === "VerificationFailed" ? "bad" : s === "VerifiedComplete" ? "ok" : "on") : ""}">${s}</span>`).join("");
  $("photos").innerHTML = state.photos.map(p => `<figure>${photoUrls[p.evidenceId] ? `<img src="${photoUrls[p.evidenceId]}" alt="">` : ""}<figcaption>${esc(p.evidenceId)} · ${esc(p.origin)}${changeTag(p.evidenceId)}<br>${esc(p.artifactSha256.slice(0, 12))}…</figcaption></figure>`).join("") || `<span class="muted">No photos yet.</span>`;
  $("diagnostics").innerHTML = state.diagnostics.map(d => {
    const r = d.result, cls = r.passed === true ? "pass" : r.passed === false ? "fail" : "unav";
    return `<div class="diag"><span class="src ${esc(r.source)}">${esc(r.source).toUpperCase()}</span><span class="${cls}">${r.passed === true ? "PASS" : r.passed === false ? "FAIL" : "UNAVAILABLE"}</span><span>${esc(r.diagnosticId)} → ${esc(r.normalizedValue ?? "n/a")}</span><span class="muted">${new Date(r.completedAt).toLocaleTimeString()}</span></div>`;
  }).join("") + (state.predictionScores.length ? `<div class="muted">Predictions: ${state.predictionScores.map(p => `${esc(p.outcome)}`).join(", ")} <span class="tag inferred">calibration, not evidence</span></div>` : "") || `<span class="muted">No diagnostics yet.</span>`;
  renderChecklist();
  const ev = await api("GET", `/v1/inspections/${inspectionId}/events`);
  $("events").innerHTML = ev.map(e => `<li><b>${esc(e.kind)}</b>${e.kind === "StageChanged" ? ` ${esc(e.from)} → ${esc(e.to)}: ${esc(e.reason)}` : ""} <span>${new Date(e.occurredAt).toLocaleTimeString()}</span></li>`).join("");
  $("receiptLinks").innerHTML = `<a href="/v1/inspections/${inspectionId}/receipt.html" target="_blank"><button>Open receipt</button></a> <a href="/v1/inspections/${inspectionId}/receipt" target="_blank"><button class="ghost">JSON</button></a> <button id="verifyReceipt" class="ghost">Verify bundle</button>`;
  $("verifyReceipt").onclick = async () => { const v = await api("GET", `/v1/inspections/${inspectionId}/receipt/verify`); banner(v.isValid ? "ok" : "bad", v.isValid ? "Receipt bundle integrity: PASS" : "Receipt bundle integrity: FAIL", (v.isValid ? v.checks : v.failures).join(" · ")); };
  // Whichever came last in the event trail wins the banner: change evidence (VK-T26) for the current claim, or a decision.
  const change = (state.changeAnalyses || []).at(-1);
  const lastIndex = (kind) => ev.map(e => e.kind).lastIndexOf(kind);
  if (change && change.claimId === state.currentClaimId && lastIndex("VisualChangeAnalyzed") > lastIndex("VerificationEvaluated")) {
    const rejected = change.verdict === "DuplicateImage" || change.verdict === "NoVisualChange";
    banner(rejected ? "bad" : change.verdict === "Changed" ? "review" : "sim",
      `CHANGE EVIDENCE: ${change.verdict}` + (rejected ? " · photo not accepted, capture a new view" : " · measured (visual), not a verification"),
      change.reason + ` · thresholds ${change.thresholdsVersion}`);
  } else if (state.lastDecision) {
    const d = state.lastDecision;
    if (d.result === "Verified") banner(d.outcomeKind === "VerifiedCompleteLive" ? "ok" : "sim", d.outcomeKind === "VerifiedCompleteLive" ? "VERIFIED COMPLETE · LIVE" : "VERIFIED COMPLETE · SIMULATED (not a live measurement)", d.verifiedClaims.join(" · "));
    else if (d.result === "NeedsHumanReview") banner("review", "NEEDS HUMAN REVIEW", d.reasons.join(" · "));
    else banner("bad", "VERIFICATION FAILED", d.reasons.join(" · "));
  }
  if (health.diagnosticsMode === "Simulated") { const s = await api("GET", "/v1/simulation/diagnostics"); $("simState").textContent = "simulated endpoint: " + JSON.stringify(s.outcomes); }
}
function changeTag(evidenceId) {
  const c = (state.changeAnalyses || []).filter(a => a.photoEvidenceId === evidenceId).at(-1);
  return c ? ` <span class="tag ${c.verdict === "Changed" ? "measured" : c.verdict === "Unscorable" ? "" : "bad"}">${esc(c.verdict)}</span>` : "";
}
function renderChecklist() {
  const f = fixtures.find(x => x.fixtureId === state.fixtureId);
  const reviewed = Object.fromEntries((state.checklist || []).map(c => [c.requirementId, c]));
  const lastPhoto = state.photos.find(p => p.evidenceId === state.acceptedPostRepairPhotoId) || null;
  $("checklist").innerHTML = f.visualRequirements.map(v => `<div class="check"><span>${esc(v.requirementId)} ${v.mandatory ? "<span class='tag'>mandatory</span>" : ""} ${v.segmentationPrompt ? `<span class='muted'>· SAM prompt “${esc(v.segmentationPrompt)}”</span>` : ""}<br><span class="muted">${reviewed[v.requirementId] ? "reviewed: " + esc(reviewed[v.requirementId].status) : (state.repairClaimedAt ? "not reviewed since claim" : "review opens after a repair claim")}</span></span>
    <span>${["Satisfied","NotSatisfied","Unknown","Conflicting"].map(s => `<button class="ghost" data-req="${esc(v.requirementId)}" data-status="${s}" ${lastPhoto && state.repairClaimedAt ? "" : "disabled"}>${s}</button>`).join(" ")}</span></div>`).join("");
  $("checklist").querySelectorAll("button").forEach(b => b.onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/checklist`, { requirementId: b.dataset.req, status: b.dataset.status, photoEvidenceId: lastPhoto.evidenceId })));
}
async function loadKnowledge() {
  const k = await api("GET", `/v1/inspections/${inspectionId}/knowledge`);
  const tag = (p) => p === "SiteConfigured" ? "site" : "documented";
  $("knowledge").innerHTML = `<div class="muted">Pack <code>${esc(k.packId || "none")}</code> rev ${k.packRevision ?? "-"} · ${esc(k.status)} · ${k.packSha256 ? k.packSha256.slice(0, 12) + "…" : ""}</div>` +
    (k.facts || []).map(f => `<div class="fact"><span class="tag ${tag(f.provenance)}">${esc(f.provenance)}</span> <code>${esc(f.factId)}</code> ${esc(f.statement)}<div class="src-line">${esc(f.citation.documentTitle)} — ${esc(f.citation.sourceLocator)}${f.citation.sourceUrl ? ` · <a href="${esc(f.citation.sourceUrl)}" target="_blank" rel="noopener">source</a>` : ""} · accessed ${esc(f.citation.accessedOn)}</div></div>`).join("") +
    ((k.excluded || []).length ? `<div class="muted">Withheld: ${k.excluded.map(e => `${esc(e.factId)} (${esc(e.reason)})`).join(", ")}</div>` : "");
}
async function act(fn) { try { await fn(); await refresh(); } catch (e) { showError(e); try { await refresh(); } catch {} } }

$("fixture").onchange = onFixtureChange;
$("create").onclick = () => act(async () => {
  const r = await api("POST", "/v1/inspections", { fixtureId: $("fixture").value, assetId: $("asset").value });
  inspectionId = r.inspectionId; photoUrls = {}; $("inspection").classList.remove("hidden"); $("banner").classList.add("hidden"); $("assessment").innerHTML = ""; $("proposal").innerHTML = "";
  await loadKnowledge();
});
$("upload").onclick = () => act(async () => {
  const file = $("photo").files[0]; if (!file) throw new Error("Choose or capture a photo first");
  const fd = new FormData(); fd.append("image", file); fd.append("source", $("source").value); fd.append("observedAt", new Date().toISOString());
  const r = await api("POST", `/v1/inspections/${inspectionId}/observations`, fd, true);
  photoUrls[r.evidenceId] = URL.createObjectURL(file); $("photo").value = "";
});
// --- SAM: segment, then draw box + mask on the ORIGINAL image on a canvas; crop shown beside it ---
function decodeRle(counts, height, width) {
  // COCO compressed RLE (LEB128-ish, column-major). Returns Uint8Array of h*w in column-major order.
  const out = new Uint8Array(height * width); let p = 0, pos = 0, value = 0, more, k = 0, x, m = 0, cnts = [];
  while (p < counts.length) { x = 0; k = 0; more = 1;
    while (more) { const c = counts.charCodeAt(p) - 48; x |= (c & 0x1f) << (5 * k); more = c & 0x20; p++; k++; if (!more && (c & 0x10)) x |= -1 << (5 * k); }
    if (m > 2) x += cnts[m - 2]; cnts[m++] = x; }
  for (let i = 0; i < cnts.length; i++) { for (let n = 0; n < cnts[i]; n++) { if (pos < out.length) out[pos++] = value; } value = value ? 0 : 1; }
  return out;
}
async function drawSegmentation(seg) {
  const canvas = $("overlay"), ctx = canvas.getContext("2d");
  const img = new Image(); img.src = `/v1/inspections/${inspectionId}/artifacts/` + (await artifactNameFor(seg.photoEvidenceId));
  await img.decode();
  canvas.width = img.naturalWidth; canvas.height = img.naturalHeight; canvas.classList.remove("hidden");
  ctx.drawImage(img, 0, 0);
  for (const m of seg.masks) {
    const [h, w] = m.rle.size; const bits = decodeRle(m.rle.counts, h, w);
    const tile = ctx.createImageData(w, h);
    for (let x = 0; x < w; x++) for (let y = 0; y < h; y++) { if (bits[x * h + y]) { const o = (y * w + x) * 4; tile.data[o] = 255; tile.data[o + 1] = 80; tile.data[o + 2] = 80; tile.data[o + 3] = 120; } }
    const off = document.createElement("canvas"); off.width = w; off.height = h; off.getContext("2d").putImageData(tile, 0, 0);
    ctx.drawImage(off, 0, 0, w, h, m.left, m.top, m.right - m.left, m.bottom - m.top);
  }
  ctx.lineWidth = Math.max(3, canvas.width / 300); ctx.strokeStyle = "#ff5050"; ctx.font = `${Math.max(14, canvas.width / 60)}px system-ui`; ctx.fillStyle = "#ff5050";
  for (const b of seg.boxes) { ctx.strokeRect(b.left, b.top, b.right - b.left, b.bottom - b.top); ctx.fillText(`${b.objectId}${b.confidence != null ? " " + b.confidence.toFixed(2) : ""}`, b.left + 4, Math.max(16, b.top - 6)); }
  if (seg.crop) { $("cropImg").src = `/v1/inspections/${inspectionId}/artifacts/${seg.crop.file}`; $("cropFig").classList.remove("hidden"); } else $("cropFig").classList.add("hidden");
}
async function artifactNameFor(evidenceId) { const names = await api("GET", `/v1/inspections/${inspectionId}/artifacts`); return names.find(n => n.startsWith(evidenceId + "-")); }
$("segment").onclick = () => act(async () => {
  $("segResult").textContent = "Asking sam-3.1 (one concept, image as data URL)…";
  const concept = $("concept").value.trim();
  const r = await api("POST", `/v1/inspections/${inspectionId}/segment`, concept ? { concept } : {});
  const s = r.segmentation;
  $("segResult").innerHTML = s.matched
    ? `<span class="tag inferred">${esc(s.model)}</span> “${esc(s.concept)}”: ${s.boxes.length} box(es), ${s.masks.length} mask(s), ${s.elapsedSeconds.toFixed(1)} s · parser ${esc(s.parser)} · mask sha ${esc((s.maskSha256 || "").slice(0, 12))}…`
    : `<span class="tag">NO REGION DETECTED</span> “${esc(s.concept)}” — honest no-match from ${esc(s.model)}; no mask was fabricated`;
  await drawSegmentation(s);
});

$("investigate").onclick = () => act(async () => {
  $("assessment").innerHTML = `<span class="muted">Asking muse-spark-1.3… (image + fixture context, strict schema)</span>`;
  const r = await api("POST", `/v1/inspections/${inspectionId}/investigate`);
  const a = r.assessment;
  $("assessment").innerHTML = `<div class="muted">model ${esc(r.modelId)} · tokens ${r.usage?.inputTokenCount ?? "?"}/${r.usage?.outputTokenCount ?? "?"} · response ${esc(r.responseDigest.slice(0, 12))}…</div>` +
    a.hypotheses.map(h => `<div class="hyp ${esc(h.status)}"><b>${esc(h.status)}</b> ${esc(h.featureId)} — ${esc(h.reason)}</div>`).join("");
  if (a.requestedSegmentationConcept) $("concept").value = a.requestedSegmentationConcept;
  $("assessment").innerHTML +=
    `<div><b>Proposed next action:</b> ${esc(a.nextAction)}${a.requestedView ? ` — “${esc(a.requestedView)}”` : ""}${a.requestedSegmentationConcept ? ` · segment “${esc(a.requestedSegmentationConcept)}”` : ""} <span class="tag inferred">inferred</span></div>`;
});
$("decline").onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/decline-view`).catch(e => { if (e.status === 404) throw new Error("Decline endpoint not exposed yet"); throw e; }));
$("reason").onclick = () => act(async () => {
  const obs = $("observation").value.trim(); if (!obs) throw new Error("Describe the observation first");
  $("proposal").innerHTML = `<span class="muted">Asking muse-spark-1.3 with only the applicable facts…</span>`;
  const r = await api("POST", `/v1/inspections/${inspectionId}/knowledge/reason`, { observation: obs });
  const p = r.proposal;
  $("proposal").innerHTML = r.accepted
    ? `<div class="fact"><span class="tag inferred">inferred · validated</span> <b>${esc(p.nextAction)}</b> ${p.requestedVisualEvidence ? `— “${esc(p.requestedVisualEvidence)}”` : ""}<br>${esc(p.hypothesis || "")}<br><span class="muted">cites ${p.usedFactIds.map(esc).join(", ")}</span>${p.prediction ? `<br>prediction: <b>${esc(p.prediction.predictedValue)}</b> for ${esc(p.prediction.diagnosticId)} <button id="usePred" class="ghost">Use this prediction</button>` : ""}</div>`
    : `<div class="fact"><span class="tag">REJECTED by server validation</span> ${r.rejections.map(x => `<div>${esc(x.code)}: ${esc(x.message)}</div>`).join("")}<div class="muted">raw: ${esc((r.raw || "").slice(0, 200))}</div></div>`;
  const u = $("usePred"); if (u) u.onclick = () => { $("predictValue").value = p.prediction.predictedValue; };
});
$("runProbe").onclick = () => act(async () => {
  const pv = $("predictValue").value;
  if (pv) await api("POST", `/v1/inspections/${inspectionId}/predictions`, { diagnosticId: "NODE-A-HEALTH", predictedValue: pv, basedOnFactIds: [] });
  await api("POST", `/v1/inspections/${inspectionId}/diagnostics/NODE-A-HEALTH:run`);
  $("predictValue").value = "";
});
$("workOrder").onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/work-order`, { summary: "Technician work order drafted from the inspection" }));
$("claim").onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/repair-claims`, { description: $("claimText").value || "Repair performed" }));
$("ack").onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/acknowledge`));
$("verify").onclick = () => act(() => api("POST", `/v1/inspections/${inspectionId}/verify`, undefined, false));
$("simFail").onclick = () => act(() => api("POST", "/v1/simulation/diagnostics/NODE-A-HEALTH", { passed: false }));
$("simPass").onclick = () => act(() => api("POST", "/v1/simulation/diagnostics/NODE-A-HEALTH", { passed: true }));

// ?inspection=I-… opens an existing inspection, e.g. one started on the Android client.
loadFixtures().then(async () => {
  const id = new URLSearchParams(location.search).get("inspection");
  if (!id) return;
  inspectionId = id; $("inspection").classList.remove("hidden");
  await act(loadKnowledge);
}).catch(showError);
