const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const values=new Map(),secrets=new Map();let fail=false,status=200,lastUrl='';
const fixture=JSON.parse(fs.readFileSync('../cloudflare-coverage.json','utf8'));
const moduleObject={exports:{}};
const code=ts.transpileModule(fs.readFileSync('src/lib/cloud.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,esModuleInterop:true}}).outputText;
vm.runInNewContext(code,{module:moduleObject,exports:moduleObject.exports,URL,Date,Number,JSON,Array,Error,Object,setTimeout,clearTimeout,AbortController,
  fetch:async(url)=>{lastUrl=url;if(fail)throw Error('offline');return {ok:status===200,status,json:async()=>url.endsWith('/v1/session')?{token:'t'.repeat(40)}:url.endsWith('/v1/live/status')?{...fixture,live:{updated_at:Date.now()},signals:[{live_valid:true,expires_at:Date.now()+90000}]}:fixture};},
  require:name=>name==='react-native'?{Platform:{OS:'android'}}:name==='@react-native-async-storage/async-storage'?{getItem:async k=>values.get(k)||null,setItem:async(k,v)=>values.set(k,v)}:name==='expo-secure-store'?{getItemAsync:async k=>secrets.get(k)||null,setItemAsync:async(k,v)=>secrets.set(k,v),deleteItemAsync:async k=>secrets.delete(k)}:{} });
const c=moduleObject.exports;
(async()=>{
 const initial=await c.cloudConfig();assert.equal(initial.base,c.DEFAULT_CLOUD_BASE);assert.equal(initial.token,'t'.repeat(40));await c.fetchCloudSnapshot(initial.base,initial.token);assert.match(lastUrl,/v1\/live\/status$/);
 assert.equal(c.normalizeCloudBase('https://api.example/'),'https://api.example');
 for(const url of ['http://api.example','https://user:pass@api.example','https://api.example/path'])assert.throws(()=>c.normalizeCloudBase(url));
 assert.throws(()=>c.validateCloudSnapshot({signals:[]}));
 const fresh=await c.fetchCloudSnapshot('','');assert.equal(fresh.data.totals.rows,217954);assert.equal(fresh.source,'github');assert.match(lastUrl,/oran-radar-cloud-4\.0/);
 fail=true;const offline=await c.fetchCloudSnapshot('','');assert.equal(offline.offline,true);assert.equal(offline.fetchedAt,fresh.fetchedAt);assert.equal(offline.error,'offline');
 fail=false;await c.saveCloudConfig('https://api.example','s'.repeat(40));assert.equal((await c.cloudConfig()).token,'s'.repeat(40));assert.equal([...values.values()].some(x=>x==='s'.repeat(40)),false);
 await c.fetchCloudSnapshot('https://api.example','s'.repeat(40));status=401;await assert.rejects(()=>c.fetchCloudSnapshot('https://api.example','wrong'),/401/);
 await c.saveCloudConfig('','');assert.equal((await c.cloudConfig()).token,'');
 console.log('Cloud 4.0: HTTPS, schema, measured GitHub report, offline timestamp, secure token and 401 checks passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
