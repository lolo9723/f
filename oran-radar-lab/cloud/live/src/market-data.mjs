export async function getJSON(url){
 const r=await fetch(url,{signal:AbortSignal.timeout(12000),redirect:'error'});
 if(!r.ok)throw Error('Kaynak HTTP '+r.status);
 const j=await r.json();if(j&&typeof j==='object'&&!Array.isArray(j)&&Object.hasOwn(j,'code')&&j.code!==0)throw Error('Kaynak API kodu '+j.code);
 return j?.data??j;
}
