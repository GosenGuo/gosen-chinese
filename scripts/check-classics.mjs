import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {sentenceUnits} from './complete-classic-annotations.mjs';
import {validateCharacterNotes} from './complete-character-annotations.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');

export function locateOccurrence(body,quote,occurrence=0){
  assert.ok(typeof quote==='string'&&quote.length,'注释引用不能为空');
  assert.ok(Number.isInteger(occurrence)&&occurrence>=0,'注释出现次数必须为非负整数');
  let from=0,start=-1;
  for(let n=0;n<=occurrence;n++){
    start=body.indexOf(quote,from);
    assert.ok(start>=0,'注释引用的指定出现位置不存在：'+quote+':'+occurrence);
    from=start+quote.length;
  }
  return start;
}
export function validateCoverage(item){
  const body=item.body,units=sentenceUnits(body);
  assert.ok(typeof body==='string'&&body.length>0,item.id+'原文为空');
  assert.ok(Array.isArray(item.notes)&&item.notes.length>0,item.id+'缺少注释');
  assert.ok(Array.isArray(item.translations),item.id+'缺少译文');
  assert.equal(item.translations.length,units.length,item.id+'逐句译文条数与原文句段不一致');
  const noteRanges=[];
  for(const note of item.notes){
    const start=locateOccurrence(body,note.quote,note.occurrence??0),end=start+note.quote.length;
    assert.ok(note.meaning?.trim()&&note.source?.trim(),item.id+'注释缺少释义或来源');
    assert.ok(typeof note.sentence==='string'&&note.sentence.length,item.id+'注释缺少原句');
    let from=0,sentenceStart=-1,matched=false;
    while((sentenceStart=body.indexOf(note.sentence,from))>=0){
      if(sentenceStart<=start&&end<=sentenceStart+note.sentence.length){matched=true;break;}
      from=sentenceStart+1;
    }
    assert.ok(matched,item.id+'注释语境与该次引用位置不对应：'+note.quote);
    if(note.start!==undefined)assert.equal(note.start,start,item.id+'注释start失效');
    if(note.end!==undefined)assert.equal(note.end,end,item.id+'注释end失效');
    noteRanges.push({start,end});
  }
  const translated=new Uint8Array(body.length);let previousEnd=0;
  for(const translation of item.translations){
    assert.ok(translation.translation?.trim(),item.id+'存在空译文');
    const start=translation.start===undefined?body.indexOf(translation.quote,previousEnd):translation.start;
    const end=start+translation.quote.length;
    assert.ok(Number.isInteger(start)&&start>=previousEnd,item.id+'译文必须按原文顺序且不能重叠');
    assert.equal(body.slice(start,end),translation.quote,item.id+'译文引用不在指定原文范围');
    if(translation.end!==undefined)assert.equal(translation.end,end,item.id+'译文end失效');
    translated.fill(1,start,end);previousEnd=end;
  }
  for(let index=0;index<body.length;){
    const character=String.fromCodePoint(body.codePointAt(index));
    if(/\p{Script=Han}/u.test(character))for(let part=0;part<character.length;part++)assert.equal(translated[index+part],1,item.id+'存在未翻译原文：'+body.slice(index,index+18));
    index+=character.length;
  }
  for(const unit of units)assert.ok(noteRanges.some(note=>note.start>=unit.start&&note.end<=unit.end),item.id+'该句没有语境注释：'+unit.quote);
  assert.equal(item.translationCoverage,'全文逐句译文',item.id+'未标明真实全文覆盖状态');
  assert.equal(item.annotationCoverage?.complete,true,item.id+'未通过完整覆盖标记');
  assert.equal(item.annotationCoverage?.sentenceCount,units.length,item.id+'句段计数失效');
  assert.equal(item.annotationCoverage?.translatedSentences,units.length,item.id+'译文计数不完整');
  return {notes:item.notes.length,translations:item.translations.length,sentences:units.length};
}
export function validateClassics(data,mapping){
  assert.equal(data.items.length,60,'60篇目录必须完整');
  assert.equal(new Set(data.items.map(x=>x.id)).size,60,'文章ID必须唯一');
  assert.equal(data.items.filter(x=>x.kind==='prose').length,20);
  assert.equal(data.items.filter(x=>x.kind==='poem').length,40);
  const expected=mapping.books.flatMap(book=>book.ids.map(number=>({id:'classic-'+String(number).padStart(2,'0'),book:book.name})));
  const totals={items:60,notes:0,translations:0,sentences:0,questions:0};
  for(const [index,item] of data.items.entries()){
    assert.equal(item.id,expected[index].id,'篇目未按教材册次目录排序');
    assert.equal(item.book,expected[index].book,'篇目册次映射错误');
    assert.equal(item.order,index+1,'篇目顺序编号失效');
    assert.ok(!/[�]|undefined|<div/.test(item.body),item.id+'原文乱码或HTML残留');
    assert.ok(item.sourceUrl.startsWith('https://'),item.id+'缺少原文来源');
    const counts=validateCoverage(item);
    totals.notes+=counts.notes;totals.translations+=counts.translations;totals.sentences+=counts.sentences;
    for(const question of item.questions){
      assert.ok(question.prompt&&question.answer&&question.analysis);
      assert.ok(question.evidence.every(quote=>item.body.includes(quote)),item.id+'题目证据失效');
      assert.equal(question.points.reduce((sum,point)=>sum+point.score,0),question.score);
      totals.questions++;
    }
  }
  return totals;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const data=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/classics.json'),'utf8'));
  const mapping=JSON.parse(fs.readFileSync(path.join(root,'scripts/sources/textbook-order.json'),'utf8'));
  const totals=validateClassics(data,mapping);
  totals.characters=data.items.reduce((sum,item)=>sum+validateCharacterNotes(item),0);
  console.log(JSON.stringify({result:'PASS',...totals,scope:'教材顺序、全部含汉字句段的注释和译文覆盖、精确原文位置与题目引用检查；不冒称教材原注或人工语义全量审定'},null,2));
}
