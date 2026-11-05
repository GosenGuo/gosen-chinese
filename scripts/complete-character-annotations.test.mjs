import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {hanPositions,seedCharacterNotes,characterJobs,normalizeCharacters,validateCharacterNotes} from './complete-character-annotations.mjs';
const note=(quote,meaning,detail=meaning,occurrence=0)=>({quote,occurrence,meaning,detail,sentence:quote,source:'test'});

test('source single-character senses retain their precise occurrence and are never applied globally',()=>{
  const article={id:'a',title:'示例',body:'其一，其二。',notes:[note('其','第一处义','第一处用法',0)]};
  const result=seedCharacterNotes(article);
  assert.equal(result.seeded.length,1);
  assert.equal(result.seeded[0].start,0);
  assert.ok(result.missing.some(position=>position.start===3));
});
test('compound words explicitly retain whole-word meaning rather than inventing component definitions',()=>{
  const article={id:'a',body:'苜蓿。',notes:[note('苜蓿','一种植物','植物名称')]};
  const {seeded,missing}=seedCharacterNotes(article);
  assert.equal(missing.length,0);assert.equal(seeded.length,2);
  assert.match(seeded[0].meaning,/在“苜蓿”中，整体/);
  assert.match(seeded[1].detail,/不将词组义硬拆/);
  assert.deepEqual(seeded.map(value=>[value.start,value.end,value.character]),[[0,1,'苜'],[1,2,'蓿']]);
});
test('an explicit character definition can be extracted only from the very same source context',()=>{
  const article={id:'a',body:'金就砺。',notes:[note('金就砺','刀剑等拿到磨刀石上去磨。','“金”指金属制成的刀剑等；“就”是靠近、接近；“砺”是磨刀石。')]};
  const {seeded,counts}=seedCharacterNotes(article);
  assert.equal(counts.explicitComponent,3);
  assert.match(seeded[0].meaning,/指金属/);assert.match(seeded[1].meaning,/靠近/);
});
test('generation targets are compact numbered single-character positions with matching context',()=>{
  const article={id:'a',title:'课文',body:'甲乙丙。甲丁。',notes:[note('乙','乙的意思')],translations:[{start:0,quote:'甲乙丙。',translation:'第一句。'}]};
  const jobs=characterJobs([article]);assert.equal(jobs.length,1);
  const job=jobs[0];assert.deepEqual(job.targets.map(target=>target.index),[0,1,2,3]);
  const result=normalizeCharacters({notes:job.targets.map(target=>[target.index,'本句字义','词语用法'])},job);
  assert.equal(result.length,4);assert.equal(result[2].start,4);
  assert.throws(()=>normalizeCharacters({notes:[[0,'义','说明'],[0,'义','说明'],[2,'义','说明'],[3,'义','说明']]},job),/duplicate/);
  assert.throws(()=>normalizeCharacters({notes:job.targets.map(target=>[target.index,target.translation||'本义','说明'])},job),/Sentence translation/);
});
test('coverage includes a supplementary-plane Han character and forbids missing positions or duplicated sentence text',()=>{
  const article={id:'a',body:'𠀀甲。',characterNotes:[{start:0,end:2,character:'𠀀',meaning:'字义',detail:'用法'},{start:2,end:3,character:'甲',meaning:'字义',detail:'用法'}]};
  article.characterAnnotationCoverage={complete:true,totalHanPositions:2,annotatedHanPositions:2,
    sourceBodySha256:crypto.createHash('sha256').update(article.body).digest('hex')};
  assert.deepEqual(hanPositions(article.body).map(value=>value.end),[2,3]);
  assert.equal(validateCharacterNotes(article),2);
  const missing=structuredClone(article);missing.characterNotes.pop();assert.throws(()=>validateCharacterNotes(missing),/incomplete/);
  article.characterNotes[0].translation='一句译文';assert.throws(()=>validateCharacterNotes(article),/must not duplicate/);
});
