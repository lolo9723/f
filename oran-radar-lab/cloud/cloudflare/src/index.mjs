import {deliver} from './fcm.mjs';
const encoder = new TextEncoder();

export async function digest(value) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');
}
function equal(a,b) {
  if (a.length!==b.length) return false;
  let d=0; for(let i=0;i<a.length;i++) d|=a.charCodeAt(i)^b.charCodeAt(i); return d===0;
}
function json(value,status=200,extra={}) {
  return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...extra}});
}
function fail(status,message) { const e=new Error(message); e.status=status; throw e; }
export async function authenticate(request,env) {
  const raw=request.headers.get('Authorization')||'';
  if(!raw.startsWith('Bearer ')||raw.length>600) fail(401,'Giriş gerekli');
  const token=raw.slice(7); if(token.length<32) fail(401,'Geçersiz erişim anahtarı');
  const hash=await digest(token);
  if(env.ORAN_ADMIN_TOKEN && equal(hash,await digest(env.ORAN_ADMIN_TOKEN))) return {role:'admin',user:'owner'};
  if(env.ORAN_INGEST_TOKEN && equal(hash,await digest(env.ORAN_INGEST_TOKEN))) return {role:'ingest',user:'pipeline'};
  const row=await env.DB.prepare('SELECT user_id,expires_at FROM api_tokens WHERE token_hash=? AND revoked_at IS NULL').bind(hash).first();
  if(!row||row.expires_at<Date.now()) fail(401,'Erişim anahtarı süresi dolmuş veya geçersiz');
  return {role:'user',user:row.user_id};
}
function requireRole(auth,roles) { if(!roles.includes(auth.role)) fail(403,'Bu işlem için yetki yok'); }
async function body(request,max=512_000) {
  if(!request.headers.get('Content-Type')?.startsWith('application/json')) fail(415,'JSON gerekli');
  const reader=request.body?.getReader(); if(!reader) fail(400,'Boş istek');
  let size=0; const chunks=[];
  while(true) { const {value,done}=await reader.read(); if(done) break; size+=value.length;
    if(size>max) { await reader.cancel(); fail(413,'İstek çok büyük'); } chunks.push(value); }
  const buffer=new Uint8Array(size); let offset=0;
  for(const c of chunks) {buffer.set(c,offset);offset+=c.length;}
  try { return JSON.parse(new TextDecoder().decode(buffer)); } catch { fail(400,'Geçersiz JSON'); }
}
function int(value,min,max) { return Number.isSafeInteger(value)&&value>=min&&value<=max; }

async function snapshot(env) {
  const row=await env.DB.prepare("SELECT payload_json FROM snapshots WHERE id='current'").first();
  return row?JSON.parse(row.payload_json):{status:'not_started',generated_at_ms:null,signals:[],models:[],inventory:{},tasks:{},totals:{},limitations:['Henüz doğrulanmış veri raporu aktarılmadı.']};
}

export async function route(request,env) {
  const url=new URL(request.url), path=url.pathname;
  if(path==='/v1/health') return json({service:'Oran Radar Cloud',version:'4.0.0',real_trading:false});
  const auth=await authenticate(request,env);
  if(request.method==='GET'&&['/v1/status','/v1/coverage','/v1/signals','/v1/models','/v1/patterns','/v1/performance'].includes(path)) {
    requireRole(auth,['admin','user','ingest']); const data=await snapshot(env);
    if(path==='/v1/signals') return json({generated_at_ms:data.generated_at_ms,signals:data.signals||[]});
    if(path==='/v1/models') return json({models:data.models||[],status:data.models?.length?'available':'not_trained'});
    if(path==='/v1/patterns') return json(data.patterns||{status:'not_evaluated',events:[]});
    if(path==='/v1/performance') return json(data.performance||{status:'not_evaluated',trades:0,win_rate:null});
    return json(data);
  }
  if(path==='/v1/settings') {
    requireRole(auth,['admin','user']);
    if(request.method==='GET') {
      const r=await env.DB.prepare('SELECT settings_json,updated_at FROM user_settings WHERE user_id=?').bind(auth.user).first();
      return json(r?{settings:JSON.parse(r.settings_json),updated_at:r.updated_at}:{settings:{watchlist:[],maxHoldingHours:48,notifications:true},updated_at:null});
    }
    if(request.method==='PUT') {
      const s=await body(request,16_000);
      if(!Array.isArray(s.watchlist)||s.watchlist.length>100||s.watchlist.some(x=>typeof x!=='string'||!/^binance_tr:spot:[A-Z0-9_]{3,30}$/.test(x))||!int(s.maxHoldingHours,1,48)||typeof s.notifications!=='boolean') fail(400,'Geçersiz ayarlar');
      const cleaned={watchlist:[...new Set(s.watchlist)],maxHoldingHours:s.maxHoldingHours,notifications:s.notifications};
      await env.DB.prepare('INSERT INTO user_settings VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET settings_json=excluded.settings_json,updated_at=excluded.updated_at').bind(auth.user,JSON.stringify(cleaned),Date.now()).run();
      return json({settings:cleaned});
    }
  }
  if(path==='/v1/devices'&&request.method==='POST') {
    requireRole(auth,['user','admin']); const d=await body(request,8_000);
    if(typeof d.token!=='string'||d.token.length<20||d.token.length>4096) fail(400,'Geçersiz cihaz anahtarı');
    const id=await digest(auth.user+':'+d.token);
    await env.DB.prepare('INSERT INTO devices VALUES(?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET updated_at=excluded.updated_at').bind(id,auth.user,d.token,Date.now()).run();
    return json({device_id:id});
  }
  if(path==='/v1/admin/tokens'&&request.method==='POST') {
    requireRole(auth,['admin']); const d=await body(request,2_000);
    if(typeof d.user_id!=='string'||!/^[a-zA-Z0-9_-]{1,80}$/.test(d.user_id)) fail(400,'Geçersiz kullanıcı');
    const secret=crypto.randomUUID()+crypto.randomUUID(); const hash=await digest(secret);
    const expires=Date.now()+30*86400_000;
    await env.DB.prepare('INSERT INTO api_tokens VALUES(?,?,?,?,NULL)').bind(hash,d.user_id,Date.now(),expires).run();
    return json({token:secret,expires_at:expires,role:'user'},201);
  }
  if(path==='/v1/admin/revoke'&&request.method==='POST') {
    requireRole(auth,['admin']); const d=await body(request,2_000);
    if(typeof d.user_id!=='string') fail(400,'Kullanıcı gerekli');
    await env.DB.prepare('UPDATE api_tokens SET revoked_at=? WHERE user_id=?').bind(Date.now(),d.user_id).run();
    return json({revoked:true});
  }
  if(path==='/v1/ingest/snapshot'&&request.method==='PUT') {
    requireRole(auth,['admin','ingest']); const d=await body(request);
    if(d.schema_version!=='coverage-v1'||!int(d.generated_at_ms,0,Date.now()+60_000)||!d.totals||!Array.isArray(d.signals)||!Array.isArray(d.models)) fail(400,'Geçersiz rapor sözleşmesi');
    const old=await env.DB.prepare("SELECT generated_at FROM snapshots WHERE id='current'").first();
    if(old&&d.generated_at_ms<=old.generated_at) return json({status:'unchanged'});
    await env.DB.prepare("INSERT INTO snapshots VALUES('current',?,?) ON CONFLICT(id) DO UPDATE SET generated_at=excluded.generated_at,payload_json=excluded.payload_json WHERE excluded.generated_at>snapshots.generated_at").bind(d.generated_at_ms,JSON.stringify(d)).run();
    return json({status:'published'});
  }
  if(path==='/v1/jobs'&&request.method==='GET') {
    requireRole(auth,['admin','ingest']);
    const {results}=await env.DB.prepare("SELECT id,kind,due_at,status,updated_at FROM system_jobs WHERE status='queued' ORDER BY due_at LIMIT 100").all();
    return json({jobs:results});
  }
  if(path==='/v1/ingest/notify'&&request.method==='POST') {
    requireRole(auth,['admin','ingest']); const d=await body(request,4_000);
    if(typeof d.signal_id!=='string'||d.signal_id.length>100||typeof d.device_id!=='string'||!/^[a-f0-9]{64}$/.test(d.device_id)) fail(400,'Geçersiz bildirim');
    const current=await snapshot(env);
    const signal=(current.signals||[]).find(s=>s.signal_id===d.signal_id);
    if(!signal||signal.mode!=='paper_forward'||!signal.symbol_id.startsWith('binance_tr:spot:')||signal.expires_at<Date.now()) fail(400,'Geçerli TR ileriye dönük sanal sinyal gerekli');
    return json(await deliver(env,{deliveryId:await digest(d.signal_id+':'+d.device_id),signalId:d.signal_id,deviceId:d.device_id}));
  }
  if(path==='/v1/jobs/complete'&&request.method==='POST') {
    requireRole(auth,['admin','ingest']); const d=await body(request,8_000);
    if(!Array.isArray(d.ids)||d.ids.length>100||d.ids.some(x=>typeof x!=='string'||x.length>150)) fail(400,'Geçersiz işler');
    if(d.ids.length) await env.DB.batch(d.ids.map(id=>env.DB.prepare("UPDATE system_jobs SET status='complete',updated_at=? WHERE id=? AND status='queued'").bind(Date.now(),id)));
    return json({status:'complete'});
  }
  fail(404,'Uç nokta bulunamadı');
}

export default {
  async fetch(request,env) {
    const url=new URL(request.url);
    if(!url.pathname.startsWith('/v1/')) return env.ASSETS.fetch(request);
    const origin=request.headers.get('Origin');
    const allowed=origin&&(origin===url.origin||origin===env.CORS_ORIGIN);
    if(origin&&!allowed) return json({error:'Origin rejected'},403);
    const cors=allowed?{'Access-Control-Allow-Origin':origin,'Vary':'Origin'}:{};
    if(request.method==='OPTIONS') return new Response(null,{status:204,headers:{...cors,'Access-Control-Allow-Methods':'GET,POST,PUT,OPTIONS','Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Max-Age':'600'}});
    try {const response=await route(request,env);for(const[k,v]of Object.entries(cors)) response.headers.set(k,v);return response;}
    catch(e) {return json({error:e.status?e.message:'Sunucu işlemi başarısız',request_id:crypto.randomUUID()},e.status||500,cors);}
  },
  async scheduled(event,env) {
    // A queue heartbeat, not a claim of completed data collection or model training.
    // SQLite runner coalesces due jobs and catches up from its durable task ledger.
    const day=new Date(event.scheduledTime).toISOString().slice(0,10);
    const slot=Math.floor(event.scheduledTime/300_000);
    await env.DB.batch([
      env.DB.prepare("INSERT OR IGNORE INTO system_jobs VALUES(?, 'refresh_data',?,'queued',?,'{}')").bind('refresh:'+slot,event.scheduledTime,Date.now()),
      env.DB.prepare("INSERT OR IGNORE INTO system_jobs VALUES(?, 'daily_integrity',?,'queued',?,'{}')").bind('integrity:'+day,event.scheduledTime,Date.now())
    ]);
  }
};
