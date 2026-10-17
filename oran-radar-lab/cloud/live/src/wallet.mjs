import {randomUUID} from 'node:crypto';
import {optimalOpportunity} from './optimizer.mjs';
export const DEFAULTS={execution_mode:'MANUAL',enabled:false,threshold:70,score_source:'FINAL',amount_mode:'AUTO',entry_mode:'MARKET',fixed_try:1000,coin_cap_try:1000,total_cap_try:null,risk_pct:1,portfolio_risk_pct:3,max_coin_pct:30,max_positions:5,cooldown_hours:12,target_pct:null,stop_pct:null,entry_prices:{}};
export const active=o=>['WAITING_ENTRY','TRACKING'].includes(o.state);
const cents=x=>Math.round(x*100);
const money=x=>x/100;
const valid=(x,min,max)=>typeof x==='number'&&Number.isFinite(x)&&x>=min&&x<=max;
export function settings(changes,previous=DEFAULTS){
 if(!changes||typeof changes!=='object'||Array.isArray(changes)||Object.keys(changes).some(k=>!Object.hasOwn(DEFAULTS,k)))throw Error('Bilinmeyen otomasyon ayarı');
 const s={...DEFAULTS,...previous,...changes};
 if(Object.hasOwn(changes,'enabled')&&typeof changes.enabled!=='boolean')throw Error('Geçersiz otomasyon seçimi');
 if(Object.hasOwn(changes,'execution_mode'))s.enabled=s.execution_mode==='OPTIMAL';
 if(!['MANUAL','OPTIMAL'].includes(s.execution_mode))throw Error('Manuel veya Optimal mod seçilmeli');
 if(s.execution_mode==='MANUAL')s.enabled=false;
 if(typeof s.enabled!=='boolean'||![50,60,70,80,90,100].includes(s.threshold)||!['BASE','FINAL'].includes(s.score_source)||!['AUTO','FIXED'].includes(s.amount_mode)||!['MARKET','LIMIT'].includes(s.entry_mode))throw Error('Geçersiz otomasyon seçimi');
 for(const k of ['fixed_try','coin_cap_try'])if(!valid(s[k],.01,1e9))throw Error('Pozitif TL tutarı gerekli: '+k);
 if(s.total_cap_try!==null&&!valid(s.total_cap_try,10,1e9))throw Error('Bütçe en az 10 TL olmalı');
 if(s.execution_mode==='OPTIMAL'&&s.total_cap_try===null)throw Error('Optimal mod için çalışma bütçesi gerekli');
 if(!Number.isInteger(s.max_positions)||!valid(s.max_positions,1,100)||!valid(s.cooldown_hours,0,720))throw Error('İşlem sayısı veya bekleme süresi geçersiz');
 for(const k of ['risk_pct','portfolio_risk_pct','max_coin_pct'])if(!valid(s[k],.01,100))throw Error('Risk sınırı geçersiz: '+k);
 for(const k of ['target_pct','stop_pct'])if(s[k]!==null&&!valid(s[k],.01,k==='target_pct'?1000:99))throw Error('Hedef / stop yüzdesi geçersiz');
 if(!s.entry_prices||Array.isArray(s.entry_prices)||typeof s.entry_prices!=='object'||Object.entries(s.entry_prices).some(([p,v])=>!/^\w{2,20}TRY$/.test(p)||!valid(v,1e-12,1e12)))throw Error('Coin alış fiyatları geçersiz');
 return structuredClone(s);
}
export function freshWallet(initial){
 if(!valid(initial,.01,1e9))throw Error('İlk sanal bakiye pozitif TL olmalı');
 return {schema:'cloud-wallet-v1',revision:0,initial_cents:cents(initial),cash_cents:cents(initial),settings:{...DEFAULTS,entry_prices:{}},orders:[],decisions:[],last_cycle_at:null};
}
export function balances(w,quotes={}){
 let reserved=0,invested=0,market=0,unrealized=0,risk=0;
 for(const o of w.orders){if(!active(o))continue;risk+=o.risk_cents;
  if(o.state==='WAITING_ENTRY')reserved+=o.amount_cents;
  else {invested+=o.amount_cents;const q=quotes[o.pair],price=q&&q.observed_at>=o.observed_at?q.price:(o.observed_price||o.entry);const value=cents(o.quantity*price*.999);market+=value;unrealized+=value-o.amount_cents;}
 }
 const equity=w.cash_cents+market,available=w.cash_cents-reserved;
 return {initial_try:money(w.initial_cents),cash_try:money(w.cash_cents),reserved_try:money(reserved),invested_try:money(invested),available_try:money(available),equity_try:money(equity),realized_pnl_try:money(w.cash_cents+invested-w.initial_cents),unrealized_pnl_try:money(unrealized),planned_risk_try:money(risk),valuation:'LAST_VERIFIED_QUOTE_OR_TRADE',budget_room_try:w.settings.total_cap_try===null?money(available):Math.max(0,w.settings.total_cap_try-money(invested+reserved))};
}
function decision(w,pair,reason,now){w.decisions.push({pair,reason,at:now});w.decisions=w.decisions.slice(-200);}
function orderCap(w,quotes,pair,entry,stop,riskScale=1){
 const s=w.settings,b=balances(w,quotes),loss=(entry-stop)/entry+.003;
 const coinUsed=w.orders.filter(o=>o.pair===pair&&active(o)).reduce((sum,o)=>sum+o.amount_cents,0)/100;
 const capital=Math.min(b.equity_try,s.total_cap_try??b.equity_try);
 const cap=Math.max(0,Math.min(b.available_try,b.budget_room_try,s.coin_cap_try-coinUsed,capital*s.max_coin_pct/100-coinUsed,capital*s.risk_pct/100*riskScale/loss,(capital*s.portfolio_risk_pct/100-b.planned_risk_try)/loss));
 return {cap,loss};
}
function availablePair(w,pair,now){
 const s=w.settings;
 if(w.orders.filter(active).length>=s.max_positions)return 'MAX_POSITIONS_REACHED';
 if(w.orders.some(o=>o.pair===pair&&(active(o)||now-o.created_at<s.cooldown_hours*3600000)))return 'COOLDOWN_OR_ACTIVE';
 return null;
}
function createOrder(w,{pair,entry,target,stop,amount,entry_mode,quote,trace},now){
 const amountCents=Math.floor(amount*100),loss=(entry-stop)/entry+.003;
 const o={id:randomUUID(),pair,state:entry_mode==='LIMIT'?'WAITING_ENTRY':'TRACKING',entry_mode,entry,target,stop,...trace,amount_cents:amountCents,risk_cents:Math.ceil(amountCents*loss),quantity:0,created_at:now,filled_at:null,closed_at:null,entry_expires_at:now+6*3600000,deadline_at:null,net_pnl_cents:null,last_trade_id:null,cursor_time:now,observed_at:now,observed_price:quote.price,tracking_error:null};
 if(o.state==='TRACKING'){o.quantity=amountCents/100*.999/entry;o.filled_at=now;o.deadline_at=now+48*3600000;w.cash_cents-=amountCents;}
 w.orders.push(o);decision(w,pair,'ORDER_CREATED',now);return o;
}
export function manualOrder(w,d,quotes,now=Date.now()){
 if((w.settings.execution_mode||'MANUAL')!=='MANUAL')throw Error('Önce Manuel moda geçip kaydet');
 const q=quotes[d.pair];
 if(!q||!valid(q.price,1e-12,1e12)||!Number.isFinite(q.observed_at)||now-q.observed_at>60000||q.observed_at>now+1000)throw Error('Güncel TR fiyatı gerekli');
 if(!['MARKET','LIMIT'].includes(d.entry_mode)||!valid(d.amount_try,10,1e9))throw Error('Alış türü ve en az 10 TL tutar gerekli');
 const entry=d.entry_mode==='MARKET'?q.price:d.entry;
 if(!valid(entry,1e-12,1e12)||!valid(d.stop,1e-12,1e12)||!valid(d.target,1e-12,1e12)||!(d.stop<entry&&d.target>entry))throw Error('Stop < alış < hedef olmalı');
 if(d.entry_mode==='LIMIT'&&entry>q.price*1.001)throw Error('Limit alış güncel fiyatın üzerinde olamaz');
 const reason=availablePair(w,d.pair,now);if(reason)throw Error(reason);
 const {cap}=orderCap(w,quotes,d.pair,entry,d.stop);
 if(d.amount_try>cap+.0001)throw Error('Bütçe / risk sınırı: en fazla '+cap.toFixed(2)+' TL');
 return createOrder(w,{pair:d.pair,entry,target:d.target,stop:d.stop,amount:d.amount_try,entry_mode:d.entry_mode,quote:q,trace:{execution_mode:'MANUAL',score:null,base_score:null,research_adjustment:0,score_source:'MANUAL',model_id:'user-plan',allocation_reason:'USER_SELECTED_PRICE_AND_AMOUNT'}},now);
}
export function reserve(w,candidates,quotes,now=Date.now(),onlyPair=null){
 const s={...DEFAULTS,...w.settings},optimal=!onlyPair;
 if(optimal&&(!s.enabled||s.execution_mode!=='OPTIMAL'||s.total_cap_try===null))return;
 const source=optimal?'FINAL':s.score_source;
 let eligible=[...new Map(candidates.map(c=>[c.pair,c])).values()].map(c=>({...c,selected_score:source==='BASE'?(c.base_score??c.score):(c.final_score??c.score)})).filter(c=>(!onlyPair||c.pair===onlyPair)&&c.live_valid!==false&&c.selected_score>=s.threshold&&Number.isFinite(c.as_of)&&now-c.as_of<(c.max_age_ms??(optimal?90000:20*60000))&&c.as_of<=now+1000);
 if(optimal){eligible=eligible.map(c=>({...c,allocation:optimalOpportunity(c,now)}));for(const c of eligible)if(!c.allocation.eligible)decision(w,c.pair,c.allocation.reason,now);eligible=eligible.filter(c=>c.allocation.eligible);}
 eligible.sort((a,b)=>optimal?b.allocation.priority-a.allocation.priority:b.selected_score-a.selected_score);
 const totalWeight=eligible.reduce((sum,c)=>sum+(optimal?c.allocation.priority:c.selected_score),0)||1;
 const batch=Math.min(balances(w,quotes).available_try,balances(w,quotes).budget_room_try);
 for(const c of eligible){
  const reason=availablePair(w,c.pair,now);if(reason){decision(w,c.pair,reason,now);continue;}
  const q=quotes[c.pair];if(!q||now-q.observed_at>(c.max_age_ms??(optimal?90000:20*60000))||q.observed_at>now+1000){decision(w,c.pair,'PRICE_NOT_CURRENT',now);continue;}
  // Optimal uses the validation-selected research policy; manual price overrides cannot change it.
  const entryMode=optimal?'LIMIT':s.entry_mode,entry=entryMode==='MARKET'?q.price:(optimal?c.entry:(s.entry_prices[c.pair]||c.entry));
  const target=optimal||s.target_pct===null?entry*(c.target/c.entry):entry*(1+s.target_pct/100);
  const stop=optimal||s.stop_pct===null?entry*(c.stop/c.entry):entry*(1-s.stop_pct/100);
  if(!(stop>0&&stop<entry&&target>entry&&entry<=q.price*1.001)){decision(w,c.pair,'INVALID_PLAN',now);continue;}
  const {cap}=orderCap(w,quotes,c.pair,entry,stop,optimal?c.allocation.risk_scale:1);
  const amount=!optimal&&s.amount_mode==='FIXED'?s.fixed_try:Math.min(cap,batch*(optimal?c.allocation.priority:c.selected_score)/totalWeight);
  if(amount<10||amount>cap+.0001){decision(w,c.pair,'BUDGET_OR_RISK_LIMIT',now);continue;}
  createOrder(w,{pair:c.pair,entry,target,stop,amount,entry_mode:entryMode,quote:q,trace:{execution_mode:optimal?'OPTIMAL':'MANUAL',score:c.selected_score,base_score:c.base_score??c.score,research_adjustment:c.research?.research_adjustment||0,score_source:source,model_id:c.research?.model_id||c.model_id,cloud_risk_scale:optimal?c.allocation.risk_scale:1,score_at:c.as_of,score_inputs_at:c.inputs_at||null,indicator_adjustment:c.indicators?.adjustment||0,allocation:optimal?c.allocation:null}},now);
 }
 if(optimal&&!eligible.length)decision(w,'TRY','WAIT_IN_CASH',now);
 w.last_cycle_at=now;
}
export function invalidatePending(w,candidates,now=Date.now()){
 const s=w.settings;
 if(s.execution_mode!=='OPTIMAL')return;
 for(const o of w.orders.filter(o=>o.state==='WAITING_ENTRY'&&o.execution_mode==='OPTIMAL')){
  // Replay all trades first. Never retrospectively cancel a fill that has already occurred.
  if(o.tracking_error||now-(o.verified_until??0)>90000)continue;
  const c=candidates.find(c=>c.pair===o.pair),valid=c?.live_valid===true&&now-c.as_of<=90000&&(c.final_score??c.score)>=s.threshold&&optimalOpportunity(c,now).eligible;
  if(!valid){o.state='CANCELLED';o.closed_at=o.verified_until;o.cancel_reason='LIVE_SIGNAL_INVALIDATED';decision(w,o.pair,'LIVE_SIGNAL_INVALIDATED',now);}
 }
}
export function settle(w,o,price,time,state){
 if(o.state!=='TRACKING')throw Error('Aktif pozisyon gerekli');
 const proceeds=Math.max(0,Math.floor(o.quantity*price*.999*100));w.cash_cents+=proceeds;
 o.state=state;o.exit=price;o.closed_at=time;o.net_pnl_cents=proceeds-o.amount_cents;o.net_return_pct=100*o.net_pnl_cents/o.amount_cents;
}
export function applyTrades(w,o,rows,until){
 if(!Array.isArray(rows))throw Error('İşlem dizisi gerekli');
 let prevId=o.last_trade_id,prevTime=o.observed_at;
 // Validate the entire response before changing cash, so malformed batches never partially settle.
 const checked=rows.map(r=>({id:Number(r.a),price:Number(r.p),qty:Number(r.q),time:Number(r.T)}));
 for(const t of checked){if(!Number.isSafeInteger(t.id)||!Number.isSafeInteger(t.time)||!valid(t.price,1e-12,1e12)||!valid(t.qty,0,1e15)||t.time<prevTime||t.time>until||(prevId!==null&&t.id!==prevId+1))throw Error('İşlem sırası veya kapsamı doğrulanamadı');prevId=t.id;prevTime=t.time;}
 for(const t of checked){
  if(t.time<o.created_at)throw Error('Karar öncesi işlem');
  if(!active(o))break;
  o.last_trade_id=t.id;o.cursor_time=t.time;o.observed_at=t.time;o.observed_price=t.price;
  if(o.state==='WAITING_ENTRY'){
   if(t.time>=o.entry_expires_at){o.state='EXPIRED_UNFILLED';o.closed_at=o.entry_expires_at;continue;}
   if(t.price<=o.entry){o.quantity=o.amount_cents/100*.999/o.entry;w.cash_cents-=o.amount_cents;o.state='TRACKING';o.filled_at=t.time;o.deadline_at=t.time+48*3600000;}
   // An observed gap through stop settles pessimistically on the same verified trade.
   if(o.state==='TRACKING'&&t.price<=o.stop)settle(w,o,Math.min(t.price,o.stop)*.999,t.time,'STOPPED');
  }else if(t.time>=o.deadline_at)settle(w,o,t.price*.999,t.time,'TIME_EXPIRED');
  else if(t.price<=o.stop)settle(w,o,Math.min(t.price,o.stop)*.999,t.time,'STOPPED');
  else if(t.price>=o.target)settle(w,o,o.target,t.time,'SUCCESS');
 }
 o.tracking_error=null;
}
export function cancel(w,id){const o=w.orders.find(x=>x.id===id);if(!o||o.state!=='WAITING_ENTRY')throw Error('Bekleyen limit emir gerekli');o.state='CANCELLED';o.closed_at=Date.now();}
export function viewWallet(w,quotes,now=Date.now()){const dayStart=Math.floor((now+10800000)/86400000)*86400000-10800000;return {...w,settings:{...DEFAULTS,...w.settings},daily:{timezone:'Europe/Istanbul',since:dayStart,realized_pnl_try:money(w.orders.filter(o=>o.closed_at>=dayStart&&o.closed_at<=now).reduce((sum,o)=>sum+(o.net_pnl_cents||0),0)),closed_trades:w.orders.filter(o=>o.closed_at>=dayStart&&o.net_pnl_cents!==null).length},balances:balances(w,quotes),orders:w.orders.map(o=>({...o,amount_try:money(o.amount_cents),net_pnl_try:o.net_pnl_cents===null?null:money(o.net_pnl_cents)}))};}
