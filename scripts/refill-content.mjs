import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createPackyClient,redactSecrets} from './packy-client.mjs';
import {generateOne,chooseSeed,studyDay,MAX_OUTPUT_TOKENS} from './generate-daily-content.mjs';
import {validateFeed,fingerprint} from './validate-content-feed.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const MODULES=['modern','classical','essay'];
const ledgerFile=projectRoot=>path.join(projectRoot,'docs/refill-ledger.json');
async function read(file,fallback){try{return JSON.parse(await fs.readFile(file,'utf8'));}catch(error){if(error.code==='ENOENT'&&fallback!==undefined)return fallback;throw error;}}
async function write(file,data){
  await fs.mkdir(path.dirname(file),{recursive:true});const temporary=file+'.'+crypto.randomUUID()+'.tmp';
  try{await fs.writeFile(temporary,JSON.stringify(data,null,2)+'\n');await fs.rename(temporary,file);}
  catch(error){await fs.unlink(temporary).catch(()=>{});throw error;}
}
function input(module,targetCount,batchId){
  if(!MODULES.includes(module))throw Error('Unknown refill module');
  if(!Number.isSafeInteger(targetCount)||targetCount<1||targetCount>30)throw Error('target_count must be 1–30');
  if(typeof batchId!=='string'||!batchId.match(/^[A-Za-z0-9_-]{1,80}$/))throw Error('A stable batch identifier is required');
}
export async function reserveRefill({projectRoot=root,module,targetCount=30,batchId,now=new Date()}={}){
  input(module,targetCount,batchId);validateFeed(await read(path.join(projectRoot,'docs/library.json')));
  const ledger=await read(ledgerFile(projectRoot),{schemaVersion:1,batches:{}});
  if(ledger.schemaVersion!==1||!ledger.batches||Array.isArray(ledger.batches))throw Error('Invalid refill ledger');
  const key=module+':'+batchId;if(ledger.batches[key])return {eligible:false,key,status:ledger.batches[key].status};
  ledger.batches[key]={module,batchId,targetCount,status:'reserved',reservedAt:now.toISOString(),
    maximumCalls:targetCount*2,maximumOutputTokensPerCall:MAX_OUTPUT_TOKENS,attempts:[],addedIds:[]};
  await write(ledgerFile(projectRoot),ledger);return {eligible:true,key,module,batchId,targetCount};
}
function questionKey(item){
  const normalized=(item.questions||[]).map(({id,...q})=>q);
  return fingerprint(normalized.map(value=>fingerprint(value)).sort());
}
function duplicateOf(candidate,items){
  const bodyHash=fingerprint(candidate.body.replace(/\s/g,''));
  for(const old of items){
    if(old.module!==candidate.module||fingerprint(old.body.replace(/\s/g,''))!==bodyHash)continue;
    if(candidate.module!=='classical')return true;
    if(questionKey(old)===questionKey(candidate))return true;
    if((old.questions||[]).map(q=>q.prompt).sort().join('\n')===(candidate.questions||[]).map(q=>q.prompt).sort().join('\n'))return true;
  }
  return false;
}
export async function runRefill({projectRoot=root,module,targetCount=30,batchId,client,now=new Date(),log=()=>{}}={}){
  input(module,targetCount,batchId);
  const ledger=await read(ledgerFile(projectRoot)),entry=ledger.batches?.[module+':'+batchId];
  if(!entry||entry.status!=='reserved'||entry.targetCount!==targetCount)throw Error('No unused matching batch reservation; no API request made');
  const feedFile=path.join(projectRoot,'docs/library.json');let feed=validateFeed(await read(feedFile));
  const seeds=(await read(path.join(projectRoot,'scripts/sources/classical-daily-seeds.json'))).items;
  const day=studyDay(now),api=client||createPackyClient(),plannedSeeds=[],maximumCalls=targetCount*2;
  if(module==='classical'){
    const virtual={items:[...feed.items]};
    for(let n=0;n<maximumCalls;n++){const selected=chooseSeed(seeds,virtual,day);plannedSeeds.push(selected);virtual.items.push({module:'classical',body:selected.seed.body,questions:[]});}
  }
  entry.status='running';entry.startedAt=now.toISOString();await write(ledgerFile(projectRoot),ledger);
  let cursor=0,serial=Promise.resolve(),fatal=null;
  const serialize=work=>{const task=serial.then(work);serial=task.catch(()=>{});return task;};
  async function worker(){
    while(cursor<maximumCalls&&!fatal&&entry.addedIds.length<targetCount){
      const index=cursor++,id='refill-'+module+'-'+day.replaceAll('-','')+'-'+batchId+'-'+String(index+1).padStart(2,'0');
      try{
        await serialize(async()=>{entry.attempts[index]={index,status:'attempted',attemptedAt:new Date().toISOString()};await write(ledgerFile(projectRoot),ledger);});
        const result=await generateOne(module,{api,day,sequence:index,idOverride:id,seedSelectionOverride:plannedSeeds[index],
          feed:{...feed,items:[...feed.items]},seeds,onResponse:metadata=>serialize(async()=>{
            entry.attempts[index]={...entry.attempts[index],status:'responded',metadata};await write(ledgerFile(projectRoot),ledger);
          })});
        await serialize(async()=>{
          if(entry.addedIds.length>=targetCount){
            entry.attempts[index]={...entry.attempts[index],status:'discarded',reason:'target reached'};
            await write(ledgerFile(projectRoot),ledger);return;
          }
          if(duplicateOf(result.item,feed.items))throw Error('Duplicate learning material refused');
          if(feed.items.some(item=>item.id===result.item.id))throw Error('Repeated content identifier refused');
          const next={...feed,contentVersion:feed.contentVersion+1,generatedAt:new Date().toISOString(),items:[...feed.items,result.item]};
          validateFeed(next);await write(feedFile,next);feed=next;
          entry.attempts[index]={...entry.attempts[index],status:'published',contentId:result.item.id};
          entry.addedIds.push(result.item.id);entry.contentVersion=feed.contentVersion;
          await write(ledgerFile(projectRoot),ledger);
          log({accepted:entry.addedIds.length,targetCount,module,id:result.item.id,attempt:index+1});
        });
      }catch(error){
        try{
          await serialize(async()=>{
            entry.attempts[index]={...entry.attempts[index],status:'failed',error:redactSecrets(error.message,[process.env.PACKY_API_KEY]).slice(0,500)};
            await write(ledgerFile(projectRoot),ledger);
          });
          log({rejected:true,module,attempt:index+1,error:redactSecrets(error.message,[process.env.PACKY_API_KEY]).slice(0,200)});
        }catch(storageError){fatal=storageError;break;}
      }
    }
  }
  // Two generators replenish rejected candidates, with a strict total request budget.
  await Promise.all([worker(),worker()]);await serial;
  if(fatal)throw fatal;
  entry.status=entry.addedIds.length===targetCount?'complete':entry.addedIds.length?'partial':'failed';
  entry.completedAt=new Date().toISOString();entry.acceptedCount=entry.addedIds.length;entry.attemptedCount=entry.attempts.length;
  await write(ledgerFile(projectRoot),ledger);
  return {ok:entry.addedIds.length>0,complete:entry.status==='complete',module,targetCount,acceptedCount:entry.addedIds.length,
    rejectedCount:entry.attempts.filter(attempt=>attempt.status==='failed').length,maximumCalls,contentVersion:feed.contentVersion};
}
function argumentsOf(args){
  const task=args.shift(),known=new Set(['module','target-count','batch-id']);const parsed={};
  for(let i=0;i<args.length;i+=2){const flag=args[i];if(!flag?.startsWith('--')||!known.has(flag.slice(2))||!args[i+1])throw Error('Invalid refill option');parsed[flag.slice(2)]=args[i+1];}
  return {task,module:parsed.module||process.env.REFILL_MODULE,targetCount:Number(parsed['target-count']||process.env.TARGET_COUNT||30),
    batchId:parsed['batch-id']||process.env.GENERATION_RUN_ID};
}
async function cli(){
  const options=argumentsOf(process.argv.slice(2));
  if(options.task==='reserve'){
    const result=await reserveRefill(options);
    if(process.env.GITHUB_OUTPUT)await fs.appendFile(process.env.GITHUB_OUTPUT,'eligible='+result.eligible+'\n');
    console.log(JSON.stringify(result));return;
  }
  if(options.task==='generate'){
    const result=await runRefill({...options,log:value=>console.log(JSON.stringify(value))});
    console.log(JSON.stringify(result));if(!result.ok)process.exitCode=1;return;
  }
  throw Error('Use refill-content.mjs reserve|generate with module, target-count and a stable batch-id');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))cli().catch(error=>{
  console.error(JSON.stringify({ok:false,error:redactSecrets(error.message,[process.env.PACKY_API_KEY]).slice(0,500)}));process.exitCode=1;
});
