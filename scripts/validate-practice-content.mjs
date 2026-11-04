import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const practice = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/practice.json'), 'utf8')).items;
const essays = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/essays.json'), 'utf8')).items;
const ids = new Set();
let questions = 0;
let annotations = 0;
for (const item of [...practice, ...essays]) {
  assert(!ids.has(item.id), `duplicate id ${item.id}`);
  ids.add(item.id);
  assert(item.body && item.title && item.sourceLabel && item.verification, `missing content identity ${item.id}`);
  for (const n of item.notes || []) {
    let offset = -1;
    for (let occurrence = 0; occurrence <= n.occurrence; occurrence++) offset = item.body.indexOf(n.quote, offset + 1);
    assert(offset >= 0, `note cannot resolve ${item.id} ${n.quote}`);
    assert(item.body.includes(n.sentence), `missing note sentence ${item.id}`);
    const sentenceStart = item.body.indexOf(n.sentence);
    assert(offset >= sentenceStart && offset + n.quote.length <= sentenceStart + n.sentence.length, `wrong repeated word occurrence ${item.id} ${n.quote}`);
    annotations++;
  }
  for (const t of item.translations || []) assert(item.body.includes(t.quote) && t.translation, `bad translation ${item.id}`);
  for (const question of item.questions || []) {
    assert(!ids.has(question.id), `duplicate question id ${question.id}`);
    ids.add(question.id);
    assert(question.answer && question.analysis && question.focus, `missing answer detail ${question.id}`);
    assert(question.points.length > 0 && question.evidence.length > 0, `missing rubric/evidence ${question.id}`);
    assert.equal(question.points.reduce((sum, point) => sum + point.score, 0), question.score, `rubric score mismatch ${question.id}`);
    for (const quote of question.evidence) assert(item.body.includes(quote), `evidence mismatch ${question.id}: ${quote}`);
    if (question.type === 'single') {
      assert.equal(question.options.length, 4);
      assert(question.answerIndex >= 0 && question.answerIndex < 4, `bad answer index ${question.id}`);
      assert.equal(question.optionAnalyses.length, 4);
      assert.equal(question.options[question.answerIndex], question.answer, `answer index mismatches answer ${question.id}`);
    }
    questions++;
  }
  if (item.module === 'classical') {
    const untranslated = item.body.split(/\n+/).filter(Boolean).join('');
    assert.equal((item.translations || []).map(t => t.quote).join(''), untranslated, `incomplete or nonconsecutive translation ${item.id}`);
  }
  if (item.module === 'essay') {
    assert(item.prompt && item.requirements && item.exercise && item.outline.length >= 4, `incomplete essay ${item.id}`);
    const hanCount = [...item.body.matchAll(/\p{Script=Han}/gu)].length;
    assert(hanCount >= 800 && hanCount <= 1100, `essay length ${item.id}: ${hanCount}`);
    assert.equal(item.contentType, 'AI示范作文');
    assert(item.verification.includes('不标称满分'));
  }
}
assert.equal(practice.filter(i => i.module === 'modern').length, 3);
assert.equal(practice.filter(i => i.module === 'classical').length, 3);
assert.equal(essays.length, 7);
console.log(`Validated 3 modern passages, 3 classical passages, ${questions} questions, ${annotations} anchored annotations, 7 essays.`);
