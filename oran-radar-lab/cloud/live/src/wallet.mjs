import {randomUUID} from 'node:crypto';
export const DEFAULTS={enabled:false,threshold:70,score_source:'FINAL',amount_mode:'AUTO',entry_mode:'MARKET',fixed_try:1000,coin_cap_try:1000,total_cap_try:null,risk_pct:1,portfolio_risk_pct:3,max_coin_pct:30,max_positions:5,cooldown_hours:12,target_pct:null,stop_pct:null,entry_prices:{}};
export const active=o=>['WAITING_ENTRY','TRACKING'].includes(o.state);
const cents=x=>Math.round(x*100);
const money=x=>x/100;
const valid=(x,min,max)=>typeof x==='number'&&Number.isFinite(x)&&x>=min&&x<=max;
export function settings(changes,previous=DEFAULTS){
 if(!changes||typeof changes!=='object'||Array.isArray(changes)||Object.keys(changes).some(k=>!Object.hasOwn(DEFAULTS,k)))throw Error('Bilinmeyen otomasyon ayarı');
 const s={...previous,...changes};
 if(typeof s.enabled!=='boolean'||![50,60,70,80,90,100].includes(s.threshold)||!['BASE','FINAL'].includes(s.score_source)||!['AUTO','FIXED'].includes(s.amount_mode)||!['MARKET','LIMIT'].includes(s.entry_mode))throw Error('Geçersiz otomasyon seçimi');
 for(const k of ['fixed_try','coin_cap_try'])if(!valid(s[k],.01,1e9))throw Error('Pozitif TL tutarı gerekli: '+k);
 if(s.total_cap_try!==null&&!valid(s.total_cap_try,.01,1e9))throw Error('Toplam bütçe geçersiz');
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
  else {invested+=o.amount_cents;const price=quotes[o.pair]?.price||o.observed_price||o.entry;const value=cents(o.quantity*price*.999);market+=value;unrealized+=value-o.amount_cents;}
 }
 const equity=w.cash_cents+market,available=w.cash_cents-reserved;
 return {initial_try:money(w.initial_cents),cash_try:money(w.cash_cents),reserved_try:money(reserved),invested_try:money(invested),available_try:money(available),equity_try:money(equity),realized_pnl_try:money(w.cash_cents+invested-w.initial_cents),unrealized_pnl_try:money(unrealized),planned_risk_try:money(risk),valuation:'LAST_VERIFIED_QUOTE_OR_TRADE',budget_room_try:w.settings.total_cap_try===null?money(available):Math.max(0,w.settings.total_cap_try-money(invested+reserved))};
}
function decision(w,pair,reason,now){w.decisions.push({pair,reason,at:now});w.decisions=w.decisions.slice(-200);}
export function reserve(w,candidates,quotes,now=Date.now(),onlyPair=null){
 if(!w.settings.enabled&&!onlyPair)return;
 const s=w.settings;
 const eligible=[...new Map(candidates.map(c=>[c.pair,c])).values()].map(c=>({...c,selected_score:s.score_source==='BASE'?(c.base_score??c.score):(c.final_score??c.score)})).filter(c=>(!onlyPair||c.pair===onlyPair)&&c.selected_score>=s.threshold&&now-c.as_of<20*60000).sort((a,b)=>b.selected_score-a.selected_score);
 const totalWeight=eligible.reduce((sum,c)=>sum+c.selected_score,0)||1;
 const batch=balances(w,quotes).available_try;
 for(const c of eligible){
  if(w.orders.filter(active).length>=s.max_positions){decision(w,c.pair,'MAX_POSITIONS_REACHED',now);break;}
  if(w.orders.some(o=>o.pair===c.pair&&(active(o)||now-o.created_at<s.cooldown_hours*3600000))){decision(w,c.pair,'COOLDOWN_OR_ACTIVE',now);continue;}
  const q=quotes[c.pair];if(!q||now-q.observed_at>20*60000){decision(w,c.pair,'PRICE_NOT_CURRENT',now);continue;}
  const entry=s.entry_mode==='MARKET'?q.price:(s.entry_prices[c.pair]||c.entry);
  const target=s.target_pct===null?entry*(c.target/c.entry):entry*(1+s.target_pct/100);
  const stop=s.stop_pct===null?entry*(c.stop/c.entry):entry*(1-s.stop_pct/100);
  if(!(stop>0&&stop<entry&&target>entry)){decision(w,c.pair,'INVALID_PLAN',now);continue;}
  const b=balances(w,quotes),loss=(entry-stop)/entry+.003,coinUsed=w.orders.filter(o=>o.pair===c.pair&&active(o)).reduce((sum,o)=>sum+o.amount_cents,0)/100;
  const cap=Math.max(0,Math.min(b.available_try,b.budget_room_try,s.coin_cap_try-coinUsed,b.equity_try*s.max_coin_pct/100-coinUsed,b.equity_try*s.risk_pct/100/loss,(b.equity_try*s.portfolio_risk_pct/100-b.planned_risk_try)/loss));
  const amount=s.amount_mode==='FIXED'?s.fixed_try:Math.min(cap,batch*c.selected_score/totalWeight);
  if(amount<10||amount>cap+.0001){decision(w,c.pair,'BUDGET_OR_RISK_LIMIT',now);continue;}
  const amountCents=Math.floor(amount*100),id=randomUUID();
  const o={id,pair:c.pair,state:s.entry_mode==='LIMIT'?'WAITING_ENTRY':'TRACKING',entry_mode:s.entry_mode,entry,target,stop,score:c.selected_score,base_score:c.base_score??c.score,research_adjustment:c.research?.research_adjustment||0,score_source:s.score_source,model_id:c.research?.model_id||c.model_id,amount_cents:amountCents,risk_cents:Math.ceil(amountCents*loss),quantity:0,created_at:now,filled_at:null,closed_at:null,entry_expires_at:now+6*3600000,deadline_at:null,net_pnl_cents:null,last_trade_id:null,cursor_time:now,observed_at:now,observed_price:q.price,tracking_error:null};
  if(o.state==='TRACKING'){o.quantity=amountCents/100*.999/entry;o.filled_at=now;o.deadline_at=now+48*3600000;w.cash_cents-=amountCents;}
  w.orders.push(o);decision(w,c.pair,'ORDER_CREATED',now);
 }
 w.last_cycle_at=now;
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
export function viewWallet(w,quotes){return {...w,balances:balances(w,quotes),orders:w.orders.map(o=>({...o,amount_try:money(o.amount_cents),net_pnl_try:o.net_pnl_cents===null?null:money(o.net_pnl_cents)}))};}
