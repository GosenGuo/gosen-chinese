import test from "node:test";
import assert from "node:assert/strict";
import worker,{RefillQueue,validateRefill} from "../src/index.js";
const copy=value=>structuredClone(value);
function setup(){
  const data=new Map(),alarms=[],requests=[],runs=[];
  const ctx={storage:{async get(key){return data.has(key)?copy(data.get(key)):undefined;},
    async put(key,value){data.set(key,copy(value));},async setAlarm(value){alarms.push(value);}},
    async blockConcurrencyWhile(fn){return fn();}};
  const env={GITHUB_OWNER:"GosenGuo",GITHUB_REPO:"gosen-chinese",GITHUB_TOKEN:"private-test-token",APP_TRIGGER_TOKEN:"limited-app-capability",
    async TEST_FETCH(url,options){requests.push({url,options});return url.includes("/dispatches")?new Response(null,{status:204}):Response.json({workflow_runs:runs});}};
  const queue=new RefillQueue(ctx,env);env.REFILL_QUEUE={idFromName(){return "queue";},get(){return {fetch:request=>queue.fetch(request)};}};
  return {queue,env,data,requests,runs,alarms};
}
const request=module=>new Request("https://service/api/refill",{method:"POST",headers:{"Authorization":"Bearer limited-app-capability"},
  body:JSON.stringify({module,unreadCount:4,requestedCount:30,appVersion:"0.2.0"})});
test("only each category below five can request exactly thirty",async()=>{
  const s=setup();const ok=await worker.fetch(request("modern"),s.env);assert.equal(ok.status,202);
  assert.throws(()=>validateRefill({module:"modern",unreadCount:4,requestedCount:300,appVersion:"x"}));
  const enough=new Request(request("essay"),{body:JSON.stringify({module:"essay",unreadCount:5,requestedCount:30,appVersion:"x"})});
  assert.equal((await (await worker.fetch(enough,s.env)).json()).reason,"enough_unread");
  const denied=new Request("https://service/api/refill",{method:"POST",body:"{}"});assert.equal((await worker.fetch(denied,s.env)).status,401);
});
test("three modules stay queued and only one workflow is dispatched",async()=>{
  const s=setup();for(const module of ["modern","classical","essay"])assert.equal((await worker.fetch(request(module),s.env)).status,202);
  assert.equal((await worker.fetch(request("modern"),s.env)).status,409);
  await s.queue.alarm();assert.equal(s.requests.filter(x=>x.url.includes("/dispatches")).length,1);
  const state=s.data.get("state"),payload=JSON.parse(s.requests[0].options.body);
  assert.equal(state.active.status,"queued");
  assert.equal(payload.inputs.module,"modern");assert.equal(payload.inputs.target_count,"30");assert.equal(payload.inputs.request_id,state.active.requestId);
  assert.deepEqual(state.queue.map(x=>x.module),["classical","essay"]);
});
test("a completed job releases the next queued module without losing the third",async()=>{
  const s=setup();for(const module of ["modern","classical","essay"])await worker.fetch(request(module),s.env);
  await s.queue.alarm();const active=s.data.get("state").active;
  s.runs.push({id:123,display_title:"Refill modern "+active.requestId,status:"completed",conclusion:"success"});
  await s.queue.alarm();assert.equal(s.data.get("state").active.module,"classical");
  assert.deepEqual(s.data.get("state").queue.map(x=>x.module),["essay"]);assert.equal(s.data.get("state").history.length,1);
});
test("transient observation failure never repeats a paid dispatch",async()=>{
  const s=setup();await worker.fetch(request("modern"),s.env);await s.queue.alarm();
  await s.queue.alarm();await s.queue.alarm();
  assert.equal(s.requests.filter(x=>x.url.includes("/dispatches")).length,1);
  assert.equal(s.data.get("state").active.awaitingObservation,true);
});
test("queue survives another object instance and health does not disclose credentials",async()=>{
  const s=setup();await worker.fetch(request("modern"),s.env);
  const health=await (await worker.fetch(new Request("https://service/health"),s.env)).text();
  assert.ok(!health.includes("private-test-token"));assert.ok(!health.includes("limited-app-capability"));
  const again=new RefillQueue(s.queue.ctx,s.env);await again.alarm();
  assert.equal(s.data.get("state").active.module,"modern");
});
