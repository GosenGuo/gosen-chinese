import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {reserveDay,generateDay,studyDay,MAX_OUTPUT_TOKENS} from './generate-daily-content.mjs';
import {buildFeed} from './build-content-feed.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
async function fixture(){
  const dir=path.join(root,'generated','daily-test-'+Date.now()+'-'+Math.random().toString(16).slice(2));
  await fs.mkdir(path.join(dir,'app/src/main/assets'),{recursive:true});await fs.mkdir(path.join(dir,'release'),{recursive:true});await fs.mkdir(path.join(dir,'scripts/sources'),{recursive:true});
  for(const name of ['classics','practice','essays'])await fs.copyFile(path.join(root,'app/src/main/assets',name+'.json'),path.join(dir,'app/src/main/assets',name+'.json'));
  await fs.writeFile(path.join(dir,'release/version.json'),JSON.stringify({versionCode:3}));buildFeed(dir,new Date('2026-11-05T00:00:00Z'));
  const source=JSON.parse(await fs.readFile(path.join(root,'scripts/sources/classical-daily-seeds.json'),'utf8'));const seed=source.items[0];
  await fs.writeFile(path.join(dir,'scripts/sources/classical-daily-seeds.json'),JSON.stringify({items:[seed]}));
  return {dir,seed,async cleanup(){assert(dir.startsWith(path.join(root,'generated')+path.sep));await fs.rm(dir,{recursive:true,force:true});}};
}
function questions(body,types){
  return types.map((type,index)=>({
    type,prompt:'第'+index+'题：理解正文的信息与表达',answer:type==='single'?'正确选项':'结合正文准确说明。',
    score:2,points:[{text:'准确理解并结合原文。',score:2}],analysis:'先依据原文，再概括题干所问内容。',focus:'理解与概括',evidence:[body.slice(0,8)],
    ...(type==='single'?{options:['干扰选项','正确选项','另外选项','错误选项'],answerIndex:1,optionAnalyses:['与原文不符','有原文依据','推理不成立','范围不准确']}:{})
  }));
}
function fakeClient(seed,{failAt=0,invalidEssay=false,invalidJson=false}={}){
  let calls=0;const budgets=[];
  const modernBody='一个学生在路口看见有人整理散落的图书，他停下脚步，和大家一起把书送回安静的阅览室。'.repeat(12);
  const essayBody='学习不仅需要获得答案，还需要理解问题。认真观察日常的细节，用自己的行动验证判断，才能把道理转化为稳定的能力。'.repeat(18);
  const answers=[
    {title:'阅览室的书',kind:'小说',body:modernBody,questions:questions(modernBody,['single','short','short','short'])},
    {notes:[{quote:seed.body.slice(0,2),occurrence:0,meaning:'词语在当前语境的含义。',sentence:seed.body,detail:'结合上下文理解。',translation:'这段古文的完整参考译文。'}],
      translations:[{quote:seed.body,translation:'这段古文的完整参考译文。'}],questions:questions(seed.body,['short','translation','segmentation','short'])},
    {title:'在行动中理解',prompt:'阅读下列材料，根据要求写作。有同学认为获得答案就是学习完成，也有人认为还需要在实际生活中检验自己的判断。这引发了你怎样的联想和思考？请结合学习或生活写一篇文章。',
      requirements:'自拟标题，写一篇议论文，不少于800字。',body:invalidEssay?'过短':essayBody,
      outline:[{title:'审题',text:'理解材料中的关系。'},{title:'中心论点',text:'在行动中理解。'},{title:'结构推进',text:'从观察到实践逐层说明。'},{title:'迁移写法',text:'用生活材料展开论证。'}],exercise:'用自己的材料写一个论证段。'}
  ];
  return {get calls(){return calls;},budgets,async generate(request){calls++;budgets.push(request.maxTokens);if(calls===failAt)throw Error('upstream temporary error');
    return {text:invalidJson?'{broken':JSON.stringify(answers[calls-1]),model:'mock-model',generatedAt:'2026-11-05T00:00:00Z',responseId:'mock-'+calls,tokenUsage:{inputTokens:10,outputTokens:20,totalTokens:30}};}};
}
test('study dates use Asia/Shanghai across the UTC midnight boundary',()=>{
  assert.equal(studyDay(new Date('2026-11-04T16:01:00Z')),'2026-11-05');
  assert.equal(studyDay(new Date('2026-11-04T15:59:59Z')),'2026-11-04');
});
test('a study day has only one reservation, including reruns with a new run identity',async()=>{
  const f=await fixture();try{
    const one=await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1'});assert.equal(one.eligible,true);
    const second=await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-2'});assert.equal(second.eligible,false);
  }finally{await f.cleanup();}
});
test('three validated items publish together with three bounded calls and useful provenance',async()=>{
  const f=await fixture();try{
    await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1'});const api=fakeClient(f.seed);
    const result=await generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1',client:api});
    assert.equal(api.calls,3);assert.deepEqual(api.budgets,[MAX_OUTPUT_TOKENS,MAX_OUTPUT_TOKENS,MAX_OUTPUT_TOKENS]);assert.equal(result.contentVersion,2);
    const feed=JSON.parse(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'));
    for(const module of ['modern','classical','essay'])assert(feed.items.some(item=>item.id==='daily-'+module+'-20261105'));
    const classical=feed.items.find(item=>item.id==='daily-classical-20261105');assert.equal(classical.body,f.seed.body);assert.equal(classical.sourceUrl,f.seed.sourceUrl);
    assert.equal(feed.items.find(item=>item.id==='daily-modern-20261105').notes.length,0);
    const ledger=JSON.parse(await fs.readFile(path.join(f.dir,'docs/daily-generation-ledger.json'),'utf8'));assert.equal(ledger.days['2026-11-05'].status,'published');
    assert.equal((await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'again'})).eligible,false);
  }finally{await f.cleanup();}
});
test('the first provider failure keeps the old feed and consumes the daily reservation',async()=>{
  const f=await fixture();try{
    await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1'});const before=await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8');const api=fakeClient(f.seed,{failAt:1});
    await assert.rejects(generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1',client:api}),/temporary/);
    assert.equal(api.calls,1);assert.equal(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'),before);
    await assert.rejects(generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1',client:api}),/unused/);assert.equal(api.calls,1);
    assert.equal((await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-2'})).eligible,false);
  }finally{await f.cleanup();}
});
test('an invalid last item cannot publish the first two successful items',async()=>{
  const f=await fixture();try{
    await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1'});const before=await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8');const api=fakeClient(f.seed,{invalidEssay:true});
    await assert.rejects(generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'run-1',client:api}),/body|length/);
    assert.equal(api.calls,3);assert.equal(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'),before);
  }finally{await f.cleanup();}
});
test('a wrong run identity cannot spend a reservation made by another workflow',async()=>{
  const f=await fixture();try{
    await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'owner'});const api=fakeClient(f.seed);
    await assert.rejects(generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'other',client:api}),/matching/);assert.equal(api.calls,0);
  }finally{await f.cleanup();}
});
test('invalid model JSON still records received token usage without publishing or retrying',async()=>{
  const f=await fixture();try{
    await reserveDay({projectRoot:f.dir,day:'2026-11-05',runId:'owner'});const api=fakeClient(f.seed,{invalidJson:true});
    await assert.rejects(generateDay({projectRoot:f.dir,day:'2026-11-05',runId:'owner',client:api}),/JSON/);assert.equal(api.calls,1);
    const ledger=JSON.parse(await fs.readFile(path.join(f.dir,'docs/daily-generation-ledger.json'),'utf8'));
    assert.equal(ledger.days['2026-11-05'].status,'failed');assert.equal(ledger.days['2026-11-05'].modules.modern.metadata.tokenUsage.totalTokens,30);
  }finally{await f.cleanup();}
});
