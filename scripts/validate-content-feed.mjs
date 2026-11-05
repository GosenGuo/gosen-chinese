import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import crypto from 'node:crypto';

export const text=(value,name)=>{assert.equal(typeof value,'string',name+' must be text');assert.ok(value.trim(),name+' must not be empty');return value;};
export const hanCount=value=>(String(value).match(/\p{Script=Han}/gu)||[]).length;
export const fingerprint=value=>crypto.createHash('sha256').update(typeof value==='string'?value:JSON.stringify(value)).digest('hex');
export function validateItem(item){
  assert(item&&typeof item==='object'&&!Array.isArray(item));
  assert.match(text(item.id,'id'),/^[A-Za-z0-9_-]{1,140}$/);
  assert(['classics','modern','classical','essay'].includes(item.module),'unknown module');
  for(const field of ['title','body','sourceLabel','verification'])text(item[field],field);
  assert(item.body.length>=25&&item.body.length<=100000&&!item.body.includes('\ufffd'),'invalid body');
  const notes=item.notes||[],translations=item.translations||[];
  assert(Array.isArray(notes)&&Array.isArray(translations));
  const noteKeys=new Set();
  for(const note of notes){
    const quote=text(note.quote,'note.quote');text(note.meaning,'note.meaning');text(note.sentence,'note.sentence');
    assert(Number.isSafeInteger(note.occurrence)&&note.occurrence>=0,'invalid occurrence');
    let at=-1,from=0;for(let n=0;n<=note.occurrence;n++){at=item.body.indexOf(quote,from);assert(at>=0,'missing note quote');from=at+quote.length;}
    let sentenceAt=item.body.indexOf(note.sentence),matches=false;
    while(sentenceAt>=0){if(at>=sentenceAt&&at+quote.length<=sentenceAt+note.sentence.length){matches=true;break;}sentenceAt=item.body.indexOf(note.sentence,sentenceAt+note.sentence.length);}
    assert(matches,'note belongs to a different sentence');const key=quote+':'+note.occurrence;assert(!noteKeys.has(key),'duplicate note location');noteKeys.add(key);
  }
  for(const unit of translations){assert(item.body.includes(text(unit.quote,'translation.quote')),'translation quote missing');text(unit.translation,'translation');}
  if(item.module!=='essay'){
    assert(Array.isArray(item.questions)&&item.questions.length>0,'questions missing');const ids=new Set();
    for(const q of item.questions){
      assert(!ids.has(text(q.id,'question.id')),'duplicate question');ids.add(q.id);
      assert(['single','short','translation','segmentation'].includes(q.type),'unknown question type');
      for(const field of ['prompt','answer','analysis','focus'])text(q[field],'question.'+field);
      assert(Array.isArray(q.evidence)&&q.evidence.length,'evidence missing');
      for(const quote of q.evidence)assert(item.body.includes(text(quote,'evidence')),'evidence not in passage');
      assert(Array.isArray(q.points)&&q.points.length,'points missing');
      let score=0;for(const point of q.points){text(point.text,'point');assert(Number.isSafeInteger(point.score)&&point.score>0);score+=point.score;}
      assert.equal(q.score,score,'score mismatch');
      if(q.type==='single'){
        assert.equal(q.options?.length,4);assert.equal(q.optionAnalyses?.length,4);assert(Number.isSafeInteger(q.answerIndex)&&q.answerIndex>=0&&q.answerIndex<4);
        q.options.forEach(option=>text(option,'option'));q.optionAnalyses.forEach(option=>text(option,'option analysis'));assert.equal(q.answer,q.options[q.answerIndex]);
      }
    }
  }
  if(item.module==='modern')assert.equal(notes.length,0,'modern reading has no clickable word cards');
  if(item.module==='classical'){
    assert(notes.length&&translations.length,'classical annotations missing');
    assert.equal(translations.map(unit=>unit.quote).join('').replace(/\s/g,''),item.body.replace(/\s/g,''),'classical translation must cover the original');
  }
  if(item.module==='essay'){
    for(const field of ['prompt','requirements','exercise'])text(item[field],field);
    assert(Array.isArray(item.outline)&&item.outline.length>=4,'essay outline missing');
    for(const part of item.outline){text(part.title,'outline.title');text(part.text,'outline.text');}
    assert(!item.isFullScore&&!('examScore' in item)&&!('score' in item),'unverified score');
    if(item.generatedAt){assert(hanCount(item.body)>=800&&hanCount(item.body)<=1600,'generated essay length');assert.equal(item.contentType,'AI示范作文');}
  }
  return item;
}
export function validateFeed(feed){
  assert.equal(feed.schemaVersion,1);assert(Number.isSafeInteger(feed.contentVersion)&&feed.contentVersion>0);
  assert(Number.isSafeInteger(feed.bundleVersionCode)&&feed.bundleVersionCode>0);assert(Number.isFinite(Date.parse(feed.generatedAt)),'generatedAt');
  assert(Array.isArray(feed.items)&&feed.items.length>=63&&feed.items.length<=5000);const ids=new Set(),modules=new Set();let classics=0;
  for(const item of feed.items){validateItem(item);assert(!ids.has(item.id),'duplicate item');ids.add(item.id);modules.add(item.module);if(item.module==='classics')classics++;}
  assert.equal(classics,60,'classic catalog must retain its 60 original entries');for(const module of ['modern','classical','essay'])assert(modules.has(module),'missing module '+module);
  return feed;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const target=process.argv[2]||new URL('../docs/library.json',import.meta.url);const feed=validateFeed(JSON.parse(fs.readFileSync(target,'utf8')));
  console.log(JSON.stringify({ok:true,contentVersion:feed.contentVersion,items:feed.items.length,generatedAt:feed.generatedAt}));
}
