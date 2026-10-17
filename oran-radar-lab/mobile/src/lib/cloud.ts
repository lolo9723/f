import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';

export const PUBLIC_COVERAGE_URL = 'https://raw.githubusercontent.com/lolo9723/f/oran-radar-cloud-4.0/oran-radar-lab/cloudflare-coverage.json';
export type CloudSignal = {signal_id: string;symbol_id: string;predicted_at: number;model_id: string;entry: number;target: number;stop: number;expires_at: number;score: number|null;probability: number|null;expected_return: number|null;mode: string};
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
  performance?:{status?:string;trades?:number;win_rate?:number|null;net_expectancy?:number|null};
};
export type CloudResult={data:CloudSnapshot;fetchedAt:number;offline:boolean;error:string|null;source:'github'|'api'};
const BASE_KEY='oranCloud4.base',TOKEN_KEY='oranCloud4.userToken';
export function normalizeCloudBase(value:string){
  const v=value.trim().replace(/\/+$/,'');if(!v)return '';
  const u=new URL(v);
  if(u.protocol!=='https:'||u.username||u.password||u.search||u.hash||u.pathname!=='/')throw new Error('API adresi https:// ile başlayan bir ana adres olmalı.');
  return u.origin;
}
export async function cloudConfig(){return {base:(await AsyncStorage.getItem(BASE_KEY))||'',token:(await SecureStore.getItemAsync(TOKEN_KEY))||''};}
export async function saveCloudConfig(base:string,token:string){
  const normalized=normalizeCloudBase(base);
  if(normalized&&token.trim().length<32)throw new Error('Kullanıcı erişim anahtarı en az 32 karakter olmalı.');
  if(token.trim())await SecureStore.setItemAsync(TOKEN_KEY,token.trim());else await SecureStore.deleteItemAsync(TOKEN_KEY);
  await AsyncStorage.setItem(BASE_KEY,normalized);
}
export function validateCloudSnapshot(v:unknown):CloudSnapshot{
  const x=v as CloudSnapshot;
  if(x?.status==='not_started'&&x?.generated_at_ms===null)return {...x,schema_version:'coverage-v1',all_history_complete:false,inventory:{},tasks:{},totals:{},signals:[],models:[],gaps:[],intervals:[]};
  if(x?.schema_version!=='coverage-v1'||!Number.isFinite(x.generated_at_ms)||!Array.isArray(x.signals)||!Array.isArray(x.models)||!Array.isArray(x.intervals)||!Array.isArray(x.gaps)||!x.totals||!x.inventory||!x.tasks)throw new Error('Bulut veri sözleşmesi doğrulanamadı.');
  return x;
}
export async function cloudJSON(base:string,path:string,token:string,init?:RequestInit){
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),15000);
  try{
    const response=await fetch(base+path,{...init,signal:controller.signal,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...init?.headers}});
    if(!response.ok){const e=Object.assign(new Error('Bulut sunucusu HTTP '+response.status),{status:response.status});throw e;}
    return await response.json();
  }finally{clearTimeout(timer);}
}
export async function fetchCloudSnapshot(base:string,token:string):Promise<CloudResult>{
  const source=base?'api':'github';const cacheKey='oranCloud4.snapshot:'+(base||'public');
  try{
    const value=base?await cloudJSON(base,'/v1/status',token):await cloudJSON(PUBLIC_COVERAGE_URL,'','');
    const result:CloudResult={data:validateCloudSnapshot(value),fetchedAt:Date.now(),offline:false,error:null,source};
    await AsyncStorage.setItem(cacheKey,JSON.stringify(result));return result;
  }catch(e){
    const error=e as Error&{status?:number};
    if(error.status===401||error.status===403)throw error;
    const raw=await AsyncStorage.getItem(cacheKey);
    if(raw){try{const old=JSON.parse(raw) as CloudResult;validateCloudSnapshot(old.data);return {...old,offline:true,error:error.message};}catch{/* Preserve the original connection error. */}}
    throw error;
  }
}
