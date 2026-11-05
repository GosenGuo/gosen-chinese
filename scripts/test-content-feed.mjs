import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateFeed,validateItem} from './validate-content-feed.mjs';
import {buildFeed} from './build-content-feed.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
function bundle(){
  let catalog={};const items=[];
  for(const file of ['classics','practice','essays']){
    const data=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',file+'.json'),'utf8'));
    if(data.catalog)catalog=data.catalog;
    for(const item of data.items){if(item.module==='modern')item.notes=[];items.push(item);}
  }
  return {schemaVersion:1,contentVersion:1,bundleVersionCode:3,generatedAt:'2026-11-05T00:00:00Z',catalog,items};
}
test('a complete four-module feed retains the 60-entry classic catalog',()=>{
  const data=validateFeed(bundle());assert.equal(data.items.filter(item=>item.module==='classics').length,60);
});
test('bad evidence, repeated identifiers and missing modules are rejected',()=>{
  let data=bundle();data.items.push(structuredClone(data.items[0]));assert.throws(()=>validateFeed(data),/duplicate/);
  data=bundle();const reading=data.items.find(item=>item.module==='modern');reading.questions[0].evidence=['不存在的证据'];assert.throws(()=>validateFeed(data),/evidence/);
  data=bundle();data.items=data.items.filter(item=>item.module!=='essay');assert.throws(()=>validateFeed(data),/missing module/);
});
test('sentence context and full classical translations are strict, modern annotations absent',()=>{
  const data=bundle();const classical=structuredClone(data.items.find(item=>item.module==='classical'));
  classical.translations.pop();assert.throws(()=>validateItem(classical),/cover/);
  const modern=structuredClone(data.items.find(item=>item.module==='modern'));modern.notes=[{quote:modern.body.slice(0,1),occurrence:0,meaning:'测试',sentence:modern.body}];assert.throws(()=>validateItem(modern),/no clickable/);
});
test('feed builds preserve remotely added daily IDs while replacing bundled annotations',async()=>{
  const tempRoot=path.join(root,'generated','feed-test-'+Date.now()+'-'+Math.random().toString(16).slice(2));
  await fsp.mkdir(path.join(tempRoot,'app/src/main/assets'),{recursive:true});await fsp.mkdir(path.join(tempRoot,'release'),{recursive:true});
  for(const name of ['classics','practice','essays'])await fsp.copyFile(path.join(root,'app/src/main/assets',name+'.json'),path.join(tempRoot,'app/src/main/assets',name+'.json'));
  await fsp.writeFile(path.join(tempRoot,'release/version.json'),JSON.stringify({versionCode:4,bundledContentVersionCode:3}));
  try{
    const first=buildFeed(tempRoot,new Date('2026-11-05T00:00:00Z'));const addition=structuredClone(first.items.find(item=>item.module==='modern'));addition.id='daily-modern-test';first.items.push(addition);
    await fsp.writeFile(path.join(tempRoot,'docs/library.json'),JSON.stringify(first));
    const second=buildFeed(tempRoot,new Date('2026-11-06T00:00:00Z'));
    assert(second.items.some(item=>item.id==='daily-modern-test'));assert.equal(second.contentVersion,2);assert.equal(second.bundleVersionCode,3);
  }finally{assert(tempRoot.startsWith(path.join(root,'generated')+path.sep));await fsp.rm(tempRoot,{recursive:true,force:true});}
});
