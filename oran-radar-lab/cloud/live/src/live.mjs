import {optimalOpportunity} from './optimizer.mjs';
import {randomUUID} from 'node:crypto';
import {pool,state,setState} from './db.mjs';
import {getJSON} from './market-data.mjs';
import {indicatorDecision} from './indicators.mjs';
import {rally,validateCandles} from './rally.mjs';
import {historyFeature,researchDecision,policyPlan,POLICIES} from './research.mjs';
export const LIVE_INTERVAL_MS=10000,MAX_SCORE_AGE_MS=90000;
const BASE='https://api.binance.me',barsCache=new Map();
let modelCache={at:0,models:{}},inflight=null;
async function bars(pair,interval,now){
 const period=interval==='1h'?3600000:60000,key=pair+interval,cached=barsCache.get(key);
 if(cached?.at(-1)?.time+period===Math.floor(now/period)*period)return cached;
 const rows=validateCandles(await getJSON(BASE+'/api/v1/klines?'+new URLSearchParams({symbol:pair,interval,limit:'121'})),period,Date.now());
 barsCache.set(key,rows);return rows;
}
export function liveCandidate({pair,hour,minute,book,quote,btc,model,bookAt},now=Date.now()){
 const asOf=Math.min(quote.observed_at,btc.observed_at,bookAt);
 if(!Number.isFinite(asOf)||now-asOf>20000||asOf>now||minute.at(-1).time+120000<=now||hour.at(-1).time+7200000<=now)throw Error('Canlı puan kaynakları güncel değil');
 const base=rally(hour,minute,book,btc.change,quote.price),feature=historyFeature(hour);
 const indicators=indicatorDecision(minute,quote.price,base.spread_pct),baseScore=Math.max(0,Math.min(100,Math.round((base.score+indicators.adjustment)*10)/10));
 const research=researchDecision(model,feature,baseScore,now);
 // The evidence uses closed historical bars; the validated policy is repriced at today's quote.
 if(research.entry){const policy=POLICIES.find(p=>p.id===research.selected_policy)||POLICIES[1];Object.assign(research,policyPlan({...feature,price:quote.price},policy));}
 const candidate={pair,...base,base_rally_score:base.score,indicators,base_score:baseScore,score:research.final_score,final_score:research.final_score,research,...(research.entry?{entry:research.entry,target:research.target,stop:research.stop}:{}),model_id:research.entry?research.model_id:'live-rally-v1',symbol_id:'binance_tr:spot:'+pair,as_of:asOf,live_valid:true,max_age_ms:MAX_SCORE_AGE_MS,last_closed_minute:minute.at(-1).time+60000,inputs_at:{price:quote.observed_at,book:bookAt,btc:btc.observed_at,minute_close:minute.at(-1).time+60000,hour_close:hour.at(-1).time+3600000}};
 candidate.cloud_gate=optimalOpportunity(candidate,now);return candidate;
}
async function ticker(pair){const at=Date.now(),t=await getJSON(BASE+'/api/v1/ticker/24hr?symbol='+encodeURIComponent(pair));const price=Number(t.lastPrice),change=Number(t.priceChangePercent)/100;if(t.symbol!==pair||!(price>0)||!Number.isFinite(change))throw Error('Güncel TR fiyatı doğrulanamadı');return {price,change,quote_volume:Number(t.quoteVolume),observed_at:at,source:'BINANCE_TR_TYPE_1_OFFICIAL',symbol_type:1};}
async function calculate(){
 const now=Date.now(),previous=await state('fast_market');if(previous&&now-previous.last_attempt_at<LIVE_INTERVAL_MS)return previous;
 const owner=randomUUID(),claim=await pool.query("INSERT INTO engine_lock VALUES('live-score',$1,$2) ON CONFLICT(id) DO UPDATE SET lease_until=excluded.lease_until,owner=excluded.owner WHERE engine_lock.lease_until<$3 RETURNING owner",[now+45000,owner,now]);
 if(!claim.rows.length)return previous||{candidates:[],quotes:{},updated_at:0,last_attempt_at:now,errors:[{pair:'SYSTEM',error:'İlk canlı puan hazırlanıyor'}]};
 try{
  const base=await state('market');if(!base?.updated_at||now-base.updated_at>30*60000)throw Error('TR aday evreninin derin taraması güncel değil');
  if(now-modelCache.at>60000){const rows=(await pool.query('SELECT pair,payload FROM live_models')).rows;modelCache={at:now,models:Object.fromEntries(rows.map(r=>[r.pair,r.payload]))};}
  const pairs=[...new Set(base.candidates.map(c=>c.pair))].filter(p=>Object.hasOwn(base.quotes,p)).slice(0,36),quotes={...base.quotes},candidates=[],errors=[];
  const btc=await ticker('BTCTRY');quotes.BTCTRY=btc;
  for(let i=0;i<pairs.length;i+=6)await Promise.all(pairs.slice(i,i+6).map(async pair=>{try{
   const started=Date.now();const [hour,minute,book,quote]=await Promise.all([bars(pair,'1h',started),bars(pair,'1m',started),getJSON(BASE+'/api/v3/depth?'+new URLSearchParams({symbol:pair,limit:'20'})),pair==='BTCTRY'?btc:ticker(pair)]);
   quotes[pair]=quote;candidates.push(liveCandidate({pair,hour,minute,book,quote,btc,model:modelCache.models[pair],bookAt:started}));
  }catch(e){errors.push({pair,error:e.message});const old=previous?.candidates.find(c=>c.pair===pair)||base.candidates.find(c=>c.pair===pair);if(old)candidates.push({...old,live_valid:false,live_error:e.message,max_age_ms:MAX_SCORE_AGE_MS});}}));
  candidates.sort((a,b)=>Number(b.live_valid)-Number(a.live_valid)||b.score-a.score);
  const valid=candidates.filter(c=>c.live_valid),result={...base,quotes,candidates,updated_at:valid.length?Math.min(...valid.map(c=>c.as_of)):(previous?.updated_at||0),last_attempt_at:Date.now(),deep_scan_at:base.updated_at,score_interval_seconds:10,background_interval_seconds:60,errors};
  await setState('fast_market',result);return result;
 }finally{await pool.query("UPDATE engine_lock SET lease_until=0 WHERE id='live-score' AND owner=$1",[owner]);}
}
export async function getLiveMarket(){if(inflight)return inflight;inflight=calculate();try{return await inflight;}finally{inflight=null;}}
export function liveSignals(market){return (market?.candidates||[]).map(c=>({signal_id:c.pair+':'+c.as_of,symbol_id:c.symbol_id,predicted_at:c.as_of,model_id:c.model_id,entry:c.entry,target:c.target,stop:c.stop,expires_at:c.as_of+MAX_SCORE_AGE_MS,score:c.score,base_score:c.base_score,final_score:c.final_score,research:c.research,live_valid:c.live_valid===true,live_error:c.live_error||null,inputs_at:c.inputs_at,base_rally_score:c.base_rally_score,indicators:c.indicators,cloud_gate:c.cloud_gate,probability:null,expected_return:null,mode:'live_rule_candidate'}));}
