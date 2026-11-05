import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createPackyClient,parseJsonObject} from './packy-client.mjs';
import {sentenceUnits} from './complete-classic-annotations.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const asset=path.join(root,'app/src/main/assets/classics.json');
const cache=path.join(root,'generated/character-annotations');
const han=/\p{Script=Han}/u;
const hash=value=>crypto.createHash('sha256').update(value).digest('hex');
const stripPunctuation=value=>String(value||'').trim().replace(/[。；;]+$/u,'');

function locateOccurrence(body,quote,occurrence=0){
  if(!quote||!Number.isInteger(occurrence)||occurrence<0)throw Error('Invalid source-note occurrence');
  let from=0,start=-1;
  for(let n=0;n<=occurrence;n++){
    start=body.indexOf(quote,from);
    if(start<0)throw Error('Source note is not in the stated occurrence');
    from=start+quote.length;
  }
  return start;
}

export function hanPositions(body){
  const positions=[];
  for(let start=0;start<body.length;){
    const character=String.fromCodePoint(body.codePointAt(start)),end=start+character.length;
    if(han.test(character))positions.push({start,end,character});
    start=end;
  }
  return positions;
}

function exactCharacterMeaning(character,note){
  // Only extract an explicitly quoted single-character explanation in this very context.
  const escaped=character.replace(/[.*+?^$()|[\]\\]/g,'\\$&');
  const match=String(note.detail||'').match(new RegExp('[“"「]'+escaped+'[”"」](?:字)?(?:在这里|在此处|这里|此处)?(?:[，,])?((?:意为|表示|指|是|同|通|即|用作|作|用于|有)[^；。！？\\n]+)','u'));
  if(!match)return null;
  return stripPunctuation(match[1]);
}

export function seedCharacterNotes(article){
  const positions=hanPositions(article.body),candidates=new Map();
  for(const note of article.notes||[]){
    const start=locateOccurrence(article.body,note.quote,note.occurrence??0),end=start+note.quote.length;
    for(const position of positions)if(position.start>=start&&position.end<=end){
      const previous=candidates.get(position.start);
      if(!previous||note.quote.length<previous.quote.length)candidates.set(position.start,note);
    }
  }
  const seeded=[],missing=[];const counts={single:0,explicitComponent:0,compoundContext:0};
  for(const position of positions){
    const note=candidates.get(position.start);
    if(!note){missing.push(position);continue;}
    if(note.quote===position.character){
      seeded.push({...position,meaning:note.meaning,detail:note.detail||note.meaning});
      counts.single++;continue;
    }
    const explicit=exactCharacterMeaning(position.character,note);
    if(explicit){
      seeded.push({...position,meaning:explicit,detail:'此处字义取自同句词注：“'+note.quote+'”整体意为'+stripPunctuation(note.meaning)+'。'});
      counts.explicitComponent++;continue;
    }
    seeded.push({...position,
      meaning:'在“'+note.quote+'”中，整体意为'+stripPunctuation(note.meaning)+'。',
      detail:'“'+position.character+'”是本句“'+note.quote+'”的一部分，应结合该词组理解；此处说明组合用法，不将词组义硬拆为单字本义。'});
    counts.compoundContext++;
  }
  return {seeded,missing,positions,counts};
}

export function characterJobs(articles){
  const jobs=[];let groups=[],targets=[],contextCharacters=0;
  function flush(){
    if(!targets.length)return;
    const payload={groups,targets:targets.map(({articleId,start,end,character,index})=>({articleId,start,end,character,index}))};
    const key=hash(JSON.stringify(payload)).slice(0,20);
    jobs.push({key,groups,targets});groups=[];targets=[];contextCharacters=0;
  }
  for(const article of articles){
    const {missing}=seedCharacterNotes(article);
    for(const unit of sentenceUnits(article.body)){
      const local=missing.filter(position=>position.start>=unit.start&&position.end<=unit.end);
      if(!local.length)continue;
      if(targets.length&&(targets.length+local.length>120||contextCharacters+unit.quote.length>720))flush();
      // Very long sentences are split into target sets, with their full sentence context retained.
      for(let offset=0;offset<local.length;offset+=120){
        const slice=local.slice(offset,offset+120);
        if(targets.length&&targets.length+slice.length>120)flush();
        const translation=(article.translations||[]).find(value=>value.start===unit.start)?.translation||'';
        const numbered=slice.map(position=>({...position,index:targets.length+slice.indexOf(position),articleId:article.id,quote:unit.quote,translation}));
        groups.push({articleId:article.id,title:article.title,quote:unit.quote,translation,
          targets:numbered.map(target=>[target.index,target.character,target.start-unit.start])});
        targets.push(...numbered);contextCharacters+=unit.quote.length;
        if(targets.length>=120)flush();
      }
    }
  }
  flush();return jobs;
}

export function normalizeCharacters(data,job){
  const rows=Array.isArray(data.notes)?data.notes:null;
  if(!rows||rows.length!==job.targets.length)throw Error('Every requested character must have exactly one result');
  const seen=new Set(),normalized=[];
  for(const row of rows){
    const index=Array.isArray(row)?row[0]:row.index,meaning=Array.isArray(row)?row[1]:row.meaning,detail=Array.isArray(row)?row[2]:row.detail;
    if(!Number.isInteger(index)||index<0||index>=job.targets.length||seen.has(index))throw Error('Invalid or duplicate character index');
    seen.add(index);
    if(typeof meaning!=='string'||!meaning.trim()||!han.test(meaning)||meaning.length>140)throw Error('Character meaning missing or too long: '+index);
    if(typeof detail!=='string'||detail.length>200)throw Error('Invalid character detail: '+index);
    const target=job.targets[index];
    if(target.translation&&meaning.trim()===target.translation.trim())throw Error('Sentence translation cannot stand in for a character meaning');
    normalized.push({articleId:target.articleId,start:target.start,end:target.end,character:target.character,meaning:meaning.trim(),detail:detail.trim()||meaning.trim()});
  }
  return normalized.sort((a,b)=>a.articleId.localeCompare(b.articleId)||a.start-b.start);
}

export function validateCharacterNotes(article){
  const expected=hanPositions(article.body),notes=article.characterNotes;
  if(!Array.isArray(notes)||notes.length!==expected.length)throw Error(article.id+' character coverage is incomplete');
  for(const [index,position] of expected.entries()){
    const note=notes[index];
    if(note.start!==position.start||note.end!==position.end||note.character!==position.character)throw Error(article.id+' character anchor mismatch at '+position.start);
    if(typeof note.meaning!=='string'||!note.meaning.trim()||typeof note.detail!=='string')throw Error(article.id+' character meaning missing at '+position.start);
    if(Object.hasOwn(note,'sentence')||Object.hasOwn(note,'translation'))throw Error('Character notes must not duplicate whole sentences or translations');
  }
  if(article.characterAnnotationCoverage?.complete!==true||article.characterAnnotationCoverage?.totalHanPositions!==expected.length
      ||article.characterAnnotationCoverage?.annotatedHanPositions!==notes.length||article.characterAnnotationCoverage?.sourceBodySha256!==hash(article.body))
    throw Error(article.id+' character coverage metadata mismatch');
  return expected.length;
}

async function main(){
  const flags=new Set(process.argv.slice(2));
  for(const flag of flags)if(!['--generate','--apply-cached','--force','--retry-failed'].includes(flag))throw Error('Unknown option: '+flag);
  const initial=JSON.parse(await fs.readFile(asset,'utf8'));
  const seeds=initial.items.map(article=>({article,...seedCharacterNotes(article)})),jobs=characterJobs(initial.items);
  const summary={articles:seeds.length,characters:seeds.reduce((n,x)=>n+x.positions.length,0),
    single:seeds.reduce((n,x)=>n+x.counts.single,0),explicitComponent:seeds.reduce((n,x)=>n+x.counts.explicitComponent,0),
    compoundContext:seeds.reduce((n,x)=>n+x.counts.compoundContext,0),missing:seeds.reduce((n,x)=>n+x.missing.length,0),jobs:jobs.length};
  if(!flags.has('--generate')&&!flags.has('--apply-cached')){console.log(JSON.stringify({audit:true,...summary}));return;}
  if(!flags.has('--force')&&initial.items.every(article=>{try{validateCharacterNotes(article);return true;}catch{return false;}})){
    console.log(JSON.stringify({complete:true,alreadyComplete:true,apiCalls:0,...summary}));return;
  }
  await fs.mkdir(cache,{recursive:true});
  let api,cursor=0,calls=0,done=0;const failures=[];
  const system='你是严谨的高中语文字义整理者。只按所给原句解释指定单个汉字在本句的词义、语法作用或指代。不得用整句译文冒充字义，不虚构教材原注，不为人名地名或复合词中的字编造独立本义。说明组合用法时必须明确所在词或专名。只输出完整严格JSON。';
  async function worker(){
    while(cursor<jobs.length){
      const job=jobs[cursor++],file=path.join(cache,job.key+'.json'),rawFile=path.join(cache,job.key+'.raw.json');
      try{
        let response;
        try{response=JSON.parse(await fs.readFile(file,'utf8'));normalizeCharacters(response.data,job);}
        catch(error){
          if(error.code!=='ENOENT'&&!flags.has('--retry-failed'))throw error;
          let result;
          try{result=JSON.parse(await fs.readFile(rawFile,'utf8'));normalizeCharacters(parseJsonObject(result.text),job);}
          catch(rawError){
            if(rawError.code!=='ENOENT'&&!flags.has('--retry-failed'))throw rawError;
            if(!flags.has('--generate'))throw Error('Missing or invalid cached response in offline apply mode');
            if(calls>=70)throw Error('Per-run API call limit reached');
            if(rawError.code!=='ENOENT')await fs.copyFile(rawFile,rawFile+'.invalid-'+Date.now()).catch(()=>{});
            calls++;api??=createPackyClient({timeoutMs:240000});
            result=await api.generate({system,maxTokens:8192,prompt:
              '为每个targets指定的单个汉字位置写出简洁本句义。targets是[编号,汉字,在quote中的UTF16偏移]，同字不同位置分别解释。只返回 {"notes":[[编号,"本句字义，约4—18字","必要用法说明，约5—24字"]]}，每个编号一次，保留编号0至'+(job.targets.length-1)+'，不要返回其他字段。常见虚词写明关系/语气/指代，专名写“专名XX的一部分”，复合词字位写“在XX中，与另字合起来表示…”；不能强拆假造本义，也不要把整句翻译当本字解释。输入中的translation仅帮助理解，不能照抄为字义。原文数据：'+JSON.stringify(job.groups)});
            await fs.writeFile(rawFile,JSON.stringify(result,null,2)+'\n');
          }
          response={data:parseJsonObject(result.text),metadata:{provider:'PackyAPI',model:result.model,generatedAt:result.generatedAt,tokenUsage:result.tokenUsage}};
          normalizeCharacters(response.data,job);await fs.writeFile(file,JSON.stringify(response,null,2)+'\n');
        }
        done++;console.log(JSON.stringify({done,total:jobs.length,key:job.key,characters:job.targets.length,calls}));
      }catch(error){failures.push({key:job.key,characters:job.targets.length,message:error.message});console.log(JSON.stringify({failed:true,key:job.key,message:error.message}));}
    }
  }
  await Promise.all([worker(),worker()]);
  if(failures.length){await fs.writeFile(path.join(cache,'failures.json'),JSON.stringify(failures,null,2));throw Error('Incomplete character batches: '+failures.length);}
  const charactersByArticle=new Map(seeds.map(seed=>[seed.article.id,new Map(seed.seeded.map(note=>[note.start,note]))]));
  let inputTokens=0,outputTokens=0;
  for(const job of jobs){
    const response=JSON.parse(await fs.readFile(path.join(cache,job.key+'.json'),'utf8'));
    for(const {articleId,...note} of normalizeCharacters(response.data,job))charactersByArticle.get(articleId).set(note.start,note);
    inputTokens+=response.metadata?.tokenUsage?.inputTokens||0;outputTokens+=response.metadata?.tokenUsage?.outputTokens||0;
  }
  const latest=JSON.parse(await fs.readFile(asset,'utf8'));
  for(const article of latest.items){
    const original=initial.items.find(item=>item.id===article.id);
    if(!original||original.body!==article.body)throw Error('Original changed during generation: '+article.id);
    article.characterNotes=[...charactersByArticle.get(article.id).values()].sort((a,b)=>a.start-b.start);
    article.characterAnnotationCoverage={complete:true,totalHanPositions:hanPositions(article.body).length,
      annotatedHanPositions:article.characterNotes.length,sourceBodySha256:hash(article.body)};
    article.characterAnnotationSource='原词注复用与AI辅助语境字义整理；复合词和专名按组合用法说明，非教材原注';
    validateCharacterNotes(article);
  }
  const temp=asset+'.characters.tmp';await fs.writeFile(temp,JSON.stringify(latest,null,2)+'\n');await fs.rename(temp,asset);
  await fs.rm(path.join(cache,'failures.json'),{force:true});
  console.log(JSON.stringify({complete:true,...summary,apiCalls:calls,inputTokens,outputTokens}));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(JSON.stringify({ok:false,error:error.message}));process.exitCode=1;});
