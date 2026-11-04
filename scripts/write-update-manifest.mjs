import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

export function makeManifest({version,apkBytes,certificateSha256,pinnedCertificateSha256=null,repository='GosenGuo/gosen-chinese',previous=null}) {
  if(!Number.isInteger(version.versionCode)||version.versionCode<1) throw Error('versionCode must be a positive integer');
  if(!/^\d+\.\d+\.\d+(?:[-.][a-zA-Z0-9.]+)?$/.test(version.versionName)) throw Error('versionName must be a safe release version');
  if(!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw Error('Invalid GitHub repository');
  if(!apkBytes?.length) throw Error('APK is empty');
  const certificate=String(certificateSha256||'').replace(/:/g,'').trim().toLowerCase();
  if(!/^[0-9a-f]{64}$/.test(certificate)) throw Error('A signing certificate SHA-256 digest is required');
  if(pinnedCertificateSha256!==null){
    const pinned=String(pinnedCertificateSha256).replace(/:/g,'').trim().toLowerCase();
    if(!/^[0-9a-f]{64}$/.test(pinned)) throw Error('The release signing certificate pin is invalid');
    if(certificate!==pinned) throw Error('APK signing certificate does not match the fixed release certificate pin');
  }
  const sha256=crypto.createHash('sha256').update(apkBytes).digest('hex');
  if(previous){
    if(previous.packageName!=='com.gosen.chinese') throw Error('Previous manifest package mismatch');
    if(previous.certificateSha256?.toLowerCase()!==certificate) throw Error('Signing certificate changed');
    if(previous.versionCode>version.versionCode) throw Error('Cannot publish a versionCode downgrade');
    if(previous.versionCode===version.versionCode&&(previous.sha256!==sha256||previous.versionName!==version.versionName)) throw Error('Bump versionCode and versionName for a changed APK');
    if(previous.versionCode<version.versionCode&&previous.versionName===version.versionName) throw Error('Bump versionName for a new release');
  }
  const asset=`GosenChinese-${version.versionName}.apk`;
  return {versionCode:version.versionCode,versionName:version.versionName,
    apkUrl:`https://github.com/${repository}/releases/download/app-v${version.versionName}/${asset}`,
    sha256,packageName:'com.gosen.chinese',certificateSha256:certificate,
    releaseNotes:String(version.releaseNotes||'改进学习体验并修复问题。')};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const args=process.argv.slice(2);const get=(name,fallback)=>{const i=args.indexOf(name);return i<0?fallback:args[i+1];};
  const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
  const output=path.resolve(get('--output',path.join(root,'release/app-update.json')));
  const apk=get('--apk'); if(!apk)throw Error('Pass --apk path to the signed release APK');
  const version=JSON.parse(fs.readFileSync(get('--version',path.join(root,'release/version.json')),'utf8'));
  const previous=fs.existsSync(output)?JSON.parse(fs.readFileSync(output,'utf8')):null;
  const pinFile=get('--certificate-pin-file',path.join(root,'release/signing-certificate.sha256'));
  const pinnedCertificateSha256=fs.readFileSync(pinFile,'utf8');
  const manifest=makeManifest({version,apkBytes:fs.readFileSync(apk),certificateSha256:get('--certificate-sha256',process.env.ANDROID_CERTIFICATE_SHA256),pinnedCertificateSha256,repository:get('--repository',process.env.GITHUB_REPOSITORY||'GosenGuo/gosen-chinese'),previous});
  fs.mkdirSync(path.dirname(output),{recursive:true});
  fs.writeFileSync(output,JSON.stringify(manifest,null,2)+'\n');
  fs.writeFileSync(apk+'.sha256',manifest.sha256+'  '+path.basename(apk)+'\n');
  console.log(`Wrote update manifest for ${manifest.versionName} (${manifest.versionCode}), SHA-256 ${manifest.sha256}`);
}
