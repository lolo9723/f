// Causal, chronological historical research. An adjustment is an index rule, not a win probability.
import {createHash} from 'node:crypto';
export const RESEARCH_VERSION='tr-dynamic-research-v1';
export const POLICIES=[{id:'atr-early',entry_atr:.15,stop_atr:1.25,target_atr:2},{id:'atr-base',entry_atr:.35,stop_atr:1.5,target_atr:2},{id:'atr-patient',entry_atr:.6,stop_atr:1.8,target_atr:2.5},{id:'pct-reference',discount:.003,stop_fraction:.02,target_fraction:.03}];
const H=3600000,mean=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0,sd=a=>Math.sqrt(mean(a.map(x=>(x-mean(a))**2))),clip=(x,a,b)=>Math.max(a,Math.min(b,x));
export function historyFeature(bars,i=bars.length-1){
 if(i<72)throw Error('Özellik için 73 saatlik mum gerekli');
 const b=bars[i],past=bars.slice(i-23,i+1),before=bars.slice(i-24,i),c=bars.map(b=>b.close);
 const atr=mean(bars.slice(i-13,i+1).map((x,k)=>Math.max(x.high-x.low,Math.abs(x.high-bars[i-14+k].close),Math.abs(x.low-bars[i-14+k].close))));
 const returns=past.slice(1).map((x,k)=>Math.log(x.close/past[k].close));
 const vector=[c[i]/c[i-1]-1,c[i]/c[i-3]-1,c[i]/c[i-6]-1,c[i]/c[i-24]-1,b.quote_volume/Math.max(1,mean(before.map(x=>x.quote_volume))),sd(returns),b.close/mean(past.map(x=>x.close))-1,atr/b.close];
 if(vector.some(x=>!Number.isFinite(x)))throw Error('Özellik sayısal değil');
 return {as_of:b.time+H,price:b.close,atr,vector};
}
export function policyPlan(feature,policy){
 const entry=feature.price*(1-(policy.discount??clip(policy.entry_atr*feature.atr/feature.price,.001,.025)));
 const stop=entry*(1-(policy.stop_fraction??clip(policy.stop_atr*feature.atr/entry,.01,.08)));
 const target=entry*(1+(policy.target_fraction??clip(policy.target_atr*feature.atr/entry,.03,.15)));
 return {entry,stop,target,policy_id:policy.id};
}
export function simulate(bars,index,policy){
 const feature=historyFeature(bars,index),p=policyPlan(feature,policy),future=bars.slice(index+1,index+55);let filled=false,deadline=0;
 if(future.length<54)return {status:'pending',net:null};
 for(let j=0;j<future.length;j++){
  const b=future[j];if(b.time!==feature.as_of+j*H)return {status:'gap',net:null};
  if(!filled){if(j>=6)return {status:'unfilled',net:0};if(b.open<=p.entry||b.low<p.entry){filled=true;deadline=j+48;
    if((b.low<=p.stop&&b.high>=p.target)||(b.open>p.entry&&b.high>=p.target))return {status:'ambiguous',net:null};
   }else continue;
  }
  if(b.low<=p.stop&&b.high>=p.target)return {status:'ambiguous',net:null};
  if(b.low<=p.stop)return {status:'stop',net:Math.min(b.open,p.stop)*.998001/(p.entry*1.001)-1};
  if(b.high>=p.target)return {status:'target',net:p.target*.999/(p.entry*1.001)-1};
  if(j+1>=deadline)return {status:'timeout',net:b.close*.998001/(p.entry*1.001)-1};
 }
 return {status:'pending',net:null};
}
export function stats(rows,key){
 const values=rows.map(r=>r.results[key]?.net).filter(x=>x!==null&&Number.isFinite(x));
 // 6-hour event spacing still overlaps 54-hour labels. Downweight by the full label window.
 const effective_n=Math.min(values.length,values.length/9),m=mean(values),se=values.length?sd(values)/Math.sqrt(Math.max(1,effective_n)):null;
 return {n:values.length,effective_n,mean:values.length?m:null,se,interval:values.length?[m-1.96*se,m+1.96*se]:[null,null],excluded:rows.length-values.length};
}
export function fitResearch(bars,pair){
 const rows=[];
 for(let i=72;i<bars.length-54;i+=6){const feature=historyFeature(bars,i);rows.push({...feature,label_end:feature.as_of+54*H,results:Object.fromEntries(POLICIES.map(p=>[p.id,simulate(bars,i,p)]))});}
 if(rows.length<150)return {pair,status:'insufficient_history',trained_at:Date.now(),rows:rows.length,version:RESEARCH_VERSION};
 const v0=Math.floor(rows.length*.6),t0=Math.floor(rows.length*.8),validationStart=rows[v0].as_of,testStart=rows[t0].as_of;
 const train=rows.slice(0,v0).filter(r=>r.label_end<validationStart),validation=rows.slice(v0,t0).filter(r=>r.label_end<testStart),test=rows.slice(t0);
 const comparisons=POLICIES.map(p=>({policy_id:p.id,validation:stats(validation,p.id)})).filter(p=>p.validation.n>=15);
 if(train.length<40||!comparisons.length)return {pair,status:'insufficient_separated_data',trained_at:Date.now(),rows:rows.length,version:RESEARCH_VERSION};
 // Select only on validation. The final test never selects the policy.
 comparisons.sort((a,b)=>(b.validation.mean-.5*b.validation.se)-(a.validation.mean-.5*a.validation.se));
 const selected=comparisons[0].policy_id,holdout=stats(test,selected),baseline=stats(test,'atr-base');
 const learning=rows.filter(r=>r.label_end<testStart).map(r=>({as_of:r.as_of,vector:r.vector,net:r.results[selected].net})).filter(r=>r.net!==null);
 const vectors=train.map(r=>r.vector),center=feature=>mean(vectors.map(x=>x[feature])),scale=feature=>Math.max(1e-6,sd(vectors.map(x=>x[feature])));
 const trainedAt=bars.at(-1).time+H,model_id=RESEARCH_VERSION+'-'+createHash('sha256').update(pair+':'+trainedAt+':'+selected).digest('hex').slice(0,16);
 return {pair,version:RESEARCH_VERSION,model_id,status:holdout.effective_n>=8?'historical_candidate':'limited_history',trained_at:trainedAt,selected_policy:selected,holdout,baseline,comparisons,training_n:train.length,validation_n:validation.length,test_n:test.length,test_start:testStart,purge_hours:54,feature_names:['return_1','return_3','return_6','return_24','volume_ratio','volatility_24','ma_distance','atr_fraction'],center:Array.from({length:8},(_,i)=>center(i)),scale:Array.from({length:8},(_,i)=>scale(i)),learning,calibrated:false,forward_trades:0};
}
export function researchDecision(model,feature,baseScore,now=Date.now()){
 const neutral={base_score:baseScore,research_adjustment:0,final_score:baseScore,status:'waiting',reason:'Tarihsel araştırma hazırlanıyor',policy_id:'atr-base',historical_net:null,uncertainty:null,samples:0,effective_samples:0,model_id:null,probability:null};
 if(!model?.model_id||!model.learning?.length)return neutral;
 if(now-model.trained_at>24*H||model.trained_at>now)return {...neutral,status:'stale',reason:'Araştırma güncel değil',model_id:model.model_id};
 if(model.holdout.effective_n<8)return {...neutral,reason:'Ayrı testte etkin örnek sayısı yetersiz',model_id:model.model_id};
 const neighbors=model.learning.map(r=>({...r,distance:Math.sqrt(mean(r.vector.map((x,i)=>((x-feature.vector[i])/model.scale[i])**2)))})).sort((a,b)=>a.distance-b.distance).slice(0,40);
 if(neighbors.length<20||neighbors[0].distance>4)return {...neutral,reason:'Bu piyasa koşuluna yeterli benzer örüntü yok',model_id:model.model_id};
 const values=neighbors.map(r=>r.net),m=mean(values),effective=Math.min(model.holdout.effective_n,values.length/9),se=sd(values)/Math.sqrt(Math.max(1,effective));
 const reliability=effective/(effective+16),interval=[m-1.96*se,m+1.96*se];
 // Limited evidence can move the descriptive index by at most 4; stronger historical evidence by 12.
 // No calibration or forward success is claimed. Automatic real-money orders do not exist.
 const strong=(interval[0]>0&&model.holdout.interval[0]>0)||(interval[1]<0&&model.holdout.interval[1]<0),cap=strong?12:4;
 const adjustment=Math.round(clip(12*reliability*Math.tanh(m/Math.max(.01,2*se)),-cap,cap)*10)/10;
 const policy=POLICIES.find(p=>p.id===model.selected_policy)||POLICIES[1],plan=policyPlan(feature,policy);
 return {...neutral,...plan,base_score:baseScore,research_adjustment:adjustment,final_score:clip(Math.round((baseScore+adjustment)*10)/10,0,100),status:strong?'historical_support':'limited_historical_evidence',reason:strong?'Ayrı test ve benzer geçmiş koşullar aynı yönde':'Sınırlı tarihsel kanıt; katkı en fazla ±4 puan',historical_net:m,uncertainty:interval,samples:neighbors.length,effective_samples:effective,model_id:model.model_id,holdout:model.holdout,selected_policy:model.selected_policy,calibrated:false};
}
