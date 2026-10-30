// Moving video + recorded genuine detections exercise the actual browser worker and live loop, without provider calls.
import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const read = async name => JSON.parse(await readFile(`${root}fixtures/api-recordings/${name}.json`, 'utf8'));
const fixture = await read('02-fixtures');
const state = await read('03-inspection-created');
const detected = JSON.parse(await readFile(`${root}docs/evidence/live-ar/provider-probe.json`, 'utf8')).detect.body.objects;
assert(detected.length, 'genuine provider probe must contain at least one detection');
const id = state.inspectionId;
const base = process.env.AR_SMOKE_URL || 'http://127.0.0.1:5178';
const browser = await chromium.launch({ headless: true, channel: process.env.AR_SMOKE_CHANNEL || 'chrome' });
const ctx = await browser.newContext({ viewport: { width: 430, height: 932 }, colorScheme: 'dark', reducedMotion: 'reduce' });
await ctx.route('**/live-test-photo.jpg', route => route.fulfill({ path: `${root}fixtures/telecom-lab/images/lab-sw-01-initial-synthetic.jpg`, contentType: 'image/jpeg' }));
await ctx.addInitScript(() => {
  window.__shift = 0; window.__occluded = false; window.__stopped = false;
  Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: async () => {
    const photo = new Image(); photo.src = '/live-test-photo.jpg'; await photo.decode();
    const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 400;
    const c = canvas.getContext('2d');
    const draw = () => { c.fillStyle = '#161a20'; c.fillRect(0, 0, 640, 400); if (!window.__occluded) c.drawImage(photo, window.__shift, 0, 640, 400); };
    draw(); const timer = setInterval(draw, 33); const stream = canvas.captureStream(30);
    for (const track of stream.getTracks()) { const stop = track.stop.bind(track); track.stop = () => { window.__stopped = true; clearInterval(timer); stop(); }; }
    return stream;
  } });
});
let detectCalls = 0, guideCalls = 0, observations = 0, detectActive = 0, maxDetectActive = 0;
const errors = [];
const page = await ctx.newPage(); page.setDefaultTimeout(12000); page.on('pageerror', e => errors.push(e.message));
await ctx.route('**/v1/**', async route => {
  const req = route.request(), path = new URL(req.url()).pathname;
  const reply = json => route.fulfill({ json });
  if (path === '/v1/fixtures') return reply(fixture);
  if (path.endsWith('/events') || path.endsWith('/artifacts') || path === '/v1/inspections') return reply([]);
  if (path.endsWith('/observations')) { observations++; return reply({}); }
  if (path === `/v1/inspections/${id}`) return reply(state);
  if (path.includes('/live/')) {
    const body = req.postDataBuffer().toString('latin1');
    const frameId = body.match(/name="frameId"\r\n\r\n([^\r]+)/)?.[1]; assert(frameId);
    if (path.endsWith('/detect')) {
      detectCalls++; detectActive++; maxDetectActive = Math.max(maxDetectActive, detectActive);
      const offset = await page.evaluate(() => window.__shift / 640);
      await new Promise(resolve => setTimeout(resolve, 500)); detectActive--;
      return reply({ frameId, objects: detected.map(o => ({ ...o, box: { ...o.box, left: o.box.left + offset, right: o.box.right + offset } })), refreshAfterMs: 4000, inferenceMs: 500 });
    }
    guideCalls++; return reply({ frameId, recommendation: 'Keep the yellow connector and its port label visible.', refreshAfterMs: 12000, inferenceMs: 10 });
  }
  throw new Error(`Unexpected route ${path}`);
});
try {
  await page.goto(`${base}/m/i/${id}/scan`);
  await page.getByRole('button', { name: 'Start live assist' }).waitFor();
  assert.equal(detectCalls, 0, 'preview alone must not upload frames');
  await page.getByRole('button', { name: 'Start live assist' }).click();
  const box = page.locator('svg[aria-label="Live object tracking"] rect').first();
  await box.waitFor();
  await page.getByText('Keep the yellow connector and its port label visible.', { exact: true }).waitFor();
  const initialX = Number(await box.getAttribute('x')); const callsBeforeMotion = detectCalls;
  for (let n = 1; n <= 8; n++) {
    await page.evaluate(x => { window.__shift = x; }, n * 4);
    await new Promise(resolve => setTimeout(resolve, 160));
  }
  await box.waitFor();
  const finalX = Number(await box.getAttribute('x'));
  assert(finalX - initialX > 20 && finalX - initialX < 44, `box must follow 32px camera movement between model calls, moved ${finalX - initialX}`);
  assert.equal(detectCalls, callsBeforeMotion, 'motion must be local tracking, not another AI detection');
  const fps = await page.locator('text=/tracking updates\\/s/').innerText();
  await mkdir(`${root}docs/evidence/live-ar`, { recursive: true });
  await page.screenshot({ path: `${root}docs/evidence/live-ar/browser-live-tracking.png` });
  await page.evaluate(() => { window.__occluded = true; });
  await box.waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: 'Pause live assist' }).click();
  const pausedCalls = detectCalls + guideCalls;
  await new Promise(resolve => setTimeout(resolve, 4500));
  assert.equal(detectCalls + guideCalls, pausedCalls, 'pause must stop inference requests');
  assert.equal(observations, 0, 'live frames must never become saved evidence');
  assert.equal(maxDetectActive, 1, 'detection is single flight');
  assert.deepEqual(errors, []);
  await page.getByRole('link', { name: 'Close camera' }).click();
  assert.equal(await page.evaluate(() => window.__stopped), true, 'leaving must stop the camera');
  console.log(`PASS live browser: tracked ${Math.round(finalX - initialX)}px without a new model call; ${fps}; occlusion clears overlays; pause cancels inference; no evidence uploads; camera released.`);
} finally { await browser.close(); }
