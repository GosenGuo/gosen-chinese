const MODULES=new Set(["modern","classical","essay"]);
const COOLDOWN=6*60*60*1000;
const POLL=60*1000;
const response=(data,status=200)=>Response.json(data,{status,headers:{"Cache-Control":"no-store"}});
function authorized(request,expected){
  if(!expected)return false;
  const value=request.headers.get("Authorization")||"",target="Bearer "+expected;
  if(value.length!==target.length)return false;
  let difference=0;for(let i=0;i<value.length;i++)difference|=value.charCodeAt(i)^target.charCodeAt(i);
  return difference===0;
}
export function validateRefill(body){
  if(!body||!MODULES.has(body.module))throw Error("Unknown content module");
  if(!Number.isInteger(body.unreadCount)||body.unreadCount<0||body.unreadCount>5000)throw Error("Invalid unread count");
  if(body.requestedCount!==30)throw Error("The refill size is fixed at 30");
  if(typeof body.appVersion!=="string"||body.appVersion.length>40)throw Error("Invalid app version");
  return {module:body.module,unreadCount:body.unreadCount,requestedCount:30,appVersion:body.appVersion};
}
export default {
  async fetch(request,env){
    const url=new URL(request.url);
    if(request.method==="GET"&&url.pathname==="/health")return response({ok:true,ready:!!(env.GITHUB_TOKEN&&env.APP_TRIGGER_TOKEN&&env.REFILL_QUEUE),threshold:5,batchSize:30});
    if(!authorized(request,env.APP_TRIGGER_TOKEN))return response({accepted:false,error:"Unauthorized"},401);
    if(!env.GITHUB_TOKEN||!env.REFILL_QUEUE)return response({accepted:false,error:"Service not configured"},503);
    const stub=env.REFILL_QUEUE.get(env.REFILL_QUEUE.idFromName("library-refill"));
    if(request.method==="GET"&&url.pathname==="/status")return stub.fetch(new Request("https://internal/status"));
    if(request.method!=="POST"||url.pathname!=="/api/refill")return response({accepted:false,error:"Not found"},404);
    try{
      const raw=await request.text();if(raw.length>2048)throw Error("Request too large");
      const body=validateRefill(JSON.parse(raw));
      if(body.unreadCount>=5)return response({accepted:false,reason:"enough_unread",threshold:5});
      return stub.fetch(new Request("https://internal/refill",{method:"POST",body:JSON.stringify(body)}));
    }catch(error){return response({accepted:false,error:"Invalid refill request"},400);}
  }
};

/** One persistent global queue serializes all three content modules without dropping pending jobs. */
export class RefillQueue {
  constructor(ctx,env){this.ctx=ctx;this.env=env;}
  async load(){return await this.ctx.storage.get("state")||{queue:[],active:null,recent:{},history:[]};}
  async save(state){await this.ctx.storage.put("state",state);}
  async fetch(request){
    return this.ctx.blockConcurrencyWhile(async()=>{
      const state=await this.load(),url=new URL(request.url),now=Date.now();
      if(url.pathname==="/status")return response({active:state.active,queue:state.queue,history:state.history.slice(-12),lastError:state.lastError||null});
      const body=validateRefill(await request.json());
      if(body.unreadCount>=5)return response({accepted:false,reason:"enough_unread",threshold:5});
      if(state.active?.module===body.module||state.queue.some(job=>job.module===body.module))
        return response({accepted:true,dispatched:false,reason:"already_queued",module:body.module},409);
      if(now-(state.recent[body.module]||0)<COOLDOWN)
        return response({accepted:true,dispatched:false,reason:"cooldown",module:body.module},409);
      const job={module:body.module,requestId:crypto.randomUUID(),requestedAt:now,requestedCount:30};
      state.queue.push(job);state.recent[body.module]=now;
      await this.save(state);await this.ctx.storage.setAlarm(now+1);
      return response({accepted:true,dispatched:false,reason:"queued",module:body.module,requestId:job.requestId},202);
    });
  }
  headers(){return {"Accept":"application/vnd.github+json","Authorization":"Bearer "+this.env.GITHUB_TOKEN,
    "User-Agent":"gosen-chinese-refill","X-GitHub-Api-Version":"2022-11-28"};}
  async github(suffix,options={}){
    const repository=this.env.GITHUB_OWNER+"/"+this.env.GITHUB_REPO;
    const workflow=this.env.GITHUB_WORKFLOW||"auto-content.yml";
    const url="https://api.github.com/repos/"+repository+"/actions/workflows/"+encodeURIComponent(workflow)+suffix;
    const fetcher=this.env.TEST_FETCH||fetch;
    return fetcher(url,{...options,headers:{...this.headers(),...(options.headers||{})},signal:AbortSignal.timeout(6500)});
  }
  async alarm(){
    await this.ctx.blockConcurrencyWhile(async()=>{
      const state=await this.load(),now=Date.now();
      try{
        if(state.active){
          const result=await this.github("/runs?event=workflow_dispatch&per_page=100");
          if(!result.ok)throw Error("Unable to observe the current generation job");
          const runs=(await result.json()).workflow_runs||[];
          const run=runs.find(item=>(item.display_title||"").includes(state.active.requestId));
          if(!run){
            // A timeout or delayed observation is not proof that a dispatched job did not start.
            state.active.awaitingObservation=true;await this.save(state);await this.ctx.storage.setAlarm(now+POLL);return;
          }
          state.active.runId=run.id;state.active.status=run.status;
          if(run.status!=="completed"){await this.save(state);await this.ctx.storage.setAlarm(now+POLL);return;}
          state.history.push({...state.active,status:"completed",conclusion:run.conclusion,finishedAt:now});
          state.history=state.history.slice(-24);state.active=null;
        }
        if(!state.queue.length){await this.save(state);return;}
        const job=state.queue.shift();
        state.active={...job,status:"dispatching",dispatchedAt:now};
        // Persist the dispatch intent before the network request; unknown outcomes are observed, never blindly retried.
        await this.save(state);
        let result;
        try{
          result=await this.github("/dispatches",{method:"POST",headers:{"Content-Type":"application/json"},
            body:JSON.stringify({ref:this.env.GITHUB_REF||"main",inputs:{module:job.module,target_count:"30",request_id:job.requestId}})});
        }catch(error){
          state.active.status="awaiting_observation";state.lastError="Dispatch outcome is not yet known";
          await this.save(state);await this.ctx.storage.setAlarm(now+POLL);return;
        }
        if(!result.ok){
          state.history.push({...state.active,status:"rejected",httpStatus:result.status,finishedAt:now});
          state.active=null;state.lastError="GitHub rejected the generation request";
        }else{state.active.status="queued";state.lastError=null;}
        await this.save(state);
        if(state.active||state.queue.length)await this.ctx.storage.setAlarm(now+POLL);
      }catch(error){
        state.lastError="Generation status temporarily unavailable";
        await this.save(state);await this.ctx.storage.setAlarm(now+POLL);
      }
    });
  }
}
