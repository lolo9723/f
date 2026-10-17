/** Framework-independent client for the existing Expo app. No master key in APK. */
export class CloudClient {
  constructor({baseUrl,getToken,cache,fetchImpl=fetch,now=Date.now,timeoutMs=15000}) {
    const u=new URL(baseUrl);
    if(u.protocol!=='https:')throw new Error('Cloud API must use HTTPS');
    this.baseUrl=u.origin;this.getToken=getToken;this.cache=cache;
    this.fetchImpl=fetchImpl;this.now=now;this.timeoutMs=timeoutMs;
  }
  async request(path,options={}) {
    const token=await this.getToken();if(!token)throw new Error('Kullanıcı erişim anahtarı gerekli');
    const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),this.timeoutMs);
    try {
      const r=await this.fetchImpl(this.baseUrl+path,{...options,signal:abort.signal,headers:{'Content-Type':'application/json',...options.headers,Authorization:'Bearer '+token}});
      if(!r.ok){const e=new Error('Sunucu yanıtı: '+r.status);e.status=r.status;throw e;}
      return await r.json();
    }finally{clearTimeout(timer);}
  }
  async getSnapshot() {
    const key='oran-cloud-snapshot:'+this.baseUrl;
    try {
      const data=await this.request('/v1/status');
      if(data.schema_version!=='coverage-v1'&&!['not_started'].includes(data.status))throw new Error('Geçersiz API veri sözleşmesi');
      const record={data,syncedAt:this.now()};
      await this.cache.setItem(key,JSON.stringify(record));
      return {...record,source:'network',stale:!data.generated_at_ms||this.now()-data.generated_at_ms>1800000,error:null};
    }catch(e){
      if(e.status===401||e.status===403)throw e;
      const raw=await this.cache.getItem(key);
      if(!raw)throw e;
      let record;try{record=JSON.parse(raw);}catch{throw e;}
      if(!record.data||!Number.isFinite(record.syncedAt))throw e;
      return {...record,source:'cache',stale:true,error:e.message};
    }
  }
  getSettings(){return this.request('/v1/settings');}
  setSettings(settings){return this.request('/v1/settings',{method:'PUT',body:JSON.stringify(settings)});}
  registerPushToken(token){return this.request('/v1/devices',{method:'POST',body:JSON.stringify({token})});}
}
