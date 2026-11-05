import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {reserveRefill,runRefill} from './refill-content.mjs';
import {buildFeed} from './build-content-feed.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
async function fixture(){
  const dir=path.join(root,'generated','refill-test-'+Date.now()+'-'+Math.random().toString(16).slice(2));
  await fs.mkdir(path.join(dir,'app/src/main/assets'),{recursive:true});await fs.mkdir(path.join(dir,'release'),{recursive:true});await fs.mkdir(path.join(dir,'scripts/sources'),{recursive:true});
  for(const name of ['classics','practice','essays'])await fs.copyFile(path.join(root,'app/src/main/assets',name+'.json'),path.join(dir,'app/src/main/assets',name+'.json'));
  await fs.writeFile(path.join(dir,'release/version.json'),JSON.stringify({versionCode:3}));buildFeed(dir,new Date('2026-11-05T00:00:00Z'));
  await fs.copyFile(path.join(root,'scripts/sources/classical-daily-seeds.json'),path.join(dir,'scripts/sources/classical-daily-seeds.json'));
  return {dir,async cleanup(){assert(dir.startsWith(path.join(root,'generated')+path.sep));await fs.rm(dir,{recursive:true,force:true});}};
}
function questions(body,types){
  return types.map((type,index)=>({type,prompt:'第'+index+'题，结合文中信息作答。',answer:type==='single'?'正确选项':'正确理解原文并说明理由。',score:2,points:[{text:'有准确的原文依据。',score:2}],
    analysis:'从题干要求出发，定位原文再概括。',focus:'语境理解',evidence:[body.slice(0,8)],...(type==='single'?{options:['错误选项一','正确选项','错误选项二','错误选项三'],answerIndex:1,optionAnalyses:['原文无依据','有原文对应依据','范围扩大','理解错误']}:{})}));
}
function fakeClient(module,{failAt=0,duplicate=false}={}){
  let calls=0,active=0,maxActive=0;const budgets=[];
  return {get calls(){return calls;},get maxActive(){return maxActive;},budgets,async generate(request){
    const index=++calls;budgets.push(request.maxTokens);active++;maxActive=Math.max(maxActive,active);
    try{
      await new Promise(resolve=>setTimeout(resolve,3));if(index===failAt)throw Error('temporary upstream failure');
      let data;
      if(module==='modern'){
        const body=('同学在小巷里看见一位老人整理书架，他们认真讨论了书籍的去向，并一起把散落的书送回了阅览室。').repeat(10)+(duplicate?'':'第'+index+'次记录。');
        data={title:'阅览室'+index,body,kind:'小说',questions:questions(body,['single','short','short','short'])};
      }else if(module==='classical'){
        const seed=JSON.parse(request.prompt.split('原文数据：')[1].split('。以前')[0]);
        data={notes:[{quote:seed.body.slice(0,2),occurrence:0,meaning:'语境中的具体含义。',sentence:seed.body,detail:'结合上下文理解。',translation:'完整参考译文。'}],
          translations:[{quote:seed.body,translation:'完整参考译文。'}],questions:questions(seed.body,['translation','segmentation','short','short'])};
      }else{
        const body=('学习需要获得知识，也需要认真观察和独立判断。把自己的理解放到实际生活中检验，能够让看似简单的道理逐渐变成稳定的能力。').repeat(18)+'这是第'+index+'次思考。';
        data={title:'在行动中理解'+index,prompt:'阅读下列材料，根据要求写作。有同学认为获得答案就是学习完成，也有人认为还需要在实际生活中检验自己的判断。这引发了你怎样的联想和思考？请结合学习或生活写一篇文章。',
          requirements:'自拟题目，写一篇议论文，不少于800字。',body,outline:[{title:'审题',text:'分析材料的关系。'},{title:'中心论点',text:'知与行互相促进。'},{title:'结构推进',text:'从观察走向实践。'},{title:'迁移写法',text:'用日常情境展开论证。'}],exercise:'写一段自己的论证。'};
      }
      return {text:JSON.stringify(data),model:'mock-model',generatedAt:'2026-11-05T00:00:00Z',responseId:'mock-'+index,tokenUsage:{inputTokens:10,outputTokens:20,totalTokens:30}};
    }finally{active--;}
  }};
}
test('invalid refill budgets and modules cannot reserve paid work',async()=>{
  const f=await fixture();try{
    await assert.rejects(reserveRefill({projectRoot:f.dir,module:'modern',targetCount:31,batchId:'one'}),/1–30/);
    await assert.rejects(reserveRefill({projectRoot:f.dir,module:'classics',targetCount:30,batchId:'one'}),/Unknown/);
  }finally{await f.cleanup();}
});
test('a bounded refill publishes each accepted checkpoint using at most two concurrent calls',async()=>{
  const f=await fixture();try{
    const options={projectRoot:f.dir,module:'modern',targetCount:5,batchId:'batch-one'};
    assert.equal((await reserveRefill(options)).eligible,true);
    const api=fakeClient('modern'),result=await runRefill({...options,client:api});
    assert.equal(result.acceptedCount,5);assert.equal(result.complete,true);assert(api.calls>=5&&api.calls<=6);assert(api.maxActive<=2);assert(api.budgets.every(value=>value===8192));
    const feed=JSON.parse(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'));assert.equal(feed.contentVersion,6);assert.equal(feed.items.filter(item=>item.id.startsWith('refill-modern-')).length,5);
    const usedCalls=api.calls;assert.equal((await reserveRefill(options)).eligible,false);await assert.rejects(runRefill({...options,client:api}),/unused/);assert.equal(api.calls,usedCalls);
  }finally{await f.cleanup();}
});
test('one rejected candidate is replaced while valid checkpoints are preserved',async()=>{
  const f=await fixture();try{
    const options={projectRoot:f.dir,module:'modern',targetCount:3,batchId:'partial'};
    await reserveRefill(options);const api=fakeClient('modern',{failAt:2}),result=await runRefill({...options,client:api});
    assert.equal(result.acceptedCount,3);assert.equal(result.rejectedCount,1);assert.equal(result.complete,true);assert(api.calls>=4&&api.calls<=5);
    const ledger=JSON.parse(await fs.readFile(path.join(f.dir,'docs/refill-ledger.json'),'utf8'));
    assert.equal(ledger.batches['modern:partial'].status,'complete');assert(ledger.batches['modern:partial'].attempts.length>=4);
  }finally{await f.cleanup();}
});
test('concurrent duplicate model passages are not counted as two new readings',async()=>{
  const f=await fixture();try{
    const options={projectRoot:f.dir,module:'modern',targetCount:3,batchId:'duplicate'};
    await reserveRefill(options);const api=fakeClient('modern',{duplicate:true}),result=await runRefill({...options,client:api});
    assert.equal(api.calls,6);assert.equal(result.acceptedCount,1);assert.equal(result.rejectedCount,5);
  }finally{await f.cleanup();}
});
test('classical candidates reserve different real original texts before parallel generation',async()=>{
  const f=await fixture();try{
    const options={projectRoot:f.dir,module:'classical',targetCount:3,batchId:'classical'};
    await reserveRefill(options);const api=fakeClient('classical'),result=await runRefill({...options,client:api});
    assert.equal(result.acceptedCount,3);const feed=JSON.parse(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'));
    const added=feed.items.filter(item=>item.id.startsWith('refill-classical-'));assert.equal(new Set(added.map(item=>item.body)).size,3);
    for(const item of added){assert(item.sourceUrl.startsWith('https://zh.wikisource.org/'));assert.equal(item.translations.map(unit=>unit.quote).join(''),item.body);}
  }finally{await f.cleanup();}
});
test('essay refills include the complete task, structural explanation and accurate AI label',async()=>{
  const f=await fixture();try{
    const options={projectRoot:f.dir,module:'essay',targetCount:2,batchId:'essay'};
    await reserveRefill(options);const result=await runRefill({...options,client:fakeClient('essay')});assert.equal(result.acceptedCount,2);
    const feed=JSON.parse(await fs.readFile(path.join(f.dir,'docs/library.json'),'utf8'));const added=feed.items.filter(item=>item.id.startsWith('refill-essay-'));
    for(const item of added){assert.equal(item.contentType,'AI示范作文');assert(item.prompt.length>=60);assert.equal(item.isFullScore,false);assert(item.outline.length>=4);}
  }finally{await f.cleanup();}
});
