import test from 'node:test';
import assert from 'node:assert/strict';
import worker,{authenticate,route} from '../src/index.mjs';
import {CloudClient} from '../../android/cloud-client.mjs';
const admin='a'.repeat(40),ingest='i'.repeat(40);
function req(path,token,method='GET',body){return new Request('https://oran.example'+path,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});}
function db(){return {prepare(sql){return {bind(){return this;},async first(){return null;},async run(){return {meta:{changes:1}};},async all(){return {results:[]};}};},async batch(){return [];}};}
const env=()=>({ORAN_ADMIN_TOKEN:admin,ORAN_INGEST_TOKEN:ingest,DB:db()});
test('anonymous status denied',async()=>{const r=await worker.fetch(req('/v1/status',''),env());assert.equal(r.status,401);});
test('health discloses no private state',async()=>{const r=await worker.fetch(new Request('https://oran.example/v1/health'),env());assert.equal((await r.json()).real_trading,false);});
test('ingest token cannot edit user settings',async()=>{const r=await worker.fetch(req('/v1/settings',ingest,'PUT',{}),env());assert.equal(r.status,403);});
test('malformed settings rejected',async()=>{const r=await worker.fetch(req('/v1/settings',admin,'PUT',{watchlist:['binance_global:spot:BTCUSDT'],maxHoldingHours:48,notifications:true}),env());assert.equal(r.status,400);});
test('unconfigured report has no invented signals',async()=>{const r=await route(req('/v1/status',admin),env());const d=await r.json();assert.equal(d.status,'not_started');assert.deepEqual(d.signals,[]);});
test('untrusted Origin rejected',async()=>{const r=req('/v1/status',admin);r.headers.set('Origin','https://attacker.example');assert.equal((await worker.fetch(r,env())).status,403);});
test('oversized request rejected',async()=>{const r=await worker.fetch(req('/v1/settings',admin,'PUT',{value:'a'.repeat(17000)}),env());assert.equal(r.status,413);});
test('invalid snapshot cannot become current',async()=>{const r=await worker.fetch(req('/v1/ingest/snapshot',ingest,'PUT',{signals:[{win_rate:1}]}),env());assert.equal(r.status,400);});
test('user token provisioning restricted',async()=>{assert.equal((await worker.fetch(req('/v1/admin/tokens',ingest,'POST',{user_id:'emrah'}),env())).status,403);});
test('client never accepts cleartext server',()=>{assert.throws(()=>new CloudClient({baseUrl:'http://example.com'}),/HTTPS/);});
test('offline cache preserves timestamp and reports error',async()=>{let cached=null,offline=false;const c=new CloudClient({baseUrl:'https://oran.example',getToken:async()=>admin,now:()=>1000,cache:{setItem:async(k,v)=>{cached=v;},getItem:async()=>cached},fetchImpl:async()=>{if(offline)throw new Error('offline');return new Response(JSON.stringify({schema_version:'coverage-v1',generated_at_ms:900,signals:[]}));}});const network=await c.getSnapshot();offline=true;const local=await c.getSnapshot();assert.equal(network.source,'network');assert.equal(local.source,'cache');assert.equal(local.syncedAt,1000);assert.equal(local.stale,true);assert.equal(local.error,'offline');});
test('authorization failure cannot masquerade as offline data',async()=>{const c=new CloudClient({baseUrl:'https://oran.example',getToken:async()=>admin,cache:{getItem:async()=>{throw new Error('must not read cache');}},fetchImpl:async()=>new Response('{}',{status:401})});await assert.rejects(()=>c.getSnapshot(),/401/);});
