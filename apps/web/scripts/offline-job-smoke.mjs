import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {spawn} from 'node:child_process';
import {once} from 'node:events';

const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,what){for(let i=0;i<140;i++){const v=await fn();if(v)return v;await wait(50);}throw Error('timeout '+what);}
const src=fileURLToPath(new URL('../../../',import.meta.url));
const t=JSON.parse(fs.readFileSync(path.join(src,'docs/evidence/tasks/plan-oil.json'))).task;
const [a,b]=t.plan.steps;
t.phase='InProgress';t.currentStepId=a.id;a.status='Active';
t.photos=[{evidenceId:'reference-1',purpose:'Manual'}];a.referencePhotoIds=['reference-1'];
const acceptedKeys=[];
let offline=false;
const endpoint='/v1/tasks/'+t.taskId;
const server=http.createServer((req,res)=>{
  if(offline){req.socket.destroy();return;}
  const p=new URL(req.url,'http://localhost').pathname;
  const json=v=>{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(v));};
  if(p===endpoint)return json(t);
  if(p===endpoint+'/steps/'+a.id+':complete' && req.method==='POST'){
    acceptedKeys.push(req.headers['idempotency-key']);a.status='Done';b.status='Active';t.currentStepId=b.id;
    t.updatedAt=new Date().toISOString();return json(t);
  }
  if(p===endpoint+'/artifacts/reference-1'){res.writeHead(200,{'Content-Type':'image/png'});res.end('reference image fixture');return;}
  if(p==='/v1/tasks')return json([{taskId:t.taskId,goal:t.goal,phase:t.phase,mode:t.mode,updatedAt:t.updatedAt}]);
  if(p.startsWith('/v1/')||p==='/health')return json([]);
  const web=path.join(src,'src/LensQi.FieldOps.Api/wwwroot');
  const isStatic=p.startsWith('/assets/')||p==='/jobs-worker.js';
  const file=isStatic?path.join(web,p.slice(1)):path.join(web,'index.html');
  if(!fs.existsSync(file)){res.writeHead(404);return res.end();}
  res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});res.end(fs.readFileSync(file));
});
server.listen(0,'127.0.0.1');await once(server,'listening');
const baseUrl='http://127.0.0.1:'+server.address().port;
const profile=fs.mkdtempSync(path.join(os.tmpdir(),'fieldops-offline-test-'));
const chrome=spawn(process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless','--remote-debugging-port=0','--user-data-dir='+profile,'--disable-background-networking','--no-first-run','--disable-extensions','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','about:blank'],{stdio:'ignore'});
let ws;
try{
  const port=await until(()=>{try{return fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];}catch{}},'CDP');
  const list=await (await fetch('http://127.0.0.1:'+port+'/json/list')).json();
  ws=new WebSocket(list.find(x=>x.type==='page').webSocketDebuggerUrl);
  await new Promise(r=>ws.addEventListener('open',r,{once:true}));
  const callers=new Map();let seq=1;
  ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id && callers.has(m.id)){callers.get(m.id)(m);callers.delete(m.id);}});
  const rpc=(method,params)=>new Promise((resolve,reject)=>{let id=++seq;callers.set(id,m=>m.error?reject(Error(JSON.stringify(m.error))):resolve(m.result));ws.send(JSON.stringify({id,method,params}));});
  const ev=async(expr)=>{const r=await rpc('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
  const nav=async(route)=>{await rpc('Page.navigate',{url:baseUrl+route});await until(()=>ev('document.readyState==="complete" && location.pathname==='+JSON.stringify(route)),'navigation');};
  const ready=async(expr)=>until(()=>ev(expr),expr);
  const job='/m/t/'+t.taskId;
  await nav(job);
  await ready('Boolean(navigator.serviceWorker.controller)');
  await ready('document.body.innerText.includes('+JSON.stringify(t.plan.title)+')');
  await ev('fetch('+JSON.stringify(endpoint)+').then(r=>r.json())');
  offline=true;
  // Fresh document/process state. The worker must serve the shell, plan, manual and task history from disk.
  await nav(job);
  await ready('document.body.innerText.includes("Saved job") && document.body.innerText.includes('+JSON.stringify(t.plan.title)+')');
  assert.equal(await ev('fetch('+JSON.stringify(endpoint+'/artifacts/reference-1')+').then(r=>r.text())'),'reference image fixture');
  assert.equal(await ev('fetch("/v1/tasks").then(r=>r.json()).then(rows=>rows[0].taskId)'),t.taskId);
  const move=endpoint+'/steps/'+a.id+':complete';
  for(let i=0;i<2;i++)assert.equal(await ev('fetch('+JSON.stringify(move)+',{method:"POST",headers:{"Idempotency-Key":"offline-move"}}).then(r=>r.json()).then(x=>x.code)'),'job_update_saved');
  await nav(job);
  const cached=await ev('fetch('+JSON.stringify(endpoint)+').then(r=>r.json())');
  assert.equal(cached.currentStepId,b.id);assert.equal(cached.pendingUpdates,1);
  offline=false;
  await ev('navigator.serviceWorker.controller.postMessage({type:"sync-jobs"})');
  await until(()=>acceptedKeys.length>0,'sync');await ready('fetch("/job-updates").then(r=>r.json()).then(x=>x.length===0)');
  assert.deepEqual(acceptedKeys,['offline-move']);
  console.log('PASS: offline shell, history, accepted plan, reference, pending step, single replay.');
}finally{
  ws?.close(); server.closeAllConnections();server.close();
  chrome.kill('SIGTERM');await Promise.race([once(chrome,'exit').catch(()=>{}),wait(2000)]); if(chrome.exitCode===null)chrome.kill('SIGKILL');
  fs.rmSync(profile,{force:true,recursive:true,maxRetries:3});
}
