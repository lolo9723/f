// Market computation has NO database imports. The same engine runs independently
// on both HTTP servers, even when the wallet database has hit a free allowance.
import {selectUniverse} from './universe.mjs';
import {storedModels,saveModels} from './model-store.mjs';
import seed from './research-seed.json' with {type:'json'};
import coverage from '../../../cloudflare-coverage.json' with {type:'json'};
import {getJSON} from './market-data.mjs';
import {bars,liveCandidate,liveSignals,LIVE_INTERVAL_MS} from './live.mjs';
import {fitResearch} from './research.mjs';
import {validateCandles} from './rally.mjs';
const BASE='https://api.binance.me',models={...seed.models},bookCache=new Map();
let inventory=null,inventoryAt=0,universe=[],deepAt=0,quoteCache=null,quoteAt=0,market=null,inflight=null,training=null,modelsCheckedAt=0;
export const marketMetrics={pg_reads:0,pg_writes:0,source_requests:0};
async function source(url){marketMetrics.source_requests++;return getJSON(url);}
async function allQuotes(){
 const now=Date.now();if(quoteCache&&now-quoteAt<10000)return quoteCache;
 if(!inventory||now-inventoryAt>=900000){const x=await source('https://www.binance.tr/open/v1/common/symbols');if(!Array.isArray(x.list))throw Error('TR envanteri doğrulanamadı');inventory=x.list.filter(s=>s.quoteAsset==='TRY'&&s.spotTradingEnable===1&&s.type===1);inventoryAt=Date.now();}
 const allowed=new Set(inventory.map(s=>s.symbol.replace('_',''))),started=Date.now(),ticks=await source(BASE+'/api/v1/ticker/24hr');
 if(!Array.isArray(ticks))throw Error('TR kotasyon dizisi gerekli');const quotes={};
 for(const t of ticks){if(!allowed.has(t.symbol))continue;const price=Number(t.lastPrice),volume=Number(t.quoteVolume),change=Number(t.priceChangePercent)/100;if(price>0&&Number.isFinite(price)&&volume>=0&&Number.isFinite(volume)&&Number.isFinite(change))quotes[t.symbol]={price,quote_volume:volume,change,observed_at:started,source:'BINANCE_TR_TYPE_1_OFFICIAL',symbol_type:1};}
 if(!quotes.BTCTRY||Object.keys(quotes).length<20)throw Error('TR kotasyon kapsamı yetersiz');quoteCache=quotes;quoteAt=started;return quotes;
}
async function book(pair){const old=bookCache.get(pair);if(old&&Date.now()-old.at<15000)return old;const at=Date.now(),value=await source(BASE+'/api/v3/depth?'+new URLSearchParams({symbol:pair,limit:'20'})),row={at,value};bookCache.set(pair,row);return row;}
function choose(quotes){if(universe.length&&Date.now()-deepAt<900000)return;const selected=selectUniverse(quotes);universe=selected.pairs;deepAt=selected.deep_scan_at;}
async function calculate(){
 if(market&&Date.now()-market.last_attempt_at<LIVE_INTERVAL_MS)return market;
 // Hydrate once on a cold start, then at most once per six hours. A storage
 // failure uses the packaged seed; stale evidence always contributes zero.
 if(Date.now()-modelsCheckedAt>6*3600000){modelsCheckedAt=Date.now();try{const saved=await storedModels();for(const [pair,m]of Object.entries(saved?.models||{}))if((m.trained_at||0)>(models[pair]?.trained_at||0))models[pair]=m;}catch{/* Stale research contributes zero; price scoring remains available. */}}
 let quotes=await allQuotes();choose(quotes);const candidates=[],errors=[];
 const btcMinute=await bars('BTCTRY','1m',Date.now());
 for(let i=0;i<universe.length;i+=6){if(Date.now()-quoteAt>=10000)quotes=await allQuotes();await Promise.all(universe.slice(i,i+6).map(async pair=>{try{
  const [hour,minute,depth]=await Promise.all([bars(pair,'1h',Date.now()),pair==='BTCTRY'?btcMinute:bars(pair,'1m',Date.now()),book(pair)]);
  const bid=depth.value.bids.reduce((s,[p,q])=>s+Number(p)*Number(q),0),ask=depth.value.asks.reduce((s,[p,q])=>s+Number(p)*Number(q),0);
  const context={btc_return5:quotes.BTCTRY.price/btcMinute.at(-5).close-1,breadth:Object.values(quotes).filter(q=>q.change>0).length/Object.keys(quotes).length,depth_imbalance:(bid-ask)/Math.max(1,bid+ask)};
  candidates.push(liveCandidate({pair,hour,minute,book:depth.value,quote:quotes[pair],btc:quotes.BTCTRY,model:models[pair],bookAt:depth.at,context}));
 }catch(e){errors.push({pair,error:e.message});const old=market?.candidates.find(c=>c.pair===pair);if(old)candidates.push({...old,live_valid:false,live_error:e.message});}}));}
 candidates.sort((a,b)=>Number(b.live_valid)-Number(a.live_valid)||b.score-a.score);const valid=candidates.filter(c=>c.live_valid);
 market={quotes,candidates,updated_at:valid.length?Math.min(...valid.map(c=>c.as_of)):(market?.updated_at||0),last_attempt_at:Date.now(),deep_scan_at:deepAt,errors,known_symbols:inventory.length};return market;
}
export async function getLiveMarket(){if(inflight)return inflight;inflight=calculate();try{return await inflight;}finally{inflight=null;}}
export async function marketSnapshot(){let m=await getLiveMarket();if(m.candidates.length&&m.candidates.every(c=>!['historical_support','limited_historical_evidence'].includes(c.research?.status))&&Date.now()-seed.generated_at>24*3600000){await refreshResearch(2).catch(()=>{});if(market)market.last_attempt_at=0;m=await getLiveMarket();}return {...coverage,schema_version:'coverage-v1',generated_at_ms:Date.now(),status:m.candidates.some(c=>c.live_valid)?'live':'degraded',signals:liveSignals(m),models:Object.values(models).filter(m=>m.model_id).map(m=>({model_id:m.model_id,status:m.status,created_at:m.trained_at})),live:{updated_at:m.updated_at,deep_scan_at:m.deep_scan_at,score_interval_seconds:10,background_interval_seconds:900,cadence_minutes:15,next_scheduled_at:Math.floor(Date.now()/900000)*900000+900000,scanned:m.candidates.filter(c=>c.live_valid).length,failed:m.errors.length,known_symbols:m.known_symbols,quotes:Object.keys(m.quotes).length,scan_policy:'16 likit + 8 dönüşümlü TR paritesi',errors:m.errors,storage:'DATABASE_FREE_MARKET',pg_reads:0,pg_writes:0},limitations:['Puan betimsel endekstir; başarı olasılığı değildir.','Araştırma güncel değilse katkı sıfırlanır; ayrı bir alış vetosu uygulanmaz.','Sanal kasa ücret ve risk varsayımlarıyla çalışır.']};}
export async function refreshResearch(maximum=2){
 if(training)return training;training=(async()=>{const m=await getLiveMarket(),pairs=m.candidates.map(c=>c.pair).sort((a,b)=>(models[a]?.attempted_at||models[a]?.trained_at||0)-(models[b]?.attempted_at||models[b]?.trained_at||0)),results=[];
 for(const pair of pairs.filter(p=>!models[p]||Date.now()-(models[p].attempted_at||models[p].trained_at)>=12*3600000).slice(0,maximum)){try{const unique=new Map();let end=Date.now();for(let page=0;page<4;page++){const data=await source(BASE+'/api/v1/klines?'+new URLSearchParams({symbol:pair,interval:'1h',limit:'1000',endTime:String(end)}));const rows=validateCandles(data,3600000,Date.now(),false);rows.forEach(b=>unique.set(b.time,b));if(!rows.length)break;end=rows[0].time-1;if(data.length<1000)break;}
 const rows=[...unique.values()].sort((a,b)=>a.time-b.time);for(let i=1;i<rows.length;i++)if(rows[i].time!==rows[i-1].time+3600000)throw Error('Tarihsel seride boşluk');const model=fitResearch(rows,pair);model.attempted_at=Date.now();models[pair]=model;results.push({pair,status:model.status,rows:rows.length});}catch(e){results.push({pair,status:'failed',error:e.message});}}
 const storage=await saveModels(models).catch(e=>({status:'failed',error:e.message}));return {updates:results,storage};})();try{return await training;}finally{training=null;}
}
