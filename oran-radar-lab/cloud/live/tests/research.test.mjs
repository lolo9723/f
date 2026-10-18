import {test} from 'node:test';import assert from 'node:assert/strict';import {execFileSync} from 'node:child_process';
import {historyFeature,policyPlan,fitResearch,researchDecision,POLICIES,stats} from '../src/research.mjs';
import {freshWallet,settings,reserve} from '../src/wallet.mjs';
import {rally} from '../src/rally.mjs';
function bars(n=4000){let p=100;return Array.from({length:n},(_,i)=>{const open=p;p*=1+.001*Math.sin(i*.21)+.0007*Math.cos(i*.037);return {time:i*3600000,open,close:p,high:Math.max(p,open)*1.003,low:Math.min(p,open)*.997,quote_volume:1000+200*Math.sin(i),volume:10};});}
const fixture=bars();
test('features do not change when future candles change',()=>{const a=historyFeature(fixture,100),other=structuredClone(fixture);other[101].close*=4;assert.deepEqual(historyFeature(other,100),a);});
test('training/validation label windows are purged and test never selects policy',()=>{const r=fitResearch(fixture,'TESTTRY');assert(r.training_n>40);assert(r.test_n>20);assert.equal(r.purge_hours,54);assert(r.learning.every(x=>x.as_of+54*3600000<r.test_start));assert.equal(r.calibrated,false);});
test('research contribution is dynamic, bounded and stale-safe',()=>{const learning=Array.from({length:40},(_,i)=>({vector:Array(8).fill(0),net:.02+i*.0001}));const m={model_id:'test-only',trained_at:1000000,holdout:{effective_n:20,interval:[.01,.03]},learning,scale:Array(8).fill(1),selected_policy:'atr-patient'};
 const feature={vector:Array(8).fill(0),price:100,atr:2};const a=researchDecision(m,feature,68,1000001);assert(a.final_score>68);assert(a.research_adjustment<=20);assert.equal(a.probability,null);assert(a.entry<100&&a.stop<a.entry&&a.target>a.entry);
 assert.equal(researchDecision(m,feature,68,1000000+25*3600000).research_adjustment,0);
 assert.equal(researchDecision({...m,holdout:{effective_n:2}},feature,68,1000001).research_adjustment,0);
});
test('BASE and FINAL actually change the wallet eligibility decision',()=>{const now=Date.now(),c={pair:'TESTTRY',score:72,base_score:68,final_score:72,research:{research_adjustment:4},entry:100,stop:98,target:104,as_of:now};const q={TESTTRY:{price:100,observed_at:now}};
 const w=freshWallet(1000);w.settings=settings({enabled:true,threshold:70,score_source:'BASE'});reserve(w,[c],q,now,c.pair);assert.equal(w.orders.length,0);w.settings.score_source='FINAL';reserve(w,[c],q,now,c.pair);assert.equal(w.orders.length,1);assert.equal(w.orders[0].score,72);assert.equal(w.orders[0].research_adjustment,4);
});
test('Python and cloud agree on original score, policy plan and historical contribution',()=>{
 const hourly=fixture.slice(-120),minute=fixture.slice(-120).map((x,i)=>({...x,time:i*60000}));const book={bids:[[hourly.at(-1).close*.999,2]],asks:[[hourly.at(-1).close*1.001,1]]};const base=rally(hourly,minute,book,.012);
 const feature={price:100,atr:2,vector:Array(8).fill(0)},returns=Array.from({length:40},(_,i)=>.005*Math.sin(i)+.01),holdout={effective_n:12,interval:[-.002,.02]};const model={model_id:'fixture',trained_at:1000000,holdout,learning:returns.map(net=>({net,vector:feature.vector})),scale:Array(8).fill(1),selected_policy:'atr-patient'};
 const python="import sys,json;sys.path.insert(0,'../../backend_next');sys.path.insert(0,'../src');from radar.rally import score_market;from oran_cloud.dynamic import policy_plan,historical_adjustment;x=json.load(sys.stdin);print(json.dumps({'score':score_market(x['h'],x['m'],x['book'],.012)['score'],'plan':policy_plan(100,2,x['policy']),'decision':historical_adjustment(x['base'],x['returns'],x['holdout'])}))";
 const p=JSON.parse(execFileSync('python',['-c',python],{input:JSON.stringify({h:hourly,m:minute,book,policy:POLICIES[2],base:base.score,returns,holdout}),encoding:'utf8'}));
 assert.equal(p.score,base.score);const d=researchDecision(model,feature,base.score,1000001);assert.equal(p.decision.research_adjustment,d.research_adjustment);assert.equal(p.decision.final_score,d.final_score);assert(Math.abs(p.plan.entry-d.entry)<1e-10);
});
