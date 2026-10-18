// Correlated price indicators share a bounded group, rather than independent votes.
const clip=(v,a=-1,b=1)=>Math.max(a,Math.min(b,v));
const mean=a=>a.reduce((s,x)=>s+x,0)/a.length;
export function emaSeries(values,period){let e=0;return values.map((v,i)=>{if(i<period-1)return null;if(i===period-1)e=mean(values.slice(0,period));else e=v*2/(period+1)+e*(1-2/(period+1));return e;});}
export function rsi(values,period=14){let gain=0,loss=0;for(let i=1;i<values.length;i++){const d=values[i]-values[i-1];if(i<=period){gain+=Math.max(0,d)/period;loss+=Math.max(0,-d)/period;}else {gain=(gain*(period-1)+Math.max(0,d))/period;loss=(loss*(period-1)+Math.max(0,-d))/period;}}return loss===0?(gain===0?50:100):100-100/(1+gain/loss);}
export function adx(bars,period=14){
 let tr=0,plus=0,minus=0,dx=[],result=null;
 for(let i=1;i<bars.length;i++){
  const b=bars[i],p=bars[i-1],up=b.high-p.high,down=p.low-b.low,t=Math.max(b.high-b.low,Math.abs(b.high-p.close),Math.abs(b.low-p.close)),pd=up>down&&up>0?up:0,md=down>up&&down>0?down:0;
  if(i<=period){tr+=t;plus+=pd;minus+=md;}else{tr=tr-tr/period+t;plus=plus-plus/period+pd;minus=minus-minus/period+md;}
  if(i>=period){const d=plus+minus>0?100*Math.abs(plus-minus)/(plus+minus):0;dx.push(d);if(dx.length===period)result=mean(dx);else if(dx.length>period)result=(result*(period-1)+d)/period;}
 }
 return result??0;
}
export function indicatorDecision(minute,livePrice,spreadPct=0,context={}){
 if(minute.length<80||!(livePrice>0))throw Error('İndikatör için en az 80 kapalı dakika gerekli');
 const closed=minute.map(b=>b.close),marked=[...closed,livePrice],last=minute.at(-1),atr=mean(minute.slice(-14).map((b,i)=>Math.max(b.high-b.low,Math.abs(b.high-minute.at(-15+i).close),Math.abs(b.low-minute.at(-15+i).close))));
 const unit=Math.max(atr,livePrice*.00001),ema20=emaSeries(marked,20).at(-1),ema50=emaSeries(marked,50).at(-1),e12=emaSeries(marked,12),e26=emaSeries(marked,26),macds=e26.flatMap((v,i)=>v===null?[]:[e12[i]-v]);
 const macd=macds.at(-1),macdSignal=emaSeries(macds,9).at(-1),hist=macd-macdSignal,rsi14=rsi(marked),volume60=minute.slice(-60).reduce((s,b)=>s+b.volume,0);
 const vwap=volume60>0?minute.slice(-60).reduce((s,b)=>s+(b.high+b.low+b.close)/3*b.volume,0)/volume60:last.close;
 const v=minute.map(b=>b.quote_volume),volumeRatio=mean(v.slice(-5))/Math.max(1,mean(v.slice(-60,-5))),recent=minute.slice(-20),totalVolume=recent.reduce((s,b)=>s+b.volume,0);
 const obvFlow=recent.reduce((s,b,i)=>s+Math.sign(b.close-(i?recent[i-1].close:minute.at(-21).close))*b.volume,0)/Math.max(1e-12,totalVolume);
 const returns={one_minute:livePrice/closed.at(-1)-1,five_minutes:livePrice/closed.at(-5)-1,fifteen_minutes:livePrice/closed.at(-15)-1};
 const window=marked.slice(-20),middle=mean(window),deviation=Math.sqrt(mean(window.map(x=>(x-middle)**2))),bollingerPercentB=deviation>0?(livePrice-(middle-2*deviation))/(4*deviation):.5;
 const rsiWindow=Array.from({length:14},(_,i)=>rsi(marked.slice(0,marked.length-13+i))),rsiRange=Math.max(...rsiWindow)-Math.min(...rsiWindow),stoch=rsiRange>0?(rsi14-Math.min(...rsiWindow))/rsiRange:.5;
 const typical=minute.map(b=>(b.high+b.low+b.close)/3),typical20=typical.slice(-20),typicalMean=mean(typical20),typicalDeviation=mean(typical20.map(x=>Math.abs(x-typicalMean))),cci=typicalDeviation>0?(livePrice-typicalMean)/(.015*typicalDeviation):0;
 const cmf=recent.reduce((s,b)=>s+(b.high>b.low?(2*b.close-b.high-b.low)/(b.high-b.low):0)*b.volume,0)/Math.max(1e-12,totalVolume);
 let positiveFlow=0,negativeFlow=0;for(let i=minute.length-14;i<minute.length;i++){const flow=typical[i]*minute[i].volume;if(typical[i]>typical[i-1])positiveFlow+=flow;else if(typical[i]<typical[i-1])negativeFlow+=flow;}
 const mfi=positiveFlow+negativeFlow>0?100*positiveFlow/(positiveFlow+negativeFlow):50,adx14=adx(minute),donchianHigh=Math.max(...recent.map(b=>b.high)),donchianLow=Math.min(...recent.map(b=>b.low)),donchianPosition=donchianHigh>donchianLow?(livePrice-donchianLow)/(donchianHigh-donchianLow):.5;
 const last20=marked.slice(-21),path=last20.slice(1).reduce((sum,x,i)=>sum+Math.abs(x-last20[i]),0),efficiency=path>0?(livePrice-last20[0])/path:0;
 const acceleration=returns.five_minutes-(closed.at(-5)/closed.at(-10)-1),priceReturns=closed.slice(-21).slice(1).map((x,i)=>x/closed.slice(-21)[i]-1);
 const downside=Math.sqrt(mean(priceReturns.map(x=>Math.min(0,x)**2))),realized=Math.sqrt(mean(priceReturns.map(x=>x*x)));
 const range14=minute.slice(-14),high14=Math.max(...range14.map(b=>b.high)),low14=Math.min(...range14.map(b=>b.low)),williams=high14>low14?-100*(high14-livePrice)/(high14-low14):-50;
 const keltner=(livePrice-ema20)/(2*unit),relative5=Number.isFinite(context.btc_return5)?returns.five_minutes-context.btc_return5:0;
 const breadth=Number.isFinite(context.breadth)?context.breadth:.5,bookImbalance=Number.isFinite(context.depth_imbalance)?context.depth_imbalance:0;
 const trend=(Math.tanh((ema20-ema50)/unit)+Math.tanh(hist/(unit*.25))+clip((donchianPosition-.5)*2)+efficiency)/4;
 const directional=clip((Math.tanh((rsi14-50)/20)+Math.tanh(returns.five_minutes/(unit/livePrice*Math.sqrt(5)))+Math.tanh(acceleration/(unit/livePrice*Math.sqrt(5))))/3-Math.max(0,rsi14-80)/50);
 const reversion=clip((Math.tanh((50-rsi14)/20)+(1-2*stoch)+clip(1-2*bollingerPercentB)+Math.tanh(-cci/150)+clip(-1-williams/50))/5);
 const momentum=adx14>=25?directional:reversion;
 const volume=clip((Math.tanh((livePrice-vwap)/(unit*2))+obvFlow+cmf+(mfi-50)/50)/4)*clip(volumeRatio,0,1.5)/1.5;
 const risk=clip(Math.max(0,atr/livePrice-.01)/.02+Math.max(0,spreadPct-.1)/1.4+Math.max(0,downside-.003)/.01+Math.max(0,keltner-2)/4,0,1);
 const marketContext=clip((Math.tanh(relative5/.003)+clip((breadth-.5)*2)+bookImbalance)/3);
 const weights={trend:adx14>=25?.3:.15,momentum:adx14>=25?.2:.35,volume:.2,risk:.15,context:.15};
 const adjustment=Math.round(clip(16*(weights.trend*trend+weights.momentum*momentum+weights.volume*volume+weights.context*marketContext-weights.risk*risk),-12,12)*10)/10;
 return {version:'intraday-indicators-v2',adjustment,groups:{trend,momentum,volume,risk,context:marketContext},weights,efficiency_ratio20:efficiency,roc_acceleration5:acceleration,downside_volatility20:downside,realized_volatility20:realized,williams_r14:williams,keltner_position:keltner,relative_strength_btc5:relative5,market_breadth:breadth,depth_imbalance20:bookImbalance,adx14,regime:adx14>=25?'TREND':'RANGE',bollinger_percent_b:bollingerPercentB,bollinger_width:middle>0?4*deviation/middle:0,stoch_rsi14:stoch,cci20:cci,mfi14:mfi,cmf20:cmf,donchian_high20:donchianHigh,donchian_low20:donchianLow,donchian_position20:donchianPosition,ema20,ema50,macd,macd_signal:macdSignal,macd_histogram:hist,rsi14,vwap60:vwap,atr14:atr,atr_pct:atr/livePrice*100,obv_flow20:obvFlow,volume_ratio5_55:volumeRatio,returns,closed_minute_at:last.time+60000,price_mode:'CURRENT_QUOTE_MARK; VOLUME_AND_RANGE_CLOSED_ONLY',calibrated:false};
}
