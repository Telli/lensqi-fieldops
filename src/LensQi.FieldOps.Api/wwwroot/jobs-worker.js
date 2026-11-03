const assets = ["/assets/index-daej088D.js","/assets/index-CLmx1Cmg.css","/assets/live-tracker.worker-BZOZts3G.js"];
// Vite injects the exact list of generated assets.
const shell = "fieldops-shell-" + assets.join("|");
const recordCache = "fieldops-jobs-v1";
const readPath = /^\/v1\/tasks(?:\/[^/]+(?:\/artifacts\/[^/]+)?)?$/;
const writePath = /^\/v1\/tasks\/([^/]+)\/steps\/[^/]+(?::(?:skip|complete)|\/check)$/;
const transient = status => status === 408 || status === 429 || status >= 500;
function problem(status, code, message) { return Response.json({code, message}, {status}); }

self.addEventListener("install", e => e.waitUntil((async () => {
  const cache = await caches.open(shell);
  await cache.addAll(["/", ...assets]);
  await self.skipWaiting();
})()));
self.addEventListener("activate", e => e.waitUntil((async () => {
  for (const k of await caches.keys()) if (k.startsWith("fieldops-shell-") && k !== shell) await caches.delete(k);
  await self.clients.claim();
})()));

async function command(mode, run) {
  const db = await new Promise((resolve,reject) => {
    const r = indexedDB.open("fieldops-job-commands", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("commands", {keyPath:"id", autoIncrement:true});
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  try {
    return await new Promise((resolve,reject) => {
      const tx = db.transaction("commands", mode);
      const r = run(tx.objectStore("commands"));
      tx.oncomplete = () => resolve(r.result);
      tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
const pending = () => command("readonly", s => s.getAll());
const remove = id => command("readwrite", s => s.delete(id));
let tail = Promise.resolve();
function serialized(work) {
  const next = tail.then(work);
  tail = next.catch(()=>{});
  return next;
}
// Serialize asynchronous cache writes from multiple tabs before comparing server timestamps.
let saving = Promise.resolve();
function remember(t) {
  const next = saving.then(()=>saveTask(t));
  saving = next.catch(()=>{});
  return next;
}
async function saveTask(t) {
  if (!t?.taskId || !t.updatedAt) return;
  const cache = await caches.open(recordCache);
  const url = "/v1/tasks/" + encodeURIComponent(t.taskId);
  const previous = await cache.match(url);
  if (previous && Date.parse((await previous.json()).updatedAt) > Date.parse(t.updatedAt)) return;
  await cache.put(url, Response.json(t));
  const list = await cache.match("/v1/tasks");
  const steps = t.plan?.steps ?? [];
  const summary = {taskId:t.taskId, mode:t.mode, goal:t.goal, title:t.plan?.title, phase:t.phase,
    site:t.site, updatedAt:t.updatedAt, currentStepTitle:steps.find(s=>s.id===t.currentStepId)?.title,
    steps:steps.length, finished:steps.filter(s=>s.status==="Done" || s.status==="Skipped").length};
  const other = list ? (await list.json()).filter(s=>s.taskId!==t.taskId) : [];
  await cache.put("/v1/tasks",Response.json([summary,...other]));
  for (const p of t.photos.filter(p=>p.purpose === "Manual")) {
    const page = url + "/artifacts/" + encodeURIComponent(p.evidenceId);
    if (!await cache.match(page)) await cache.add(page).catch(()=>{});
  }
}
async function drain(wanted) {
  let result;
  const unavailable = new Set();
  for (const row of await pending()) {
    if (row.error || unavailable.has(row.taskId)) continue;
    let r;
    try { r = await fetch(row.url, {method:"POST", headers:row.headers, body:row.body}); }
    catch { unavailable.add(row.taskId); continue; }
    if (transient(r.status)) { unavailable.add(row.taskId); continue; }
    if (!r.ok && ["provider_unavailable","model_timeout","invalid_model_output"].includes((await r.clone().json().catch(()=>({}))).code)) {
      unavailable.add(row.taskId); continue;
    }
    if (r.ok) {
      const data = await r.clone().json();
      await remember(data.task ?? data);
      await remove(row.id);
    } else {
      row.error = (await r.clone().json().catch(()=>({}))).message || "Server refused the update.";
      await command("readwrite", s=>s.put(row));
    }
    if (row.id === wanted) result = r;
    for (const c of await self.clients.matchAll()) c.postMessage({type:"job-updated", taskId:row.taskId});
  }
  return result;
}
async function queue(req, taskId) {
  const body = await req.arrayBuffer();
  return serialized(async () => {
    const headers = Object.fromEntries(req.headers);
    const prior = (await pending()).find(r=>r.taskId === taskId && (r.headers["idempotency-key"] === headers["idempotency-key"] || (r.url === req.url && !req.url.endsWith("/check"))));
    if (prior?.error) return problem(409,"refused_update",prior.error);
    const id = prior?.id ?? await command("readwrite", s=>s.add({url:req.url,headers,body,taskId}));
    const r = await drain(id);
    if (r) return r;
    await self.registration.sync?.register("sync-jobs").catch(()=>{});
    return problem(503,"job_update_saved","Step update saved on this device. Waiting for server confirmation.");
  });
}
self.addEventListener("sync", e => { if (e.tag === "sync-jobs") e.waitUntil(serialized(async()=>{ await drain(); if ((await pending()).some(r=>!r.error)) throw Error("Retry queued updates"); })); });
self.addEventListener("message", e => { if (e.data?.type === "sync-jobs") e.waitUntil(serialized(()=>drain())); });

async function readJob(req) {
  const cache = await caches.open(recordCache);
  try {
    const r = await fetch(req);
    if (!transient(r.status)) {
      if (r.ok) {
        if (/^\/v1\/tasks\/[^/]+$/.test(new URL(req.url).pathname)) await remember(await r.clone().json());
        else await cache.put(req,r.clone());
      } else if (r.status === 404) await cache.delete(req);
      return r;
    }
  } catch {}
  const saved = await cache.match(req);
  if (saved && /^\/v1\/tasks\/[^/]+$/.test(new URL(req.url).pathname)) {
    const task = await saved.json();
    const updates = (await pending()).filter(r=>!r.error && r.taskId===task.taskId);
    for (const row of updates) {
      const m = /\/steps\/([^/:]+):(complete|skip)$/.exec(new URL(row.url).pathname);
      const step = m && task.plan?.steps.find(s=>s.id===decodeURIComponent(m[1]));
      if (step) step.status = m[2]==="complete" ? "Done" : "Skipped";
    }
    if (updates.length && task.phase === "InProgress")
      task.currentStepId = task.plan?.steps.find(s=>s.status==="Active"||s.status==="Todo")?.id ?? task.currentStepId;
    return Response.json({...task,cached:true,pendingUpdates:updates.length});
  }
  return saved ?? problem(503,"offline","This job has not been saved on this device.");
}
self.addEventListener("fetch", e => {
  const req = e.request, url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === "/job-updates") {
    e.respondWith((async () => {
      if (req.method === "DELETE") await serialized(async () => {
        const row = (await pending()).find(r=>r.id===Number(url.searchParams.get("id")));
        if (row?.error) await remove(row.id);
      });
      return Response.json((await pending()).map(({id,taskId,url,error})=>({id,taskId,action:url.split("/").pop(),error})));
    })()); return;
  }
  const action = writePath.exec(url.pathname);
  if (req.method === "POST" && action) { e.respondWith(queue(req,decodeURIComponent(action[1]))); return; }
  if (req.method === "POST" && /^\/v1\/tasks(?:$|\/[^/]+\/(?:accept|plan|finish|abandon|link-inspection|steps\/[^/]+:start)$)/.test(url.pathname)) {
    e.respondWith((async () => {
      const r = await fetch(req);
      if (r.ok) { const data = await r.clone().json(); await remember(data.task ?? data); }
      return r;
    })()); return;
  }
  if (req.method !== "GET") return;
  if (readPath.test(url.pathname)) { e.respondWith(readJob(req)); return; }
  if (req.mode === "navigate" && (url.pathname === "/" || url.pathname.startsWith("/m/") || url.pathname === "/m")) {
    e.respondWith((async()=>{
      try { const r=await fetch(req); if (!transient(r.status)) return r; } catch {}
      const saved=await (await caches.open(shell)).match("/");
      return new Response(saved.body,{headers:saved.headers});
    })()); return;
  }
  if (assets.includes(url.pathname)) e.respondWith((async()=>await (await caches.open(shell)).match(req) ?? fetch(req))());
});
