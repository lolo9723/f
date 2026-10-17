# Canlı Oran Radar Cloud 4.0

- Kalıcı API: https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech
- Piyasa rapor paneli: aynı API adresinin `/` yolu; ek hosting veya ücretli hizmet açılmadı.
- Sağlık: `/v1/health`. Kamuya açık piyasa raporu: `/v1/public/status`.
- Neon projesi: `floral-dew-92290233`, production: `br-falling-term-b9315oqi`.
- Her 15 dakikada Neon Function Trigger `/internal/cron` çağırır. APK ve bilgisayar kapalıyken çalışır. Bu, saniyelik bir akış veya kesintisizlik garantisi değildir.
- TRY evreni yalnızca TR resmî sembol listesiyle belirlenir. Type 1 verileri TR belgelerinin gösterdiği `api.binance.me` uç noktalarından alınır. Global pariteler TR evrenine kendiliğinden eklenmez.
- Her tur: en likit 16 + sıradaki 8 + açık pozisyon pariteleri; en çok 36 analiz. Kapalı ve ardışık saatlik/dakikalık mumlar ve güncel emir defteri gerekir.
- İlk gerçek tarama: 309 TR fiyatı, 24 başarılı analiz, 0 hata. Eski gerçek tarihsel banka: 217.954 mum; bütün paritelerin bütün geçmişi tamamlanmış değildir.
- Temel ralli kuralı önceki Python motorunun aynı altı ağırlığını kullanır. Her turda sıradaki iki TR paritesinde en fazla 4.000 saatlik gerçek geçmiş indirilir. Dört önceden belirlenmiş limit/stop/hedef politikası kronolojik eğitim/doğrulama/test ayrımıyla karşılaştırılır; 54 saatlik etiket boşluğu uygulanır. Politika yalnızca doğrulama döneminden seçilir. Ayrı testte en az 8 etkin gözlem yoksa katkı sıfırdır. Benzer geçmiş olayların maliyet sonrası getirisi nihai puanı ±4 (sınırlı kanıt) / ±12 (aynı yönlü tarihsel aralıklar) üst sınırı içinde değiştirir. Etkin örnek ağırlığı katkıyı ayrıca küçültür. Aynı araştırma seçilen alış/stop/hedef politikasını da değiştirir. Varsayılan puan kaynağı FINAL'dir; BASE seçeneği korunur. Puan ve tarihsel ortalama getiri kalibre gelecek olasılığı değildir. Forward sonuçlar henüz olgunlaşmadı; otomatik üretim şampiyonu terfisi yapılmaz.

Python `src/oran_cloud/dynamic.py` ve bulut `src/research.mjs` aynı katkı ve politika kuralını kullanır. Parite testi orijinal Python ralli puanını, planı ve araştırma etkisini karşılaştırır. Sanal kasa eşik testinde BASE altında elenen ve FINAL katkısıyla kabul edilen aday kontrol edilir. Gerçek ilk örnek: TIA/TRY temel 30 → tarihsel katkı -0,3 → nihai 29,7. Bu bir kazanç doğrulaması değildir.

## Sanal kasa / optimizasyon

İlk APK 3.8.0'ın ayar sözleşmesi korunur: enabled, threshold (50/60/70/80/90/100), BASE/FINAL, AUTO/FIXED, MARKET/LIMIT, fixed_try, coin_cap_try, total_cap_try, risk_pct, portfolio_risk_pct, max_coin_pct, max_positions, cooldown_hours, target_pct, stop_pct ve entry_prices.

İlk kurulumda `execution_mode=MANUAL` ve otomasyon kapalıdır. Manuel modda POST `/v1/wallet/manual-order` ile `pair`, `entry_mode`, `amount_try`, limit için `entry`, `target`, `stop`, `revision` gönderilir. Güncel TR envanteri ve emir anında yeniden alınan fiyat doğrulanır. Kullanıcı planına sahte puan atanmaz.

`execution_mode=OPTIMAL` seçimi ve `total_cap_try` çalışma bütçesi zorunludur. Açık tutar + bekleyen limit rezervi bütçeyi aşamaz. Risk ve yoğunlaşma yüzdeleri kasa ile çalışma bütçesinin küçüğü üzerinden hesaplanır. Optimal seçim; güncel araştırma, en az 20 benzer olay, ayrı testte en az 8 etkin örnek, pozitif maliyet sonrası komşu ve test ortalaması ister. Öncelik: `(min(komşu ortalaması, ayrı test ortalaması) − 0,5 × standart hata − güncel spread) / planlanan kayıp × nihai puan / 100`. Pozitif değer yoksa işlem açılmaz. Bu şeffaf sezgisel dağıtım kuralı matematiksel en iyi sonuç veya gelecekte kâr iddiası değildir. Optimal her zaman nihai puanı, araştırmanın seçtiği limit planını ve otomatik tutarı kullanır; eski manuel varsayılanlar bu planı değiştirmez. Kasa API'sindeki `daily`, İstanbul gününe ait kapanmış sanal işlemlerin net sonucudur.

İlk bakiye bir kez oluşturulur. Para kuruş olarak saklanır; her yazım Postgres transaction + row lock + revision kontrolünden geçer. Anahtarlar cihaza özeldir; başka cihazın kasasına erişemez. Uygulama silinirse cihaz anahtarı kaybolabilir; mevcut kasayı başka cihaza taşımak için anahtarın güvenli biçimde korunması gerekir. Anahtar repoya veya APK'ye gömülmez.

Sanal limit dolumu ve stop/hedef sırası resmi aggregate trades ile doğrulanır; sıra boşluğu varsa sonuç bekletilir. Her tur işlem sayfası bütçesi sınırlıdır; yoğun paritelerde geçmiş akışı yakalama gecikebilir. Limit bekleme 6 saat, dolumdan sonra takip 48 saat. Alış/satış komisyonu ayrı %0,10; stop/zaman çıkışında ek %0,10 kayma varsayımı. Puan başarı olasılığı değildir; bilinmeyen korelasyon bağımsızlık sayılmaz, ortak risk sınırı uygulanır.

## Kurulum ve test

`npm ci && npm test && npm run build`. `src/schema.sql` sürümlü ve tekrar uygulanabilir migration'dır. Önce validation dalında uygulanıp HTTP entegrasyonu doğrulandı, sonra production'a uygulandı. Function zip kökünde `index.mjs` bulunur. `neon.ts` aynı function/cron politikasını belgeler; secret değerleri yalnızca dağıtım ortamındadır.

Kullanıcı anahtarı: POST `/v1/session`, yalnızca kendi kasa API'sinde kullanılır. GET `/v1/wallet`; POST `/v1/wallet/init`; PUT `/v1/wallet/settings`; POST `/v1/wallet/manual-order`, `/buy`, `/cancel`, `/close`. Kasa yazımları ve ayar yazımı `revision` ister. Yönetici refresh anahtarı kullanıcı anahtarı değildir.

Neon Free organizasyonunda ücretli plan açılmadı. 0,25 CU, varsayılan ölçek sıfırlama; 100 CU-saat ve servislerin ücretsiz kotası geçerlidir. Kota dolarsa hizmet durabilir. FCM arka plan bildirimi henüz yapılandırılmadı.

Ücretsiz veritabanı yaklaşık 750 MB'a ulaştığında yeni ham geçmiş ve örüntü yazımı duraklatılır; güncel piyasa analizi, mevcut kasaların takibi ve durum raporu için kapasite ayrılır. Ücretli plana kendiliğinden geçilmez.
