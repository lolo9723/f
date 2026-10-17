const API=location.origin;
'use strict';
const pages=[['overview','Genel durum'],['coverage','Geçmiş veri bankası'],['rally','Erken ralli avcısı'],['patterns','Örüntü hafızası'],['performance','Performans laboratuvarı'],['models','Model merkezi'],['imports','Veri içe aktarma'],['health','Sistem sağlığı']];
let selected='overview',data=null,token=sessionStorage.getItem('oran-token')||'';
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const number=v=>v===undefined||v===null?'—':new Intl.NumberFormat('tr-TR').format(v);
const time=v=>v?new Date(v).toLocaleString('tr-TR'):'Henüz yok';
const state=v=>({complete:'Doğrulandı',failed:'Erişilemedi',partial:'Kısmi',not_started:'Başlamadı',not_run:'Çalıştırılmadı',pending:'Bekliyor',running:'Çalışıyor',queued:'Sırada'}[v]||v||'Belirsiz');
function card(title,description){const c=el('article',undefined,'card');c.append(el('h2',title));if(description)c.append(el('p',description));return c;}
function table(headers,rows){const wrap=el('div',undefined,'table-scroll'),t=el('table'),head=el('tr');for(const h of headers)head.append(el('th',h));const thead=el('thead');thead.append(head);t.append(thead);const body=el('tbody');for(const row of rows){const tr=el('tr');for(const value of row)tr.append(el('td',String(value??'—')));body.append(tr);}t.append(body);wrap.append(t);return wrap;}
function metrics(items){const wrap=el('div',undefined,'metrics');for(const [label,value,note]of items){const n=el('article',undefined,'metric');n.append(el('span',label),el('strong',value),el('small',note));wrap.append(n);}return wrap;}
function empty(c,text){c.append(el('div',text,'empty'));return c;}
function render(){
  const root=document.querySelector('#content');root.replaceChildren();
  document.querySelector('#title').textContent=pages.find(p=>p[0]===selected)[1];
  for(const b of document.querySelectorAll('nav button'))b.classList.toggle('active',b.dataset.page===selected);
  if(!data)return;
  const total=data.totals||{}, inventory=data.inventory||{};
  if(selected==='overview'){
    root.append(metrics([['Global parite',number(inventory.binance_global?.known_symbols),'API + tarihsel envanter'],['TR parite',inventory.binance_tr?.latest_status==='failed'?'Erişim yok':number(inventory.binance_tr?.known_symbols),'Bağımsız TR kaynağı'],['Doğrulanmış mum',number(total.rows),'Kaynak ve zaman damgasıyla'],['Son veri aktarımı',data.generated_at_ms?new Date(data.generated_at_ms).toLocaleDateString('tr-TR'):'—',time(data.generated_at_ms)]]));
    const split=el('div',undefined,'split'),c=card('Veri kapsamı','Tamamlanma, parite envanteri ve tarihsel veri için ayrı izlenir.');
    for(const [name,market]of Object.entries(inventory)){const row=el('div',undefined,'source-row');row.append(el('strong',name==='binance_tr'?'Binance TR':'Binance Global'),el('small',state(market.latest_status)+' · '+number(market.known_symbols)+' parite'));c.append(row);}
    const next=card('Önce veri. Sonra kanıt.','Sinyal puanı, hedef olasılığı ve beklenen net getiri ayrı raporlanır. Veri kapsamı veya model doğrulaması yetersizken fırsat üretildiği iddia edilmez.');next.classList.add('accent');next.append(el('span','Gerçek para işlemleri kapalı','tag'));split.append(c,next);root.append(split,signalCard());
  }
  if(selected==='coverage'){
    root.append(metrics([['Tarihsel parite',number(data.historical_symbols),'En az bir bölümü alınan'],['Parquet boyutu',number(total.parquet_bytes?Math.round(total.parquet_bytes/1024):0)+' KB','Gerçek sıkıştırılmış boyut'],['Açık veri sorunu',number(data.unresolved_issues),'Eksik aralıklar ve erişim hataları'],['Kapsam',data.all_history_complete?'Tam':'Kısmi','Bütün geçmiş tamamlanmış değil']]));
    const c=card('Zaman çözünürlüğüne göre kapsam');c.append(table(['Borsa','Aralık','Parite','Mum','İlk kayıt','Son kayıt'],(data.intervals||[]).map(r=>[r.exchange,r.interval,number(r.symbols),number(r.rows),time(r.first_ms),time(r.last_ms)])));root.append(c);
    const gaps=card('Eksik aralıklar');gaps.append(table(['Başlangıç','Bitiş','Açıklama'],(data.gaps||[]).map(g=>[time(g.start_ms),time(g.end_ms),g.detail])));if(!data.gaps?.length)empty(gaps,'Raporda kayıtlı eksik aralık yok. Bu, envanterdeki bütün paritelerin geçmişinin alındığı anlamına gelmez.');root.append(gaps);
    const failed=card('Alınamayan veya doğrulanamayan bölümler');failed.append(table(['Parite','Aralık','Dönem','Neden'],(data.failed_partitions||[]).map(r=>[r.symbol,r.interval,r.period,r.error])));root.append(failed);
  }
  if(selected==='rally')root.append(signalCard());
  if(selected==='patterns'){const c=card('Canlı örüntü hafızası','Toplam '+number(data.patterns?.total)+' olay. Sonuçlar yalnızca ilgili saatlik mum doğrulandığında eklenir.');c.append(table(['Parite','Gözlem','Puan','Ölçülen saatlik ufuklar'],(data.patterns?.events||[]).map(x=>[x.symbol,time(x.observed_at),x.features.score,Object.keys(x.outcomes).join(', ')||'Bekleniyor'])));root.append(c);}
  if(selected==='performance'){
    const p=data.performance;
    if(!p||p.status==='not_evaluated')root.append(empty(card('İleriye dönük performans'),'Sonucu doğrulanmış canlı sanal işlem henüz yok. Kazanma oranı hesaplanmadı.'));
    else root.append(metrics([['Sanal işlem',number(p.trades),'Gerçek işlemlerden ayrı'],['Net beklenen getiri',p.net_expectancy===null?'—':(p.net_expectancy*100).toFixed(2)+'%','Tahmini maliyetler sonrası'],['Kazanma oranı',p.win_rate===null?'—':(p.win_rate*100).toFixed(1)+'%','Sonuçlanmış değerlendirilebilir işlemler'],['Belirsiz olay',number(p.excluded?.ambiguous),'Kesin kazanç sayılmaz']]));
  }
  if(selected==='models'){
    const c=card('Model sürümleri','Aday modeller örneklem dışı karşılaştırma ve ileriye dönük doğrulamadan sonra üretime aday olur.');
    if(!data.models?.length)empty(c,'Henüz eğitilmiş ve doğrulanmış üretim modeli yok.');else c.append(table(['Sürüm','Durum','Oluşturuldu'],data.models.map(m=>[m.model_id,m.status,time(m.created_at)])));root.append(c);
  }
  if(selected==='imports'){
    root.append(card('CSV, Excel ve Parquet doğrulama','Yerel içe aktarma aracı; kaynak, yöntem, oluşturma zamanı, as_of ve sürüm bilgilerini zorunlu tutar. Gelecek bilgi içeren kayıtları reddeder.'));
    root.append(empty(card('Çevrimiçi dosya yükleme'),'Çevrimiçi dosya deposu henüz bağlanmadı. Bu sürümde dosyalar yerel içe aktarma komutuyla doğrulanır; eğitim verilerine otomatik eklenmez.'));
  }
  if(selected==='health'){
    const c=card('İş kuyruğu');c.append(table(['Durum','Adet'],Object.entries(data.tasks||{}).map(([s,n])=>[state(s),number(n)])));root.append(c);
    const sources=card('Veri kaynakları');for(const m of Object.values(inventory))for(const s of(m.sources||[])){const r=el('div',undefined,'source-row');r.append(el('strong',s.source),el('small',state(s.status)+(s.error?' · '+s.error:'')));sources.append(r);}root.append(sources);
    const limits=card('Kota durumu','Hesap genelindeki kota ölçümleri henüz bağlanmadı. Uygulama içi sınırlar sağlayıcının faturalandırma limiti değildir.');root.append(limits);
  }
}
function signalCard(){const c=card('Erken ralli adayları','Binance TR spot TRY piyasası · En fazla 48 saat · Sanal işlem');
  if(!data.signals?.length)return empty(c,'Henüz doğrulanmış modelden üretilmiş sinyal yok.');
  c.append(el('p','Adaylar temel ralli kuralıyla sıralanır; doğrulanmış kârlılık veya kalibre olasılık değildir. Sanal kasa ve optimizasyon ayarları Android uygulamasındadır.'));c.append(table(['Parite','Canlı puan','RSI','MACD','Alış','Hedef','Stop','Puan zamanı'],data.signals.map(s=>[s.symbol_id,s.live_valid===false?'Güncel değil':s.score,s.indicators?.rsi14?.toFixed(1),s.indicators?.macd_histogram>0?'↑':'↓',s.entry,s.target,s.stop,time(s.predicted_at)])));return c;}
async function load(){const notice=document.querySelector('#notice');notice.replaceChildren();
  document.querySelector('#login').hidden=true;
  try{const r=await fetch(API+'/v1/live/status',{headers:{Authorization:'Bearer '+token}});if(!r.ok)throw new Error(r.status===401?'Erişim anahtarını kontrol et.':'Sunucu hatası: '+r.status);data=await r.json();document.querySelector('#login').hidden=true;
    const stale=!data.generated_at_ms||Date.now()-(data.live?.updated_at||0)>90000;
    notice.append(el('div',stale?'Veri aktarımı güncel değil. Son aktarım: '+time(data.generated_at_ms):'Canlı puan 10 sn · arka plan 1 dk · araştırma 15 dk · Son puan: '+time(data.live?.updated_at),'banner'+(stale?' error':'')));render();
  }catch(e){notice.append(el('div',e.message+(data?' · Son görüntülenen rapor: '+time(data.generated_at_ms):''),'banner error'));document.querySelector('#login').hidden=false;}}
for(const[id,label]of pages){const b=el('button',label);b.dataset.page=id;b.onclick=()=>{selected=id;render();};document.querySelector('#nav').append(b);}
document.querySelector('#loginForm').onsubmit=e=>{e.preventDefault();token=document.querySelector('#token').value.trim();sessionStorage.setItem('oran-token',token);document.querySelector('#token').value='';load();};
document.querySelector('#refresh').onclick=load;render();load();setInterval(load,10000);
