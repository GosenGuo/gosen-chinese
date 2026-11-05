import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateFeed} from './validate-content-feed.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
export function buildFeed(projectRoot=root,now=new Date()){
  const output=path.join(projectRoot,'docs/library.json');
  let previous=null;try{previous=JSON.parse(fs.readFileSync(output,'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}
  const version=JSON.parse(fs.readFileSync(path.join(projectRoot,'release/version.json'),'utf8'));
  const merged=new Map((previous?.items||[]).map(item=>[item.id,item]));let catalog={};
  for(const name of ['classics','practice','essays']){
    const source=JSON.parse(fs.readFileSync(path.join(projectRoot,'app/src/main/assets',name+'.json'),'utf8'));
    if(source.catalog)catalog=source.catalog;
    for(const item of source.items){
      // Preserve IDs and body/question identity. A new APK may intentionally correct metadata/annotations.
      const cloned=JSON.parse(JSON.stringify(item));if(cloned.module==='modern')cloned.notes=[];
      merged.set(cloned.id,cloned);
    }
  }
  const feed={schemaVersion:1,contentVersion:(previous?.contentVersion||0)+1,bundleVersionCode:version.versionCode,generatedAt:now.toISOString(),catalog,items:[...merged.values()]};
  validateFeed(feed);fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(feed,null,2)+'\n');
  return feed;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){const result=buildFeed();console.log(JSON.stringify({ok:true,contentVersion:result.contentVersion,items:result.items.length}));}
