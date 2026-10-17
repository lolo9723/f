import {randomUUID} from 'node:crypto';
import {pool,state,setState,walletTransaction} from './db.mjs';
import {historyFeature,researchDecision} from './research.mjs';
import {learnPairs} from './learning.mjs';
import {rally,validateCandles} from './rally.mjs';
import {active,applyTrades,reserve} from './wallet.mjs';
export async function getJSON(url){
 const r=await fetch(url,{signal:AbortSignal.timeout(12000),redirect:'error'});
 if(!r.ok)throw Error('Kaynak HTTP '+r.status);
 const j=await r.json();if(j&&typeof j==='object'&&!Array.isArray(j)&&Object.hasOwn(j,'code')&&j.code!==0)throw Error('Kaynak API kodu '+j.code);
 return j?.data??j;
}
const BASE='https://api.binance.me';
async function candles(pair,interval,limit){return validateCandles(await getJSON(BASE+'/api/v1/klines?'+new URLSearchParams({symbol:pair,interval,limit:String(limit)})),interval==='1h'?3600000:60000);}
export async function refresh(invocationId,scheduledAt=Date.now()){
 const runId=invocationId||randomUUID(),now=Date.now();
 const claim=await pool.query("INSERT INTO engine_lock VALUES('scanner',$1,$2) ON CONFLICT(id) DO UPDATE SET lease_until=excluded.lease_until,owner=excluded.owner WHERE engine_lock.lease_until<$3 RETURNING owner",[now+180000,runId,now]);
 if(!claim.rows.length)return {status:'already_running'};
 const inserted=await pool.query("INSERT INTO live_runs(id,started_at,status) VALUES($1,$2,'running') ON CONFLICT DO NOTHING RETURNING id",[runId,now]);
 if(!inserted.rows.length){await pool.query("UPDATE engine_lock SET lease_until=0 WHERE owner=$1",[runId]);return {status:'duplicate'};}
 let summary={scanned:0,failed:0};
 try{
  const usage=(await pool.query('SELECT pg_database_size(current_database())::text bytes')).rows[0];
  const retainHistory=Number(usage.bytes)<750000000;
  const old=await state('market')||{},quotes={},errors=[];
  const [inventory,tickers]=await Promise.all([getJSON('https://www.binance.tr/open/v1/common/symbols'),getJSON(BASE+'/api/v1/ticker/24hr')]);
  if(!Array.isArray(inventory.list)||!Array.isArray(tickers))throw Error('TR piyasa sözleşmesi geçersiz');
  const symbols=inventory.list.filter(s=>s.quoteAsset==='TRY'&&s.spotTradingEnable===1),supported=symbols.filter(s=>s.type===1);
  const symbolSet=new Set(supported.map(s=>s.symbol.replace('_','')));
  for(const t of tickers){if(!symbolSet.has(t.symbol))continue;const price=Number(t.lastPrice),volume=Number(t.quoteVolume),change=Number(t.priceChangePercent)/100;
   if(!(price>0&&Number.isFinite(volume)&&volume>=0&&Number.isFinite(change)))continue;
   quotes[t.symbol]={price,quote_volume:volume,change,observed_at:Date.now(),source:'BINANCE_TR_TYPE_1_OFFICIAL',symbol_type:1};
  }
  const all=Object.keys(quotes).sort(),liquid=[...all].sort((a,b)=>quotes[b].quote_volume-quotes[a].quote_volume);
  const walletRows=(await pool.query("SELECT user_id,revision,document FROM wallets WHERE document->'settings'->>'enabled'='true' OR jsonb_path_exists(document,'$.orders[*] ? (@.state == \"TRACKING\" || @.state == \"WAITING_ENTRY\")') LIMIT 100")).rows;
  const activePairs=[...new Set(walletRows.flatMap(r=>r.document.orders.filter(active).map(o=>o.pair)))];
  const cursor=(old.rotation||0)%Math.max(1,all.length),rotation=[...all.slice(cursor),...all.slice(0,cursor)].slice(0,8);
  const chosen=[...new Set([...activePairs,...liquid.slice(0,16),...rotation])].filter(p=>symbolSet.has(p)).slice(0,36);
  const modelRows=(await pool.query('SELECT pair,payload FROM live_models')).rows;
  const models=Object.fromEntries(modelRows.map(r=>[r.pair,r.payload]));
  const candidates=[];
  for(let i=0;i<chosen.length;i+=4){await Promise.all(chosen.slice(i,i+4).map(async pair=>{try{
   const [hour,minute,book]=await Promise.all([candles(pair,'1h',121),candles(pair,'1m',121),getJSON(BASE+'/api/v3/depth?'+new URLSearchParams({symbol:pair,limit:'20'}))]);
   const score=rally(hour,minute,book,quotes.BTCTRY?.change||0),c={pair,...score,last_closed_minute:score.as_of,as_of:Date.now(),price:quotes[pair].price,model_id:'rally-base-v0.1',symbol_id:'binance_tr:spot:'+pair};const learned=researchDecision(models[pair],historyFeature(hour),score.score);
   c.base_score=score.score;c.research=learned;c.final_score=learned.final_score;c.score=learned.final_score;
   if(learned.entry){c.model_id=learned.model_id;c.entry=learned.entry;c.target=learned.target;c.stop=learned.stop;}
   candidates.push(c);
   // Preserve headroom for wallets and current snapshots on the Free plan.
   if(!retainHistory)return;
   // Closed hourly observations are append-only. Minute bars remain source-backed request data.
   await pool.query("INSERT INTO live_candles SELECT 'binance_tr',$1,'1h',(x->>'time')::bigint,x FROM jsonb_array_elements($2::jsonb) x ON CONFLICT DO NOTHING",[pair,JSON.stringify(hour)]);
   await pool.query('INSERT INTO live_patterns(id,symbol,observed_at,features,price) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING',[pair+':'+hour.at(-1).time,pair,hour.at(-1).time+3600000,JSON.stringify({method:'closed-hour-pattern-v1',score:Math.max(0,Math.min(100,Math.round(50+(hour.at(-1).close/hour.at(-7).close-1)*500))),return_1:hour.at(-1).close/hour.at(-2).close-1,return_6:hour.at(-1).close/hour.at(-7).close-1,range_fraction:(hour.at(-1).high-hour.at(-1).low)/hour.at(-1).close}),hour.at(-1).close]);
  }catch(e){errors.push({pair,error:e.message});}}));}
  candidates.sort((a,b)=>b.score-a.score);summary={scanned:candidates.length,failed:errors.length,known_symbols:symbols.length,quotes:Object.keys(quotes).length};
  const market={quotes,candidates,rotation:(cursor+8)%Math.max(1,all.length),updated_at:Date.now(),scheduled_at:scheduledAt,known_symbols:symbols.length,supported_symbols:supported.length,errors,scan_policy:'Top 16 liquid + 8 rotating + open positions, maximum 36 per cycle',cadence_minutes:15};
  await setState('market',market);
  // Mark only outcomes for which the exact hourly observation is present; no interpolated data.
  await pool.query("UPDATE live_patterns p SET outcomes=p.outcomes||jsonb_build_object(h.h::text,jsonb_build_object('return',((c.payload->>'close')::double precision/p.price)-1,'evaluated_at',$1::bigint)) FROM (VALUES(1),(3),(6),(12),(24),(48)) h(h),live_candles c WHERE c.exchange='binance_tr' AND c.symbol=p.symbol AND c.interval='1h' AND c.open_ms=p.observed_at+(h.h-1)::bigint*3600000 AND NOT (p.outcomes ? h.h::text)",[Date.now()]);
  let requests=0;
  for(const row of walletRows){
   const batches=[];
   for(const o of row.document.orders.filter(active)){
    let cursor=o.cursor_time??o.created_at,lastId=o.last_trade_id;
    for(let page=0;page<8&&requests<50;page++){
     try{const end=Math.min(Date.now(),cursor+3599999),params={symbol:o.pair,limit:'1000'};
      if(lastId!==null)params.fromId=String(lastId+1);else {params.startTime=String(cursor);params.endTime=String(end);}
      const trades=await getJSON(BASE+'/api/v1/aggTrades?'+new URLSearchParams(params));requests++;
      const until=Date.now();batches.push({id:o.id,rows:trades,until,emptyEnd:lastId===null?end:until});
      if(trades.length){lastId=Number(trades.at(-1).a);cursor=Number(trades.at(-1).T);}
      else cursor=end+1;
      if(trades.length<1000||cursor>=until)break;
     }catch(e){requests++;batches.push({id:o.id,error:e.message});break;}
    }
   }
   try{await walletTransaction(row.user_id,row.revision,w=>{
    for(const batch of batches){const o=w.orders.find(x=>x.id===batch.id);if(!o||!active(o))continue;
     if(batch.error){o.tracking_error=batch.error;continue;}
     try{applyTrades(w,o,batch.rows,batch.until);if(!batch.rows.length){o.cursor_time=batch.emptyEnd+1;o.verified_until=batch.emptyEnd;if(o.state==='WAITING_ENTRY'&&batch.emptyEnd>=o.entry_expires_at){o.state='EXPIRED_UNFILLED';o.closed_at=o.entry_expires_at;}}}catch(e){o.tracking_error=e.message;}
    }
    reserve(w,candidates,quotes,Date.now());
   });}catch(e){if(e.status!==409)errors.push({wallet:'update',error:e.message});}
  }
  const size=(await pool.query('SELECT pg_database_size(current_database())::text bytes')).rows[0];
  const researchQueue=chosen.filter(p=>!models[p]||Date.now()-(models[p].attempted_at||models[p].trained_at)>12*3600000);
  const researchUpdates=Number(size.bytes)<750000000?await learnPairs(researchQueue,2):[{status:'quota_pause',reason:'Yeni tarihsel indirme için kapasite sınırı'}];
  const seed=await state('historical_coverage');
  const counts=(await pool.query("SELECT count(*)::int rows,count(DISTINCT symbol)::int symbols,min(open_ms)::text first_ms,max(open_ms)::text last_ms FROM live_candles WHERE exchange='binance_tr'")).rows[0];
  const patterns=(await pool.query("SELECT symbol,observed_at::text,features,outcomes FROM live_patterns ORDER BY observed_at DESC LIMIT 30")).rows.map(x=>({...x,observed_at:Number(x.observed_at)}));
  const totalPatterns=(await pool.query('SELECT count(*)::int count FROM live_patterns')).rows[0].count;
  const snapshot={...seed,schema_version:'coverage-v1',generated_at_ms:Date.now(),status:'live',all_history_complete:false,inventory:{...(seed?.inventory||{}),binance_tr:{known_symbols:symbols.length,latest_status:'complete',sources:[{source:'https://www.binance.tr/open/v1/common/symbols',status:'complete'}]}},totals:{...(seed?.totals||{}),rows:(seed?.totals?.rows||0)+counts.rows,live_tr_rows:counts.rows},intervals:[...(seed?.intervals||[]),{exchange:'binance_tr',interval:'1h',rows:counts.rows,symbols:counts.symbols,first_ms:Number(counts.first_ms),last_ms:Number(counts.last_ms)}],tasks:{...(seed?.tasks||{}),live_scanned:summary.scanned,live_failed:summary.failed},signals:candidates.map(c=>({signal_id:c.pair+':'+c.as_of,symbol_id:c.symbol_id,predicted_at:c.as_of,model_id:c.model_id,entry:c.entry,target:c.target,stop:c.stop,expires_at:c.as_of+20*60000,score:c.score,base_score:c.base_score,final_score:c.final_score,research:c.research,probability:null,expected_return:null,mode:'rule_candidate'})),models:[...Object.values(models).filter(m=>m.model_id).map(m=>({model_id:m.model_id,status:m.status,created_at:m.trained_at})),{model_id:'rally-base-v0.1',status:'unvalidated_rule',created_at:now},...(seed?.models||[])],patterns:{status:'collecting',events:patterns,total:totalPatterns},performance:{status:'not_evaluated',trades:0,win_rate:null},live:{...summary,history_paused:!retainHistory,research_updates:researchUpdates,database_bytes:Number(size.bytes),updated_at:market.updated_at,cadence_minutes:15,next_scheduled_at:Math.floor(Date.now()/900000)*900000+900000,scheduler:'Neon Function Trigger',scan_policy:market.scan_policy,errors},limitations:['Ralli puanı betimsel bir endekstir; başarı olasılığı değildir.','Bütün geçmiş ve bütün TR pariteleri henüz analiz edilmedi.','Tarihsel katkı betimsel endeks kuralıdır; kalibre kazanma olasılığı değildir.','FCM arka plan bildirimi yapılandırılmadı.']};
  await pool.query("INSERT INTO snapshots VALUES('current',$1,$2) ON CONFLICT(id) DO UPDATE SET generated_at=excluded.generated_at,payload_json=excluded.payload_json",[snapshot.generated_at_ms,JSON.stringify(snapshot)]);
  await pool.query("UPDATE live_runs SET status='complete',finished_at=$2,details=$3 WHERE id=$1",[runId,Date.now(),JSON.stringify({...summary,errors})]);
  return {status:'complete',...summary};
 }catch(e){await pool.query("UPDATE live_runs SET status='failed',finished_at=$2,details=$3 WHERE id=$1",[runId,Date.now(),JSON.stringify({error:e.message})]);throw e;}
 finally{await pool.query('UPDATE engine_lock SET lease_until=0 WHERE id=$1 AND owner=$2',['scanner',runId]);}
}
