const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const moduleObject={exports:{}},values=new Map(),requests=[];
const code=ts.transpileModule(fs.readFileSync('src/lib/cloud.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,esModuleInterop:true}}).outputText;
let scenario='quota';const now=Date.now(),fixture={schema_version:'coverage-v1',generated_at_ms:now,status:'live',totals:{},inventory:{},tasks:{},intervals:[],gaps:[],models:[],live:{updated_at:now},signals:[{live_valid:true,expires_at:now+90000}]};
vm.runInNewContext(code,{module:moduleObject,exports:moduleObject.exports,URL,Date,Number,JSON,Array,Error,Object,Math,setTimeout,clearTimeout,AbortController,fetch:async(url,init)=>{requests.push({url,init});const primary=url.includes('br-gentle-glade');return {ok:!(primary&&scenario==='quota'),status:primary&&scenario==='quota'?429:200,headers:{get:()=> '120'},json:async()=>primary&&scenario==='quota'?{error:'quota'}:scenario==='stale'?{...fixture,live:{updated_at:now-100000}}:fixture};},require:n=>n==='react-native'?{Platform:{OS:'android'}}:n==='@react-native-async-storage/async-storage'?{getItem:async k=>values.get(k),setItem:async(k,v)=>values.set(k,v)}:n==='expo-secure-store'?{}:{}});
(async()=>{const c=moduleObject.exports;
 const first=await c.fetchCloudSnapshot(c.DEFAULT_CLOUD_BASE,'private-device-key');assert.equal(first.backup,true);assert.equal(first.offline,false);assert.equal(requests.length,2);assert(requests.every(r=>!r.init.headers.Authorization));
 const second=await c.fetchCloudSnapshot(c.DEFAULT_CLOUD_BASE,'private-device-key');assert.equal(requests.length,3);assert(requests.at(-1).url.startsWith(c.BACKUP_CLOUD_BASE),'Retry-After keeps primary circuit open');
 scenario='fresh';await c.cloudJSON(c.BACKUP_CLOUD_BASE,'/v1/wallet/settings','s'.repeat(40),{method:'PUT',body:JSON.stringify({revision:1})});assert(requests.at(-1).url.startsWith(c.WALLET_CLOUD_BASE));assert(JSON.parse(requests.at(-1).init.body).request_id.length>=16);
 // A stale backup is not falsely marked live; cached data retains its timestamp.
 scenario='stale';const stale=await c.fetchCloudSnapshot(c.DEFAULT_CLOUD_BASE,'');assert.equal(stale.offline,true);assert.equal(stale.fetchedAt,second.fetchedAt);
 console.log('Failover: 429 automatic backup, Retry-After, no market token leakage, single wallet authority, write idempotency key and stale-data rejection passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
