// Same six descriptive weights as backend_next/radar/rally.py. This is not a probability.
export const WEIGHTS={trend:.25,early:.17,volume:.21,compression:.14,flow:.16,breadth:.07};
const clamp=x=>Math.max(0,Math.min(1,x));
const mean=a=>a.reduce((s,x)=>s+x,0)/a.length;
export function validateCandles(rows,interval,now=Date.now(),requireRecent=true){
 if(!Array.isArray(rows))throw Error('Mum dizisi gerekli');
 const out=[];let previous=-1;
 for(const r of rows){
  const [time,open,high,low,close,volume,end,quote]=r.map(Number);
  if(![time,open,high,low,close,volume,end,quote].every(Number.isFinite)||time%interval!==0||time<=previous||end!==time+interval-1||Math.min(open,high,low,close)<=0||high<Math.max(open,low,close)||low>Math.min(open,close)||volume<0||quote<0)throw Error('Mum sağlamlığı doğrulanamadı');
  previous=time;if(end>=now)continue;
  if(out.length&&out.at(-1).time+interval!==time)throw Error('Mum serisinde boşluk');
  out.push({time,open,high,low,close,volume,quote_volume:quote});
 }
 if(requireRecent&&out.length&&out.at(-1).time+2*interval<=now)throw Error('Son kapalı mum güncel değil');
 return out;
}
export function rally(hour,minute,book,btcChange=0,livePrice=null){
 if(hour.length<80||minute.length<60)throw Error('Puan için yeterli kapalı mum yok');
 const c=hour.map(b=>b.close),v=minute.map(b=>b.quote_volume),p=livePrice??c.at(-1);
 if(!Number.isFinite(p)||p<=0)throw Error('Güncel fiyat geçersiz');
 if(!book.bids?.length||!book.asks?.length)throw Error('Emir defteri boş');
 const b=book.bids.slice(0,10).map(r=>r.map(Number)),a=book.asks.slice(0,10).map(r=>r.map(Number));
 if([...b,...a].some(([p,q])=>!Number.isFinite(p)||!Number.isFinite(q)||p<=0||q<0))throw Error('Geçersiz derinlik');
 const spread=(a[0][0]-b[0][0])/p;if(spread<0||!Number.isFinite(btcChange))throw Error('Piyasa bağlamı geçersiz');
 const ranges=hour.map(b=>(b.high-b.low)/b.close),qvol=mean(v.slice(-5))/Math.max(1,mean(v.slice(-60,-5)));
 const bid=b.reduce((s,[p,q])=>s+p*q,0),ask=a.reduce((s,[p,q])=>s+p*q,0),imbalance=bid/Math.max(1,bid+ask);
 const parts={trend:clamp(.5+(p/mean(c.slice(-24))-1)*22),early:clamp(.7-Math.max(0,p/Math.max(...c.slice(-48))-1)*12),volume:clamp((qvol-.6)/2.2),compression:clamp(1-mean(ranges.slice(-8))/Math.max(1e-9,mean(ranges.slice(-72,-8)))),flow:clamp(.5+(imbalance-.5)*1.6),breadth:clamp(.5+btcChange*15)};
 let atr=mean(hour.slice(-14).map((b,i)=>Math.max(b.high-b.low,Math.abs(b.high-hour.at(-15+i).close),Math.abs(b.low-hour.at(-15+i).close))));
 const entry=p-Math.max(.001*p,.35*atr),stop=entry-Math.max(.01*entry,1.5*atr),target=entry+Math.max(.03*entry,2*atr);
 if(stop<=0)throw Error('Plan seviyeleri geçersiz');
 const raw=100*Object.entries(parts).reduce((s,[k,v])=>s+v*WEIGHTS[k],0);
 return {score:livePrice===null?Math.round(raw):Math.round(raw*10)/10,components:Object.fromEntries(Object.entries(parts).map(([k,v])=>[k,v*100])),spread_pct:spread*100,volume_ratio:qvol,entry,target,stop,atr,price:p,as_of:minute.at(-1).time+60000,method:livePrice===null?'original-rally-v0.1':'live-rally-v1',probability:null};
}
