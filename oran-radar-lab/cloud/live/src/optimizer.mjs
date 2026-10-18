// Final score controls the threshold. Research affects score and position size,
// never a second hidden veto. This allocator only creates paper positions.
export function optimalOpportunity(c,now=Date.now()){
 const spread=c.spread_pct/100,loss=(c.entry-c.stop)/c.entry+.003;
 if(!Number.isFinite(spread)||spread<0||spread>.015)return {eligible:false,reason:'SPREAD_TOO_WIDE'};
 if(!(loss>0&&Number.isFinite(loss)&&c.target>c.entry&&c.stop>0))return {eligible:false,reason:'INVALID_PLAN'};
 const r=c.research||{},current=Number.isFinite(r.trained_at)&&r.trained_at<=now&&now-r.trained_at<=86400000;
 const evidence=current&&r.model_id&&Number.isFinite(r.historical_net)&&Number.isFinite(r.holdout?.mean);
 const se=evidence&&r.uncertainty?.length===2&&r.uncertainty.every(Number.isFinite)?Math.max(0,(r.uncertainty[1]-r.uncertainty[0])/3.92):0;
 const utility=evidence?Math.min(r.historical_net,r.holdout.mean)-.5*se-spread:null;
 const strength=evidence?Math.tanh(utility/.03):0;
 const riskScale=evidence?Math.max(.5,Math.min(1,.5+.5*r.holdout.effective_n/(r.holdout.effective_n+8)*(1+strength)/2)):.7;
 const score=Math.max(0,c.final_score??c.score);
 return {eligible:true,risk_scale:riskScale,reason:'FINAL_SCORE_THRESHOLD',priority:Math.max(.001,score/100)**2*(1+.2*strength)/Math.max(.01,loss),utility,spread,uncertainty_se:se,research_current:!!evidence};
}
