// A host-wide breaker respects exchange throttling; it never changes the venue.
const failures=new Map(),inflight=new Map();
export function resetSourceBreakers(){failures.clear();inflight.clear();}
export async function getJSON(url){
 const host=new URL(url).host,now=Date.now(),c=failures.get(host);
 if(c?.until>now)throw Object.assign(Error('Kaynak beklemede; yeniden deneme '+Math.ceil((c.until-now)/1000)+' sn'),{status:503,retry_after_ms:c.until-now});
 if(inflight.has(url))return inflight.get(url);
 const promise=(async()=>{try{
  const r=await fetch(url,{signal:AbortSignal.timeout(12000),redirect:'error'});
  if(!r.ok){const retry=r.headers.get('retry-after'),seconds=Number(retry),until=Number.isFinite(seconds)&&seconds>0?Date.now()+seconds*1000:Date.parse(retry);const e=Object.assign(Error('Kaynak HTTP '+r.status),{status:r.status,retryAt:until});throw e;}
  const j=await r.json();if(j&&typeof j==='object'&&!Array.isArray(j)&&Object.hasOwn(j,'code')&&j.code!==0)throw Error('Kaynak API kodu '+j.code);
  failures.delete(host);return j?.data??j;
 }catch(e){const previous=failures.get(host)||{count:0,until:0};previous.count++;if([418,429].includes(e.status)||previous.count>=3)previous.until=Math.max(previous.until,Number.isFinite(e.retryAt)?e.retryAt:Date.now()+Math.min(300000,15000*2**Math.min(previous.count,4)));failures.set(host,previous);throw e;}})();
 inflight.set(url,promise);try{return await promise;}finally{inflight.delete(url);}
}
