import test from 'node:test';
import assert from 'node:assert/strict';
import {sentenceUnits,normalizeResult} from './complete-classic-annotations.mjs';
import {validateCoverage,locateOccurrence} from './check-classics.mjs';
function article(body){
  const units=sentenceUnits(body);
  const normalized=normalizeResult({units:units.map(unit=>({index:unit.index,translation:unit.quote.includes('呜呼')?'唉！':'这是本句的译文。',
    notes:[{quote:unit.quote.match(/\p{Script=Han}+/u)[0],occurrence:0,meaning:'本句字词的意思',detail:'具体用法'}]}))},units,body);
  return {id:'fixture',body,...normalized,translationCoverage:'全文逐句译文',annotationCoverage:{complete:true,sentenceCount:units.length,translatedSentences:units.length}};
}
test('one-character interjection translations are valid and preserve precise quote ranges',()=>{
  const item=article('呜呼！学不可以已。');
  assert.equal(item.translations[0].translation,'唉！');
  assert.equal(validateCoverage(item).sentences,2);
});
test('repeated words resolve to the original occurrence rather than a global first match',()=>{
  const body='其一也。其二也。',units=sentenceUnits(body);
  const result=normalizeResult({units:[{index:0,translation:'这是第一项。',notes:[{quote:'其',occurrence:0,meaning:'第一处意思'}]},
    {index:1,translation:'这是第二项。',notes:[{quote:'其',occurrence:0,meaning:'第二处意思'}]}]},units,body);
  assert.deepEqual(result.notes.map(note=>note.occurrence),[0,1]);
  assert.equal(locateOccurrence(body,'其',1),4);
  assert.throws(()=>locateOccurrence(body,'其',2),/不存在/);
});
test('missing sentence, empty translation and mismatched sentence cannot pass full coverage',()=>{
  const valid=article('学不可以已。锲而不舍。');
  const missing=structuredClone(valid);missing.translations.pop();
  assert.throws(()=>validateCoverage(missing),/译文条数/);
  const noNote=structuredClone(valid);noNote.notes.pop();
  assert.throws(()=>validateCoverage(noNote),/没有语境注释/);
  const empty=structuredClone(valid);empty.translations[0].translation='';
  assert.throws(()=>validateCoverage(empty),/空译文/);
  const mismatch=structuredClone(valid);mismatch.notes[1].sentence='学不可以已。';
  assert.throws(()=>validateCoverage(mismatch),/语境/);
});
test('unit segmentation includes closing quotation marks without dropping Han text',()=>{
  const body='子曰：“朝闻道，夕死可矣。”\n  子曰：“君子喻于义。”';
  const units=sentenceUnits(body);
  assert.equal(units.length,2);
  assert.equal(body.slice(units[0].start,units[0].end),units[0].quote);
  assert.ok(units[0].quote.endsWith('。”'));
  assert.equal(validateCoverage(article(body)).sentences,2);
});
