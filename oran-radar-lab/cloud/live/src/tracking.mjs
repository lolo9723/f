import {randomUUID} from 'node:crypto';
import {pool,walletTransaction} from './db.mjs';
import {getJSON} from './market-data.mjs';
import {active,applyTrades,reserve,invalidatePending,decision} from './wallet.mjs';
export async function trackWallets(market,onlyUser=null){
 const now=Date.now(),owner=randomUUID(),lockId=onlyUser?"wallet:"+onlyUser:"wallet-live";
 const claim=await pool.query("INSERT INTO engine_lock VALUES($4,$1,$2) ON CONFLICT(id) DO UPDATE SET lease_until=excluded.lease_until,owner=excluded.owner WHERE engine_lock.lease_until<$3 RETURNING owner",[now+55000,owner,now,lockId]);
 if(!claim.rows.length)return {status:'already_running'};
 const errors=[];let requests=0;
 try{
  const rows=(await pool.query(onlyUser?"SELECT user_id,revision,document FROM wallets WHERE user_id=$1": "SELECT user_id,revision,document FROM wallets WHERE document->'settings'->>'enabled'='true' OR jsonb_path_exists(document,'$.orders[*] ? (@.state == \"TRACKING\" || @.state == \"WAITING_ENTRY\")') LIMIT 100",onlyUser?[onlyUser]:[])).rows;
  for(const row of rows){
   const batches=[];
   for(const o of row.document.orders.filter(active)){
    let cursor=o.cursor_time??o.created_at,lastId=o.last_trade_id;
    for(let page=0;page<8&&requests<50;page++){
     try{const started=Date.now(),end=Math.min(started,cursor+3599999),params={symbol:o.pair,limit:'1000'};
      if(lastId!==null)params.fromId=String(lastId+1);else {params.startTime=String(cursor);params.endTime=String(end);}
      const trades=await getJSON('https://api.binance.me/api/v1/aggTrades?'+new URLSearchParams(params));requests++;
      batches.push({id:o.id,rows:trades,until:Date.now(),emptyEnd:lastId===null?end:started,verified:trades.length<1000?(lastId===null?end:started):null});
      if(trades.length){lastId=Number(trades.at(-1).a);cursor=Number(trades.at(-1).T);}else cursor=end+1;
      if(trades.length<1000||cursor>=started)break;
     }catch(e){requests++;batches.push({id:o.id,error:e.message});break;}
    }
   }
   try{await walletTransaction(row.user_id,row.revision,w=>{
    for(const batch of batches){const o=w.orders.find(x=>x.id===batch.id);if(!o||!active(o))continue;
     if(batch.error){o.tracking_error=batch.error;continue;}
     try{applyTrades(w,o,batch.rows,batch.until);if(batch.verified!==null)o.verified_until=Math.max(batch.verified,batch.rows.length?Number(batch.rows.at(-1).T):0);if(!batch.rows.length){o.cursor_time=batch.emptyEnd+1;if(o.state==='WAITING_ENTRY'&&batch.emptyEnd>=o.entry_expires_at){o.state='EXPIRED_UNFILLED';o.closed_at=o.entry_expires_at;}}}catch(e){o.tracking_error=e.message;}
    }
    invalidatePending(w,market.candidates||[],Date.now());
    // An unverified fill history must never free funds or authorize an additional order.
    if(w.orders.some(o=>active(o)&&(o.tracking_error||Date.now()-(o.verified_until??o.created_at)>90000))){decision(w,'TRY','TRADE_HISTORY_PENDING',Date.now());return;}
    reserve(w,(market.candidates||[]).filter(c=>c.live_valid===true),market.quotes||{},Date.now());
   });}catch(e){if(e.status!==409)errors.push(e.message);}
  }
  return {status:'complete',wallets:rows.length,trade_requests:requests,errors};
 }finally{await pool.query("UPDATE engine_lock SET lease_until=$2 WHERE id=$3 AND owner=$1",[owner,now+55000,lockId]);}
}
