import {test} from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {makeManifest} from './write-update-manifest.mjs';
const base={version:{versionCode:1,versionName:'0.1.0',releaseNotes:'试用版'},apkBytes:Buffer.from('signed-apk-test-fixture'),certificateSha256:'ab'.repeat(32)};
test('Manifest pins APK hash, package, signing certificate and release URL',()=>{
 const value=makeManifest(base); assert.equal(value.sha256,crypto.createHash('sha256').update(base.apkBytes).digest('hex'));
 assert.equal(value.packageName,'com.gosen.chinese');assert.equal(value.certificateSha256,base.certificateSha256);
 assert.equal(value.apkUrl,'https://github.com/GosenGuo/gosen-chinese/releases/download/app-v0.1.0/GosenChinese-0.1.0.apk');
});
test('No blank certificate, zero version, unsafe release name or empty APK',()=>{
 for(const args of [{certificateSha256:''},{apkBytes:Buffer.alloc(0)},{version:{versionCode:0,versionName:'0.1.0'}},{version:{versionCode:1,versionName:'../unsafe'}}])assert.throws(()=>makeManifest({...base,...args}));
});
test('A changed APK cannot overwrite an existing version',()=>{
 const previous=makeManifest(base);assert.deepEqual(makeManifest({...base,previous}),previous);
 assert.throws(()=>makeManifest({...base,previous,apkBytes:Buffer.from('changed')}),/Bump version/);
});
test('Signing identity remains stable across version bumps',()=>{
 const previous=makeManifest(base); const version={versionCode:2,versionName:'0.1.1'};
 assert.throws(()=>makeManifest({...base,previous,version,certificateSha256:'cd'.repeat(32)}),/certificate changed/);
 assert.equal(makeManifest({...base,previous,version}).versionCode,2);
});
test('The first release must match the fixed certificate pin even without a previous manifest',()=>{
 assert.equal(makeManifest({...base,pinnedCertificateSha256:base.certificateSha256+'\r\n'}).certificateSha256,base.certificateSha256);
 assert.throws(()=>makeManifest({...base,pinnedCertificateSha256:'cd'.repeat(32)}),/fixed release certificate pin/);
 assert.throws(()=>makeManifest({...base,pinnedCertificateSha256:''}),/pin is invalid/);
});
test('Downgrades and reused release names are rejected',()=>{
 const previous=makeManifest({...base,version:{versionCode:2,versionName:'0.1.1'}});
 assert.throws(()=>makeManifest({...base,previous}),/downgrade/);
 assert.throws(()=>makeManifest({...base,previous,version:{versionCode:3,versionName:'0.1.1'}}),/Bump versionName/);
});
