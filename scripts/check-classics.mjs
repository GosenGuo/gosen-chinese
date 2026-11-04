import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const data=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/classics.json'),'utf8'));
assert.equal(data.items.length,60,'60篇目录必须完整');
assert.equal(new Set(data.items.map(x=>x.id)).size,60,'文章ID必须唯一');
assert.equal(data.items.filter(x=>x.kind==='prose').length,20);
assert.equal(data.items.filter(x=>x.kind==='poem').length,40);
let notes=0,translations=0,questions=0;
for(const item of data.items){
 assert.ok(item.body.length>25,`${item.id}原文为空或异常`);
 assert.ok(!/[�]|undefined|<div/.test(item.body),`${item.id}原文乱码或HTML残留`);
 assert.ok(item.sourceUrl.startsWith('https://'),`${item.id}缺少来源`);
 assert.ok(item.notes.length>=3,`${item.id}缺少重点注释`);
 for(const note of item.notes){
  assert.ok(item.body.includes(note.quote),`${item.id}注释不在原文：${note.quote}`);
  assert.ok(item.body.includes(note.sentence),`${item.id}注释语境不在原文`);
  assert.ok(note.meaning&&note.source,`${item.id}注释缺少释义或来源`);
  assert.equal(note.occurrence,0);
  notes++;
 }
 for(const t of item.translations){assert.ok(item.body.includes(t.quote),`${item.id}译文定位失效`);assert.ok(t.translation);translations++;}
 for(const q of item.questions){
  assert.ok(q.prompt&&q.answer&&q.analysis);
  assert.ok(q.evidence.every(x=>item.body.includes(x)),`${item.id}题目证据失效`);
  assert.equal(q.points.reduce((n,x)=>n+x.score,0),q.score);
  questions++;
 }
}
console.log(JSON.stringify({result:'PASS',items:60,notes,translations,questions,scope:'结构、定位和引用检查，不代表教材逐字核验或语义人工审定'},null,2));
