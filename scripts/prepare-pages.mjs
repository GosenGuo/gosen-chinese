import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

export function pageManifest(manifest,apkBytes,pin,base='https://gosenguo.github.io/gosen-chinese/'){
  if(manifest.packageName!=='com.gosen.chinese'||!Number.isInteger(manifest.versionCode)||manifest.versionCode<1)throw Error('Invalid published version');
  if(!/^\d+\.\d+\.\d+(?:[-.][a-zA-Z0-9.]+)?$/.test(manifest.versionName))throw Error('Invalid published version name');
  if(manifest.certificateSha256!==pin.trim())throw Error('Published certificate does not match fixed signing identity');
  if(crypto.createHash('sha256').update(apkBytes).digest('hex')!==manifest.sha256)throw Error('APK hash does not match the published update manifest');
  const filename='GosenChinese-'+manifest.versionName+'.apk';
  return {...manifest,releaseApkUrl:manifest.apkUrl,apkUrl:new URL(filename,base).href};
}
export function prepare(root,apkFile,outDir){
  const manifest=JSON.parse(fs.readFileSync(path.join(root,'release/app-update.json'),'utf8'));
  const apk=fs.readFileSync(apkFile),pin=fs.readFileSync(path.join(root,'release/signing-certificate.sha256'),'utf8');
  const published=pageManifest(manifest,apk,pin);
  fs.mkdirSync(outDir,{recursive:true});
  fs.writeFileSync(path.join(outDir,'app-update.json'),JSON.stringify(published,null,2)+'\n');
  fs.writeFileSync(path.join(outDir,path.basename(new URL(published.apkUrl).pathname)),apk);
  fs.writeFileSync(path.join(outDir,'.nojekyll'),'');
  const feed=path.join(root,'docs/library.json');
  if(fs.existsSync(feed))fs.copyFileSync(feed,path.join(outDir,'library.json'));
  const index='<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>拾文 · 安卓版</title><style>body{margin:0;background:#f7f4ed;color:#29342d;font:17px/1.8 system-ui,sans-serif}main{max-width:540px;margin:12vh auto;padding:28px}h1{font-size:44px;line-height:1.3}.button{display:block;text-align:center;padding:16px 20px;border-radius:15px;background:#315f4a;color:#fff;text-decoration:none;margin:32px 0 20px}small{color:#748174}a{color:#315f4a}</style><main><h1>拾文</h1><p>每天一篇现代文、一篇文言文，<br>再积累一点写作方法。</p><a class="button" href="'+path.basename(new URL(published.apkUrl).pathname)+'">下载安卓版 '+published.versionName+'</a><p>已安装的用户直接覆盖安装，保留学习记录。后续打开 App，会自动提示新版本。</p><small>支持 Android 8.0 及以上</small><p><a href="https://github.com/GosenGuo/gosen-chinese">查看项目</a></p></main></html>';
  fs.writeFileSync(path.join(outDir,'index.html'),index);
  return published;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const args=process.argv.slice(2),get=(key,fallback)=>{const n=args.indexOf(key);return n<0?fallback:args[n+1];};
  const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),apk=get('--apk');
  if(!apk)throw Error('Pass the downloaded release APK using --apk');
  const result=prepare(root,apk,path.resolve(get('--out','pages-dist')));
  console.log(JSON.stringify({version:result.versionName,download:result.apkUrl,sha256:result.sha256}));
}
