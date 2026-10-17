import {pool} from './db.mjs';
import {getJSON} from './engine.mjs';
import {validateCandles} from './rally.mjs';
import {fitResearch,RESEARCH_VERSION} from './research.mjs';
export async function learnPairs(pairs,maximum=2){
 const trained=[];
 for(const pair of pairs.slice(0,maximum)){
  const existing=(await pool.query('SELECT payload FROM live_models WHERE pair=$1',[pair])).rows[0]?.payload;
  if(existing&&existing.version===RESEARCH_VERSION&&Date.now()-(existing.attempted_at||existing.trained_at)<12*3600000)continue;
  try{
   const unique=new Map();let end=Date.now();
   for(let page=0;page<4;page++){
    const data=await getJSON('https://api.binance.me/api/v1/klines?'+new URLSearchParams({symbol:pair,interval:'1h',limit:'1000',endTime:String(end)}));
    const bars=validateCandles(data,3600000,Date.now(),false);if(!bars.length)break;
    bars.forEach(b=>unique.set(b.time,b));end=bars[0].time-1;if(data.length<1000)break;
   }
   const bars=[...unique.values()].sort((a,b)=>a.time-b.time);for(let i=1;i<bars.length;i++)if(bars[i].time!==bars[i-1].time+3600000)throw Error('Tarihsel saatlik seride boşluk');const model=fitResearch(bars,pair);model.attempted_at=Date.now();
   // Keep only the current model's neighbor data; previous immutable versions keep compact evidence.
   const {learning,...metadata}=model;
   await pool.query('INSERT INTO live_models VALUES($1,$2,$3,$4) ON CONFLICT(pair) DO UPDATE SET model_id=excluded.model_id,payload=excluded.payload,updated_at=excluded.updated_at',[pair,model.model_id||'pending-'+pair,JSON.stringify(model),Date.now()]);
   if(model.model_id)await pool.query('INSERT INTO live_model_versions VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING',[model.model_id,pair,Date.now(),JSON.stringify(metadata)]);
   await pool.query("INSERT INTO live_candles SELECT 'binance_tr',$1,'1h',(x->>'time')::bigint,x FROM jsonb_array_elements($2::jsonb) x ON CONFLICT DO NOTHING",[pair,JSON.stringify(bars)]);
   trained.push({pair,status:model.status,model_id:model.model_id||null,rows:bars.length});
  }catch(e){trained.push({pair,status:'failed',error:e.message});}
 }
 return trained;
}
