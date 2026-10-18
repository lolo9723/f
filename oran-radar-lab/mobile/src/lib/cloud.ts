import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import {Platform} from 'react-native';

export const DEFAULT_CLOUD_BASE = 'https://br-gentle-glade-bai48xkz-radar.compute.c-8.eu-central-1.aws.neon.tech';
export const BACKUP_CLOUD_BASE = 'https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech';
export const WALLET_CLOUD_BASE = DEFAULT_CLOUD_BASE;
const managed=(base:string)=>[DEFAULT_CLOUD_BASE,BACKUP_CLOUD_BASE].includes(base);
const circuits=new Map<string,{until:number;failures:number}>();
let registration:Promise<string>|null=null;
export const PUBLIC_COVERAGE_URL = 'https://raw.githubusercontent.com/lolo9723/f/oran-radar-cloud-4.0/oran-radar-lab/cloudflare-coverage.json';
export type ResearchDecision={base_score:number;research_adjustment:number;final_score:number;status:string;reason:string;model_id:string|null;historical_net:number|null;uncertainty:[number,number]|null;samples:number;effective_samples:number;holdout?:{n:number;effective_n:number;mean:number|null}};
export type LiveIndicators={adjustment:number;rsi14:number;ema20:number;ema50:number;macd_histogram:number;vwap60:number;atr_pct:number;obv_flow20:number;volume_ratio5_55:number;efficiency_ratio20?:number;roc_acceleration5?:number;downside_volatility20?:number;williams_r14?:number;keltner_position?:number;relative_strength_btc5?:number;market_breadth?:number;closed_minute_at:number;adx14:number;bollinger_percent_b:number;mfi14:number;cmf20:number;stoch_rsi14:number};
export type CloudSignal = {live_valid?:boolean;live_error?:string|null;cloud_gate?:{eligible:boolean;reason:string;risk_scale?:number};inputs_at?:{price:number;book:number;minute_close:number;hour_close:number};base_rally_score?:number;indicators?:LiveIndicators;base_score?:number;final_score?:number;research?:ResearchDecision;signal_id: string;symbol_id: string;predicted_at: number;model_id: string;entry: number;target: number;stop: number;expires_at: number;score: number|null;probability: number|null;expected_return: number|null;mode: string};
export type CloudSnapshot = {
  schema_version:'coverage-v1';generated_at_ms:number|null;status:string;all_history_complete:boolean;
  inventory:Record<string,{known_symbols:number;latest_status:string;sources?:{source:string;status:string;error?:string|null}[]}>;
  tasks:Record<string,number>;totals:{rows?:number;parquet_bytes?:number;physical_object_bytes?:number;first_ms?:number;last_ms?:number};
  historical_symbols?:number;symbols_without_downloaded_history?:number;unresolved_issues?:number;
  quality?:{validated_source_partitions:number;rejected_source_partitions:number;downloaded_file_validation_rate:number|null;complete_coverage_partitions:number};
  intervals:{exchange:string;interval:string;symbols:number;rows:number;first_ms:number;last_ms:number}[];
  gaps:{start_ms:number;end_ms:number;detail:string}[];
  failed_partitions?:{symbol:string;interval:string;period:string;error:string}[];
  signals:CloudSignal[];models:{model_id:string;status:string;created_at:number}[];
  limitations?:string[];
  live?:{score_interval_seconds?:number;background_interval_seconds?:number;deep_scan_at?:number;score_error?:string|null;scanned:number;failed:number;known_symbols:number;quotes:number;updated_at:number;cadence_minutes:number;next_scheduled_at:number;scan_policy:string;errors?:{pair:string;error:string}[]};
  patterns?:{status:string;events:{symbol:string;observed_at:number;features:{score:number};outcomes:Record<string,{return:number}>}[];total:number};
  performance?:{status?:string;trades?:number;win_rate?:number|null;net_expectancy?:number|null};
};
export type CloudResult={data:CloudSnapshot;fetchedAt:number;offline:boolean;error:string|null;source:'github'|'api';endpoint?:string;backup?:boolean};
const BASE_KEY='oranCloud4.base',TOKEN_KEY='oranCloud4.userToken';
const tokenStore={get:async()=>Platform.OS==='web'?globalThis.localStorage?.getItem(TOKEN_KEY):SecureStore.getItemAsync(TOKEN_KEY),set:async(v:string)=>{if(Platform.OS==='web')globalThis.localStorage?.setItem(TOKEN_KEY,v);else await SecureStore.setItemAsync(TOKEN_KEY,v);},remove:async()=>{if(Platform.OS==='web')globalThis.localStorage?.removeItem(TOKEN_KEY);else await SecureStore.deleteItemAsync(TOKEN_KEY);}};
export function normalizeCloudBase(value:string){
  const v=value.trim().replace(/\/+$/,'');if(!v)return '';
  const u=new URL(v);
  if(u.protocol!=='https:'||u.username||u.password||u.search||u.hash||u.pathname!=='/')throw new Error('API adresi https:// ile başlayan bir ana adres olmalı.');
  return u.origin;
}
export async function cloudConfig(){
 const stored=await AsyncStorage.getItem(BASE_KEY),base=stored==='github'?'':stored===BACKUP_CLOUD_BASE?DEFAULT_CLOUD_BASE:stored||DEFAULT_CLOUD_BASE;
 let token=(await tokenStore.get())||'';
 if(managed(base)&&!token){try{registration??=(async()=>{const session=await cloudJSON(WALLET_CLOUD_BASE,'/v1/session','',{method:'POST',body:'{}'});await tokenStore.set(session.token);return session.token;})();token=await registration;}catch{registration=null;}}
 return {base,token};
}
export async function saveCloudConfig(base:string,token:string){
  const normalized=normalizeCloudBase(base);
  if(normalized&&token.trim().length<32)throw new Error('Kullanıcı erişim anahtarı en az 32 karakter olmalı.');
  if(token.trim())await tokenStore.set(token.trim());else await tokenStore.remove();
  await AsyncStorage.setItem(BASE_KEY,normalized||'github');
}
export function validateCloudSnapshot(v:unknown):CloudSnapshot{
  const x=v as CloudSnapshot;
  if(x?.status==='not_started'&&x?.generated_at_ms===null)return {...x,schema_version:'coverage-v1',all_history_complete:false,inventory:{},tasks:{},totals:{},signals:[],models:[],gaps:[],intervals:[]};
  if(x?.schema_version!=='coverage-v1'||!Number.isFinite(x.generated_at_ms)||!Array.isArray(x.signals)||!Array.isArray(x.models)||!Array.isArray(x.intervals)||!Array.isArray(x.gaps)||!x.totals||!x.inventory||!x.tasks)throw new Error('Bulut veri sözleşmesi doğrulanamadı.');
  return x;
}
export async function cloudJSON(base:string,path:string,token:string,init?:RequestInit){
  // Wallet requests always reach one ledger. Market failover never moves money
  // into a second database. Writes are NOT automatically retried.
  if(managed(base)&&(path.startsWith('/v1/wallet')||path==='/v1/session'))base=WALLET_CLOUD_BASE;
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),25000);
  let body=init?.body;
  if(path.startsWith('/v1/wallet')&&typeof body==='string'&&init?.method&&init.method!=='GET'){
    const value=JSON.parse(body);value.request_id??='r_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2)+'_'+Math.random().toString(36).slice(2);body=JSON.stringify(value);
  }
  try{
    const response=await fetch(base+path,{...init,...(body===undefined?{}:{body}),signal:controller.signal,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...init?.headers}});
    if(!response.ok){let message='Bulut sunucusu HTTP '+response.status;try{const data=await response.json();if(typeof data.error==='string')message+=' · '+data.error;}catch{}const retry=Number(response.headers?.get('Retry-After'));throw Object.assign(new Error(message),{status:response.status,retryAfter:Number.isFinite(retry)&&retry>0?retry*1000:30000});}
    return await response.json();
  }finally{clearTimeout(timer);}
}
export async function fetchCloudSnapshot(base:string,token:string):Promise<CloudResult>{
  const source=base?'api':'github';const cacheKey='oranCloud4.snapshot:'+(base||'public');
  try{
    let value:unknown,endpoint=base;
    if(base&&managed(base)){
      let failure:unknown,found=false;
      for(const candidate of [base,...[DEFAULT_CLOUD_BASE,BACKUP_CLOUD_BASE].filter(x=>x!==base)]){
        if((circuits.get(candidate)?.until||0)>Date.now())continue;
        try{value=await cloudJSON(candidate,'/v1/live/status','');const snapshot=validateCloudSnapshot(value);if(!snapshot.live||Date.now()-snapshot.live.updated_at>90000||!snapshot.signals.some(s=>s.live_valid!==false&&s.expires_at>Date.now()))throw new Error('Sunucuda güncel puan doğrulanamadı.');endpoint=candidate;found=true;circuits.delete(candidate);break;}
        catch(e){failure=e;const error=e as Error&{status?:number;retryAfter?:number};if(error.status===401||error.status===403)throw error;const failures=(circuits.get(candidate)?.failures||0)+1;circuits.set(candidate,{failures,until:Date.now()+Math.min(300000,Math.max(error.retryAfter||0,15000*2**Math.min(failures,4)))});}
      }
      if(!found)throw failure||new Error('Sunucular kısa süreli beklemede; otomatik yeniden denenecek.');
    }else value=base?await cloudJSON(base,'/v1/status',token):await cloudJSON(PUBLIC_COVERAGE_URL,'','');
    const result:CloudResult={data:validateCloudSnapshot(value),fetchedAt:Date.now(),offline:false,error:null,source,endpoint,backup:managed(base)&&endpoint!==base};
    await AsyncStorage.setItem(cacheKey,JSON.stringify(result));return result;
  }catch(e){
    const error=e as Error&{status?:number};
    if(error.status===401||error.status===403)throw error;
    const raw=await AsyncStorage.getItem(cacheKey);
    if(raw){try{const old=JSON.parse(raw) as CloudResult;validateCloudSnapshot(old.data);return {...old,offline:true,error:error.message};}catch{/* Preserve the original connection error. */}}
    throw error;
  }
}
