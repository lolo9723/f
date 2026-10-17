// A transparent allocation heuristic. Historical returns are evidence, not a profit forecast.
export function optimalOpportunity(c,now=Date.now()){
 const r=c.research;
 if(!r?.model_id||!['historical_support','limited_historical_evidence'].includes(r.status)||!(r.samples>=20)||!(r.holdout?.effective_n>=8))return {eligible:false,reason:'RESEARCH_NOT_READY'};
 if(!Number.isFinite(r.trained_at)||r.trained_at>now||now-r.trained_at>86400000)return {eligible:false,reason:'RESEARCH_NOT_CURRENT'};
 const spread=c.spread_pct/100;
 if(!Number.isFinite(spread)||spread<0||spread>.015)return {eligible:false,reason:'SPREAD_TOO_WIDE'};
 if(!Number.isFinite(r.historical_net)||!Number.isFinite(r.holdout.mean)||r.uncertainty?.length!==2||!r.uncertainty.every(Number.isFinite))return {eligible:false,reason:'RESEARCH_NOT_READY'};
 const se=Math.max(0,(r.uncertainty[1]-r.uncertainty[0])/3.92);
 // Historical simulation already includes commission/slippage. Charge today's spread as well.
 const utility=Math.min(r.historical_net,r.holdout.mean)-.5*se-spread;
 const loss=(c.entry-c.stop)/c.entry+.003;
 if(!(utility>0&&loss>0&&Number.isFinite(loss)))return {eligible:false,reason:'NO_POSITIVE_HISTORICAL_EDGE'};
 const priority=utility/loss*Math.max(0,c.final_score??c.score)/100;
 return {eligible:true,reason:'HISTORICAL_EDGE_AND_RISK',priority,utility,spread,uncertainty_se:se};
}
