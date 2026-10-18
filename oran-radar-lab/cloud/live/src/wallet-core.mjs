import {createHash,randomBytes} from 'node:crypto';
import {pool,transaction,walletTransaction} from './db.mjs';
import {freshWallet,settings,viewWallet,cancel,settle,reserve,manualOrder} from './wallet.mjs';
import {archiveWallet} from './wallet-retention.mjs';
import {getLiveMarket} from './market-memory.mjs';
export const CORE_URL='https://br-gentle-glade-bai48xkz-radar.compute.c-8.eu-central-1.aws.neon.tech';
export const LEGACY_URL='https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech';
const json=(x,status=200)=>Response.json(x,{status,headers:{'Cache-Control':'no-store'}});
export function identity(request){const token=request.headers.get('authorization')?.replace(/^Bearer /,'');if(!token||!/^[\w-]{32,160}$/.test(token))throw Object.assign(Error('Cihaz erişim anahtarı gerekli'),{status:401});return {user:'key_'+createHash('sha256').update(token).digest('hex'),token,legacy:!token.startsWith('v403_')};}
async function body(request){const text=await request.text();if(Buffer.byteLength(text)>16000)throw Object.assign(Error('İstek büyük'),{status:413});return JSON.parse(text);}
export function session(){return json({token:'v403_'+randomBytes(40).toString('base64url'),expires_at:Date.now()+365*86400000},201);}
const migration={status:'LEGACY_QUOTA_LOCKED',message:'Önceki kasa silinmedi. Eski sunucunun veri aktarım kotası kayıtları okumayı engelliyor. Burada oluşturulan kasa ayrı bir sanal kasadır; eski bakiye aktarılmış sayılmaz.'};
export async function walletAPI(request,path){
 const auth=identity(request),user=auth.user;
 if(path==='/v1/wallet/archive'){const cursor=Number(new URL(request.url).searchParams.get('before')||Date.now());const rows=(await pool.query('SELECT document FROM wallet_archive WHERE user_id=$1 AND closed_at<$2 ORDER BY closed_at DESC,order_id LIMIT 50',[user,Number.isFinite(cursor)?cursor:Date.now()])).rows;return json({orders:rows.map(r=>r.document),next_before:rows.at(-1)?.document.closed_at||null});}
 if(path==='/v1/wallet/legacy'){
  const r=await fetch(LEGACY_URL+'/v1/legacy-wallet',{headers:{Authorization:'Bearer '+auth.token},signal:AbortSignal.timeout(15000)});return new Response(r.body,{status:r.status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
 }
 const market=await getLiveMarket(),quotes=market.quotes||{};
 const view=w=>({initialized:true,...viewWallet(w,quotes),...(auth.legacy?{migration}:{}),ledger:'wallet-core-v403',background_interval_seconds:900});
 if(request.method==='GET'){
  const row=(await pool.query('SELECT document FROM wallets WHERE user_id=$1',[user])).rows[0];
  return json(row?view(row.document):{initialized:false,settings:settings({}),orders:[],...(auth.legacy?{migration}:{}),ledger:'wallet-core-v403'});
 }
 const d=await body(request);
 // UI sends one immutable request id. Concurrent servers serialize on the same
 // Postgres row. A lost response cannot debit the paper wallet twice.
 if(typeof d.request_id!=='string'||!/^[\w-]{16,100}$/.test(d.request_id))throw Object.assign(Error('Tekil istek kimliği gerekli'),{status:400});
 if(path==='/v1/wallet/init'&&request.method==='POST'){
  const w=freshWallet(d.initial_try);w.last_request_id=d.request_id;
  const r=await pool.query('INSERT INTO wallets VALUES($1,0,$2,$3) ON CONFLICT DO NOTHING RETURNING user_id',[user,JSON.stringify(w),Date.now()]);
  if(!r.rows.length){const prior=(await pool.query('SELECT document FROM wallets WHERE user_id=$1',[user])).rows[0]?.document;if(prior?.last_request_id===d.request_id)return json(view(prior));return json({error:'İlk kasa bakiyesi daha önce oluşturuldu'},409);}
  return json(view(w),201);
 }
 if(!Number.isSafeInteger(d.revision))throw Object.assign(Error('Güncel kasa sürümü gerekli'),{status:400});
 const w=await transaction(async client=>{
  const row=(await client.query('SELECT revision,document FROM wallets WHERE user_id=$1 FOR UPDATE',[user])).rows[0];if(!row)throw Object.assign(Error('Önce sanal kasa açılmalı'),{status:404});
  const w=row.document;if(w.request_ids?.includes(d.request_id)||w.last_request_id===d.request_id)return w;
  if(row.revision!==d.revision)throw Object.assign(Error('Kasa güncellendi; yenileyip tekrar kaydet'),{status:409});
  if(path==='/v1/wallet/settings'&&request.method==='PUT'){w.settings=settings(d.settings,w.settings);reserve(w,market.candidates,quotes);}
  else if(path==='/v1/wallet/cancel'&&request.method==='POST')cancel(w,d.id);
  else if(path==='/v1/wallet/close'&&request.method==='POST'){const o=w.orders.find(x=>x.id===d.id),q=o&&quotes[o.pair];if(!o||o.state!=='TRACKING'||!q||Date.now()-q.observed_at>20000)throw Error('Güncel fiyat ve aktif pozisyon gerekli');settle(w,o,q.price*.999,Date.now(),'MANUALLY_CLOSED');}
  else if(path==='/v1/wallet/manual-order'&&request.method==='POST')manualOrder(w,d,quotes);
  else if(path==='/v1/wallet/buy'&&request.method==='POST'){const before=w.orders.length;reserve(w,market.candidates,quotes,Date.now(),d.pair);if(w.orders.length===before)throw Error(w.decisions.at(-1)?.reason||'Puan eşiği / kaynak / risk sınırı');}
  else throw Object.assign(Error('Uç nokta bulunamadı'),{status:404});
  await archiveWallet(client,user,w);
  w.last_request_id=d.request_id;w.request_ids=[...(w.request_ids||[]),d.request_id].slice(-50);w.revision=row.revision+1;
  await client.query('UPDATE wallets SET revision=$2,document=$3,updated_at=$4 WHERE user_id=$1',[user,w.revision,JSON.stringify(w),Date.now()]);return w;
 });return json(view(w));
}
