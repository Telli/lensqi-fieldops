// Deterministic browser checks using recorded API fixtures; never calls providers or writes inspection data.
// Run a local web server, then: PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node scripts/ar_ux_smoke.mjs
import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const read = async (name) => JSON.parse(await readFile(`${root}fixtures/api-recordings/${name}.json`, 'utf8'));
const base = process.env.AR_SMOKE_URL || 'http://127.0.0.1:5178';
const fixture = await read('02-fixtures');
const investigation = await read('06-investigate');
const segmentation = await read('07-segment');
const photoId = segmentation.segmentation.photoEvidenceId;
const inspectionId = investigation.state.inspectionId;
const assessment = { ...investigation.assessment, requestedSegmentationConcept: 'yellow ethernet cable' };
let state = { ...segmentation.state, evidenceRequests: [], changeAnalyses: [] };
const artifact = `${photoId}-photo.jpg`;
const calls = [], errors = [];
let cameraRequests = 0;
const browser = await chromium.launch({ headless: true, ...(process.env.AR_SMOKE_CHANNEL ? { channel: process.env.AR_SMOKE_CHANNEL } : {}) });
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce', colorScheme: 'dark' });
await context.addInitScript(() => {
  window.__cameraRequests = 0;
  Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { configurable: true, value: async () => {
    window.__cameraRequests++;
    throw new DOMException('Denied in deterministic test', 'NotAllowedError');
  } });
  window.__speech = [];
  Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: {
    cancel: () => window.__speech.push('cancel'), speak: (utterance) => window.__speech.push(utterance.text),
  } });
});
await context.route('**/health', (route) => route.fulfill({ json: { status: 'ok', packs: 1, fixtures: 1, diagnosticsMode: 'Simulated' } }));
await context.route('**/v1/**', async (route) => {
  const req = route.request(), path = new URL(req.url()).pathname;
  const reply = (json) => route.fulfill({ json });
  if (path === '/v1/fixtures') return reply(fixture);
  if (path === '/v1/inspections') return reply([]);
  if (path.endsWith('/events')) return reply([{ kind: 'AssessmentRecorded', sequence: 4, occurredAt: '2026-10-28T09:18:15Z', assessment }]);
  if (path.endsWith('/artifacts')) return reply([artifact, `${segmentation.segmentation.segmentationId}.json`]);
  if (path.endsWith(`/${artifact}`)) return route.fulfill({ path: `${root}fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg`, contentType: 'image/jpeg' });
  if (path.endsWith('.json')) return reply(segmentation.segmentation);
  if (path.endsWith('/observations')) return reply({ evidenceId: photoId, state, width: 1600, height: 1000 });
  if (path.endsWith('/investigate')) return reply({ ...investigation, assessment, state });
  if (path.endsWith('/segment')) {
    const body = req.postDataJSON(); calls.push(body);
    // Return a foreign photo for one concept: it must never be displayed over this capture.
    const geometry = body.concept === 'network switch'
      ? { ...segmentation.segmentation, concept: body.concept, photoEvidenceId: 'another-client-photo' }
      : segmentation.segmentation;
    return reply({ segmentation: geometry, state });
  }
  if (path === `/v1/inspections/${inspectionId}`) return reply(state);
  throw new Error(`Unexpected request: ${req.method()} ${path}`);
});
const page = await context.newPage();
page.setDefaultTimeout(10_000);
page.on('pageerror', (error) => errors.push(error.message));
const output = `${root}docs/evidence/ar-ux`;
await mkdir(output, { recursive: true });
try {
  await page.goto(`${base}/m/i/${inspectionId}/scan`);
  await page.getByText('Camera access is blocked.', { exact: false }).waitFor();
  cameraRequests = await page.evaluate(() => window.__cameraRequests);
  await page.getByRole('button', { name: 'Try camera again' }).click();
  await page.waitForFunction((before) => window.__cameraRequests > before, cameraRequests);
  await page.screenshot({ path: `${output}/camera-recovery.png` });
  await page.locator('input[type=file]').last().setInputFiles(`${root}fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg`);
  const pin = page.getByRole('button', { name: /^Part 1:/ });
  await pin.waitFor();
  assert.equal(calls.length, 2);
  assert(calls.every((call) => call.photoEvidenceId === photoId), 'every outline request must name the captured photo');
  assert.equal(await page.getByRole('button', { name: /^Part \d:/ }).count(), 1, 'foreign-photo geometry must be ignored');
  await pin.click();
  await page.getByRole('button', { name: 'Close part details' }).waitFor();
  assert.equal(await pin.getAttribute('aria-pressed'), 'true');
  await page.getByRole('button', { name: 'Hide outlines' }).click();
  await pin.waitFor({ state: 'hidden' });
  assert.equal(await pin.isVisible(), false);
  await page.getByRole('button', { name: 'Show outlines' }).click();
  await pin.waitFor({ state: 'visible' });
  assert.equal(await pin.isVisible(), true);
  await page.screenshot({ path: `${output}/capture-review.png` });
  await page.getByRole('button', { name: 'Capture the requested view' }).click();
  await page.getByRole('button', { name: 'Capture', exact: true }).waitFor();
  assert(await page.getByRole('button', { name: 'Try camera again' }).isVisible());

  await page.goto(`${base}/m/i/${inspectionId}/glasses`);
  await page.getByRole('button', { name: /^Part 1:/ }).click();
  await page.getByRole('button', { name: 'Close part details' }).waitFor();
  assert.equal(await page.evaluate(() => window.__speech.length), 0, 'voice starts only by user choice');
  await page.getByRole('button', { name: 'Speak guidance' }).click();
  await page.waitForFunction(() => window.__speech.some((s) => s !== 'cancel'));
  await page.screenshot({ path: `${output}/hands-free-details.png` });
  await page.getByRole('button', { name: 'Mute guidance' }).click();
  assert.equal(await page.evaluate(() => window.__speech.at(-1)), 'cancel');
  for (const viewport of [{ width: 320, height: 568 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'page must not overflow horizontally');
    const box = await page.getByRole('button', { name: /^Part 1:/ }).boundingBox();
    assert(box.width >= 44 && box.height >= 44, 'marker target must be at least 44px');
  }

  // Ops constrains the photo height, creating letterboxing: overlays must fit the actual image pixels.
  await page.setViewportSize({ width: 1000, height: 1000 });
  await page.evaluate(() => localStorage.setItem('lensqi-theme', 'light'));
  await page.goto(`${base}/ops/inspections/${inspectionId}?theme=light`);
  await page.locator('[data-ar-overlay]').first().waitFor();
  const alignment = await page.locator('[data-ar-photo]').first().evaluate((el) => {
    const img = el.querySelector('img'), overlay = el.querySelector('[data-ar-overlay]');
    const imageBox = img.getBoundingClientRect(), overlayBox = overlay.getBoundingClientRect();
    const scale = Math.min(imageBox.width / img.naturalWidth, imageBox.height / img.naturalHeight);
    return { expectedWidth: img.naturalWidth * scale, actualWidth: overlayBox.width, imageLeft: imageBox.left,
      expectedLeft: imageBox.left + (imageBox.width - img.naturalWidth * scale) / 2, actualLeft: overlayBox.left };
  });
  assert(Math.abs(alignment.expectedWidth - alignment.actualWidth) < 1, 'overlay width follows contained photo');
  assert(Math.abs(alignment.expectedLeft - alignment.actualLeft) < 1, 'overlay includes letterbox offset');
  assert(alignment.actualLeft - alignment.imageLeft > 5, 'fixture must exercise real letterboxing');
  assert.equal(await page.locator('html').evaluate((el) => el.classList.contains('dark')), false, 'light-theme screenshot must actually use light mode');
  await page.screenshot({ path: `${output}/desktop-alignment-light.png` });
  state = { ...state, stage: 'VerifiedComplete', outcomeKind: 'VerifiedCompleteSimulated' };
  await page.goto(`${base}/m/i/${inspectionId}/glasses`);
  await page.getByText('Verified complete · simulated check', { exact: true }).waitFor();
  assert.deepEqual(errors, [], 'no browser runtime errors');
  console.log('PASS: camera recovery; source-photo pinning; foreign-geometry rejection; marker selection; original-photo toggle; recapture; hands-free details; voice opt-in/mute; 320px and landscape layouts; desktop letterboxing; simulated provenance.');
} finally {
  await browser.close();
}
