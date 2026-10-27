// Actual page logic with synthetic Firebase/transport doubles: no login or network calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/openridesync-login.html'), 'utf8')
 .match(/<script type="module">([\s\S]*?)<\/script>/)[1]
 .replace(/^import .*;\r?\n/gm, '').replace('__FIREBASE_CONFIG__', '{}');

async function scenario({token=true, accepted=true, popupError=null, doubleAttempt=false, nativeStatus='SUCCEEDED', delayedNative=false}={}) {
 const calls=[], status={textContent:''}, retry={hidden:true};
 let release;
 const popupGate = doubleAttempt ? new Promise(resolve=>{release=resolve;}) : Promise.resolve();
 let finishNative;
 const nativeGate = delayedNative ? new Promise(resolve=>{finishNative=resolve;}) : Promise.resolve();
 class GoogleAuthProvider {
  setCustomParameters(value) { assert.equal(value.prompt,'select_account'); }
  static credentialFromResult(value) { return value.credential; }
 }
 const context={document:{querySelector:s=>s==='#status'?status:retry},location:{pathname:'/session/nonce/'},
  initializeApp:()=>({}),getAuth:()=>({}),inMemoryPersistence:'memory',
  setPersistence:async(_,value)=>assert.equal(value,'memory'),GoogleAuthProvider,
  signInWithPopup:async()=>{calls.push('popup');await popupGate;if(popupError)throw popupError;return {credential:token?{idToken:'synthetic-id-token'}:{}};},
  signOut:async()=>calls.push('signout'),fetch:async(url,options)=>{
   assert.equal(url,'credential');assert.equal(options.method,'POST');assert.equal(options.cache,'no-store');assert.equal(options.credentials,'omit');
   assert.equal(options.headers['X-Local-Nonce'],'nonce');
   assert.deepEqual(JSON.parse(options.body),{googleIdToken:'synthetic-id-token'});
   calls.push('handoff');return {ok:accepted,json:async()=>{await nativeGate;return {status:nativeStatus};}};
  }};
 const running=vm.runInNewContext('(async()=>{'+source+'})()',context);
 if(doubleAttempt) {
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(calls,['popup']);
  await retry.onclick(); // A second click while popup is pending cannot launch another attempt.
  assert.deepEqual(calls,['popup']);
  release();
 }
 if(delayedNative) {
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(calls,['popup','handoff']);
  assert.doesNotMatch(status.textContent,/登入完成|驗證失敗/); // HTTP receipt alone is not native completion.
  finishNative();
 }
 await running;
 if(accepted&&token&&!popupError&&['SUCCEEDED','FAILED','CANCELLED','TIMEOUT'].includes(nativeStatus)) {
  await retry.onclick(); // A successful handoff cannot replay the credential.
  assert.deepEqual(calls,['popup','handoff','signout']);
 }
 return {calls,status:status.textContent,retry};
}
(async()=>{
 const success=await scenario();assert.deepEqual(success.calls,['popup','handoff','signout']);assert.equal(success.retry.hidden,true);assert.match(success.status,/Firebase/);
 const nativeFailure=await scenario({nativeStatus:'FAILED',delayedNative:true});assert.match(nativeFailure.status,/原生 Firebase 驗證失敗/);assert.doesNotMatch(nativeFailure.status,/登入完成/);assert.equal(nativeFailure.retry.hidden,true);
 const nativeTimeout=await scenario({nativeStatus:'TIMEOUT'});assert.match(nativeTimeout.status,/逾時/);assert.equal(nativeTimeout.retry.hidden,true);
 const nativeCancel=await scenario({nativeStatus:'CANCELLED'});assert.match(nativeCancel.status,/取消/);assert.equal(nativeCancel.retry.hidden,true);
 const malformed=await scenario({nativeStatus:'unexpected-private-error'});assert.equal(malformed.retry.hidden,false);assert.doesNotMatch(malformed.status,/unexpected-private-error/);
 const blocked=await scenario({popupError:{code:'auth/popup-blocked',message:'secret-sdk-error'}});assert.deepEqual(blocked.calls,['popup','signout']);assert.match(blocked.status,/允許/);assert.equal(blocked.retry.hidden,false);assert.doesNotMatch(blocked.status,/secret-sdk-error/);
 const failed=await scenario({popupError:{code:'auth/internal-error',message:'private-sdk-payload'}});assert.deepEqual(failed.calls,['popup','signout']);assert.equal(failed.retry.hidden,false);assert.doesNotMatch(failed.status,/private-sdk-payload|internal-error/);
 const missing=await scenario({token:false});assert.deepEqual(missing.calls,['popup','signout']);assert.equal(missing.retry.hidden,false);
 const rejected=await scenario({accepted:false});assert.deepEqual(rejected.calls,['popup','handoff','signout']);assert.equal(rejected.retry.hidden,false);
 const cancelled=await scenario({popupError:{code:'auth/popup-closed-by-user'}});assert.match(cancelled.status,/取消/);assert.equal(cancelled.retry.hidden,false);
 await scenario({doubleAttempt:true});
 console.log('PASS synthetic popup, terminal native success/failure/cancel/timeout, no transport-only success, sanitized errors, concurrency and replay prevention; no authentication/network');
})().catch(error=>{console.error(error);process.exitCode=1;});
