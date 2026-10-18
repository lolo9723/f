import panelAssets from 'oran-panel-assets';
import {parseTriggerDelivery} from '@neon/functions/triggers';
import {marketSnapshot,getLiveMarket,refreshResearch,marketMetrics} from './market-memory.mjs';
import {walletAPI,session,identity,CORE_URL} from './wallet-core.mjs';
import {trackWallets} from './tracking.mjs';
import {pool,DB} from './db.mjs';
import {authenticate} from '../../cloudflare/src/index.mjs';
import {viewWallet} from './wallet.mjs';
const core=__WALLET_CORE__;
const json=(x,status=200)=>Response.json(x,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
async function pulse(){const m=await getLiveMarket();return {status:'complete',scored:m.candidates.filter(c=>c.live_valid).length,score_at:m.updated_at,wallets:core?await trackWallets(m):{status:'separate_wallet_authority'},research:await refreshResearch(2)};}
export default {async fetch(request){
 const path=new URL(request.url).pathname,origin=request.headers.get('origin');
 // The mobile web preview and public dashboard use the same token-scoped API.
 // Wildcard CORS never exposes cookies; no cookie authentication is accepted.
 const cors={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Methods':'GET,POST,PUT,OPTIONS','Access-Control-Allow-Headers':'Authorization,Content-Type'};
 if(request.method==='OPTIONS')return new Response(null,{status:204,headers:cors});
 let response;
 try{
  if(path==='/v1/health')response=json({service:'Oran Radar Cloud 4.0',version:'4.0.3',deployed:true,real_trading:false,modes:['MANUAL','OPTIMAL'],role:core?'primary':'backup',market_storage:'DATABASE_FREE',...marketMetrics,cadence_minutes:15,score_interval_seconds:10,background_interval_seconds:900,wallet_authority:CORE_URL});
  else if(['/v1/public/status','/v1/live/status','/v1/status'].includes(path)&&request.method==='GET')response=json(await marketSnapshot());
  else if(['/internal/cron','/internal/pulse'].includes(path)&&request.method==='POST'){const parsed=await parseTriggerDelivery(request);if(!parsed.ok)return json({error:parsed.error},401);response=json(await pulse());}
  else if(path==='/v1/session'&&request.method==='POST')response=session();
  else if(path==='/v1/legacy-wallet'&&!core){const auth=await authenticate(request,{DB});const w=(await pool.query('SELECT document FROM wallets WHERE user_id=$1',[auth.user])).rows[0]?.document;response=json(w?{initialized:true,...viewWallet(w,(await getLiveMarket()).quotes)}:{initialized:false});}
  else if(path.startsWith('/v1/wallet')){
   if(!core){const upstream=await fetch(CORE_URL+path,{method:request.method,headers:{'Content-Type':'application/json',Authorization:request.headers.get('authorization')||''},...(request.method==='GET'?{}:{body:await request.text()}),signal:AbortSignal.timeout(25000)});response=new Response(upstream.body,{status:upstream.status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});}
   else if(path==='/v1/wallet/tick'&&request.method==='POST'){const auth=identity(request);response=json(await trackWallets(await getLiveMarket(),auth.user));}
   else response=await walletAPI(request,path);
  }else if(path==='/v1/market'&&request.method==='GET'){identity(request);response=json(await getLiveMarket());}
  else if(panelAssets[path])response=new Response(panelAssets[path].content,{headers:{'Content-Type':panelAssets[path].type,'Cache-Control':'public,max-age=60','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"}});
  else response=json({error:'Uç nokta bulunamadı'},404);
 }catch(e){const quota=/quota|exceeded|suspend/i.test(e.message);const status=e.status&&e.status>=400&&e.status<600?e.status:(quota||path==='/v1/legacy-wallet'?503:400);response=json({error:quota?'Sanal kasa geçici kota korumasında. Piyasa verisi bağımsız sunuculardan çalışmaya devam eder.':e.message,code:quota?'WALLET_QUOTA_PAUSED':'REQUEST_FAILED',retry_after_seconds:quota?900:30},status);if(status===503||status===429)response.headers.set('Retry-After',String(quota?900:30));}
 for(const[k,v]of Object.entries(cors))response.headers.set(k,v);return response;
}};
