import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {pageManifest} from './prepare-pages.mjs';
const apk=Buffer.from('signed package fixture'),pin='a'.repeat(64);
const manifest={versionCode:2,versionName:'0.1.1',packageName:'com.gosen.chinese',certificateSha256:pin,
  sha256:crypto.createHash('sha256').update(apk).digest('hex'),apkUrl:'https://github.com/GosenGuo/gosen-chinese/releases/download/app-v0.1.1/GosenChinese-0.1.1.apk'};
test('Pages serves the exact verified APK with the compatible update manifest',()=>{
  const result=pageManifest(manifest,apk,pin+'\n');
  assert.equal(result.apkUrl,'https://gosenguo.github.io/gosen-chinese/GosenChinese-0.1.1.apk');
  assert.equal(result.releaseApkUrl,manifest.apkUrl);assert.equal(result.sha256,manifest.sha256);assert.equal(result.certificateSha256,pin);
});
test('publication refuses a different APK or signing identity',()=>{
  assert.throws(()=>pageManifest(manifest,Buffer.from('different bytes'),pin),/hash/);
  assert.throws(()=>pageManifest(manifest,apk,'b'.repeat(64)),/certificate/);
  assert.throws(()=>pageManifest({...manifest,versionName:'../bad'},apk,pin),/version/);
});
