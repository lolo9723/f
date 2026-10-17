import panelAssets from 'oran-panel-assets';
import {randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {parseTriggerDelivery} from '@neon/functions/triggers';
import worker,{authenticate,digest} from '../../cloudflare/src/index.mjs';
import {DB,pool,state,transaction,walletTransaction} from './db.mjs';
import {refresh,getJSON} from './engine.mjs';
import {getLiveMarket,liveSignals} from './live.mjs';
import {trackWallets} from './tracking.mjs';
import {freshWallet,settings,viewWallet,cancel,settle,reserve,manualOrder} from './wallet.mjs';
const env={...process.env,DB};
let publicCache={at:0,value:null};
const json=(x,status=200,extra={})=>Response.json(x,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...extra}});
function admin(request){const p=request.headers.get('Authorization')||'',e='Bearer '+env.ORAN_ADMIN_TOKEN;return p.length===e.length&&timingSafeEqual(Buffer.from(p),Buffer.from(e));}
async function body(request){if(!request.headers.get('content-type')?.startsWith('application/json'))throw Object.assign(Error('JSON gerekli'),{status:415});const reader=request.body?.getReader();if(!reader)throw Error('Boş istek');let size=0;const chunks=[];while(true){const r=await reader.read();if(r.done)break;size+=r.value.length;if(size>16000){await reader.cancel();throw Object.assign(Error('İstek büyük'),{status:413});}chunks.push(r.value);}return JSON.parse(Buffer.concat(chunks).toString());}
async function snapshot(){if(publicCache.value&&Date.now()-publicCache.at<30000)return publicCache.value;const r=(await pool.query("SELECT payload_json FROM snapshots WHERE id='current'")).rows[0];publicCache={at:Date.now(),value:r?JSON.parse(r.payload_json):{status:'not_started',generated_at_ms:null}};return publicCache.value;}
async function liveSnapshot(update=false){
 const deep=await snapshot();let fast;
 try{fast=update?await getLiveMarket():await state('fast_market');}catch(e){fast=await state('fast_market');if(fast)fast={...fast,error:e.message,candidates:fast.candidates.map(c=>({...c,live_valid:false,live_error:e.message}))};}
 if(!fast)return {...deep,signals:[],live:{...deep.live,updated_at:0,score_interval_seconds:10,background_interval_seconds:60,score_error:'İlk canlı puan hazırlanıyor'}};
 return {...deep,signals:liveSignals(fast),live:{...deep.live,updated_at:fast.updated_at,deep_scan_at:fast.deep_scan_at,score_interval_seconds:10,background_interval_seconds:60,scanned:fast.candidates.filter(c=>c.live_valid).length,failed:fast.errors?.length||0,errors:fast.errors,score_error:fast.error||null}};
}
async function pulse(){let m;try{m=await getLiveMarket();}catch(e){const prior=await state('fast_market')||await state('market')||{candidates:[],quotes:{},updated_at:0};m={...prior,candidates:prior.candidates.map(c=>({...c,live_valid:false,live_error:e.message})),errors:[{pair:'SYSTEM',error:e.message}]};}return {status:'complete',scored:m.candidates.filter(c=>c.live_valid).length,score_at:m.updated_at,wallets:await trackWallets(m)};}
async function walletAPI(request,path,auth){
 if(!['user','admin'].includes(auth.role))return json({error:'Yetki gerekli'},403);
 const user=auth.user,market=(path==='/v1/wallet/buy'?await getLiveMarket():await state('fast_market'))||await state('market')||{},quotes=market.quotes||{};
 if(request.method==='GET'){
  const row=(await pool.query('SELECT document FROM wallets WHERE user_id=$1',[user])).rows[0];
  return json(row?{initialized:true,...viewWallet(row.document,quotes)}:{initialized:false,settings:settings({}),orders:[]});
 }
 const d=await body(request);
 if(path==='/v1/wallet/init'&&request.method==='POST'){
  const w=freshWallet(d.initial_try);
  const r=await pool.query('INSERT INTO wallets VALUES($1,0,$2,$3) ON CONFLICT DO NOTHING RETURNING user_id',[user,JSON.stringify(w),Date.now()]);
  if(!r.rows.length)return json({error:'İlk kasa bakiyesi daha önce oluşturuldu'},409);
  return json({initialized:true,...viewWallet(w,quotes)},201);
 }
 if(!Number.isSafeInteger(d.revision))return json({error:'Güncel kasa sürümü gerekli'},400);
 if(['/v1/wallet/buy','/v1/wallet/close','/v1/wallet/manual-order'].includes(path)){
  let pair=d.pair;
  if(path.endsWith('/close')){const prior=(await pool.query('SELECT document FROM wallets WHERE user_id=$1',[user])).rows[0]?.document;pair=prior?.orders.find(o=>o.id===d.id)?.pair;}
  if(typeof pair!=='string'||!Object.hasOwn(quotes,pair))throw Error('Güncel TR paritesi gerekli');
  // Network work finishes before the wallet transaction; manual fills never reuse a 15-minute-old quote.
  const tick=await getJSON('https://api.binance.me/api/v1/ticker/24hr?symbol='+encodeURIComponent(pair));
  const price=Number(tick.lastPrice);if(tick.symbol!==pair||!Number.isFinite(price)||price<=0)throw Error('Güncel piyasa fiyatı doğrulanamadı');
  quotes[pair]={...(quotes[pair]||{}),price,observed_at:Date.now()};
 }

 const w=await walletTransaction(user,d.revision,w=>{
  if(path==='/v1/wallet/settings'&&request.method==='PUT')w.settings=settings(d.settings,w.settings);
  else if(path==='/v1/wallet/cancel'&&request.method==='POST')cancel(w,d.id);
  else if(path==='/v1/wallet/close'&&request.method==='POST'){
   const o=w.orders.find(x=>x.id===d.id),q=o&&quotes[o.pair];
   if(!o||o.state!=='TRACKING'||!q||Date.now()-q.observed_at>20*60000)throw Error('Güncel fiyat ve aktif pozisyon gerekli');
   settle(w,o,q.price*.999,Date.now(),'MANUALLY_CLOSED');
  }else if(path==='/v1/wallet/manual-order'&&request.method==='POST'){
   if(Date.now()-(market.updated_at||0)>20*60000)throw Error('Güncel TR envanteri gerekli');
   manualOrder(w,d,quotes);
  }else if(path==='/v1/wallet/buy'&&request.method==='POST'){
   if(Date.now()-(market.updated_at||0)>20*60000)throw Error('Güncel bulut taraması gerekli');
   if(typeof d.pair!=='string'||!market.candidates?.some(c=>c.pair===d.pair))throw Error('Güncel TR adayı gerekli');
   const before=w.orders.length;reserve(w,market.candidates,quotes,Date.now(),d.pair);if(w.orders.length===before)throw Error(w.decisions.at(-1)?.reason||'Aday puan eşiğini geçmedi');
  }else throw Object.assign(Error('Uç nokta bulunamadı'),{status:404});
 });
 return json({initialized:true,...viewWallet(w,quotes)});
}
export default {
 async fetch(request){
  const url=new URL(request.url),path=url.pathname,origin=request.headers.get('origin');
  // The market snapshot is intentionally public; wallet/settings writes require a per-device key.
  const allowed=origin&&(origin===url.origin||origin===env.CORS_ORIGIN);
  const cors=allowed?{'Access-Control-Allow-Origin':origin,Vary:'Origin'}:{};
  if(origin&&!allowed)return json({error:'Origin rejected'},403);
  if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{...cors,'Access-Control-Allow-Methods':'GET,POST,PUT,OPTIONS','Access-Control-Allow-Headers':'Authorization,Content-Type'}});
  try{
   let response;
   if(['/internal/cron','/internal/pulse'].includes(path)&&request.method==='POST'){
    const parsed=await parseTriggerDelivery(request);if(!parsed.ok)return json({error:parsed.error},401);
    if(parsed.invocation.type!=='schedule')return json({error:'schedule gerekli'},400);
    response=json(path==='/internal/pulse'?await pulse():await refresh(parsed.invocation.invocationId,Date.parse(parsed.invocation.data.scheduledAt)));publicCache.at=0;
   }else if(path==='/v1/admin/pulse'&&request.method==='POST'){
    if(!admin(request))return json({error:'Unauthorized'},401);response=json(await pulse());
   }else if(path==='/v1/admin/refresh'&&request.method==='POST'){
    if(!admin(request))return json({error:'Unauthorized'},401);
    response=json(await refresh());publicCache.at=0;
   }else if(path==='/v1/health')response=json({service:'Oran Radar Cloud 4.0',modes:['MANUAL','OPTIMAL'],version:'4.0.2',deployed:true,real_trading:false,scheduler:'Neon Function Trigger',cadence_minutes:15,score_interval_seconds:10,background_interval_seconds:60});
   else if(path==='/v1/public/status'&&request.method==='GET')response=json(await liveSnapshot());
   else if(path==='/v1/live/status'&&request.method==='GET')response=json(await liveSnapshot(true));
   else if(path==='/v1/session'&&request.method==='POST'){
    // Capacity is global and checked under a transaction lock. Existing devices never re-register.
    const token=randomBytes(40).toString('base64url'),hash=await digest(token),now=Date.now();
    await transaction(async c=>{await c.query('SELECT pg_advisory_xact_lock(740001)');const count=(await c.query('SELECT count(*)::int n FROM api_tokens WHERE revoked_at IS NULL')).rows[0].n;if(count>=100)throw Object.assign(Error('Cihaz kapasitesi doldu'),{status:429});await c.query('INSERT INTO api_tokens VALUES($1,$2,$3,$4,NULL)',[hash,'device_'+randomUUID(),now,now+365*86400000]);});
    response=json({token,expires_at:now+365*86400000},201);
   }else if(path.startsWith('/v1/wallet'))response=await walletAPI(request,path,await authenticate(request,env));
   else if(path==='/v1/market'&&request.method==='GET'){
    await authenticate(request,env);const m=await getLiveMarket();response=json(m);
   }else if(path==='/v1/runs'&&request.method==='GET'){
    await authenticate(request,env);const rows=(await pool.query('SELECT id,started_at::text,finished_at::text,status,details FROM live_runs ORDER BY started_at DESC LIMIT 15')).rows;response=json({runs:rows});
   }else if(path.startsWith('/v1/'))response=await worker.fetch(request,env);
   else if(panelAssets[path])response=new Response(panelAssets[path].content,{headers:{'Content-Type':panelAssets[path].type,'Cache-Control':'public,max-age=60','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
   else response=json({error:'Uç nokta bulunamadı'},404);
   for(const[k,v]of Object.entries(cors))response.headers.set(k,v);return response;
  }catch(e){console.error('Request failed',path,e.message);return json({error:e.status?e.message:'İşlem tamamlanamadı: '+e.message},e.status||400,cors);}
 }
};
