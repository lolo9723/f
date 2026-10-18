# Oran Radar Cloud 4.0 — düzeltme 4.0.3

Ana sunucu: https://br-gentle-glade-bai48xkz-radar.compute.c-8.eu-central-1.aws.neon.tech

Yedek sunucu: https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech

Her iki sunucu da gerçek Binance TR envanteri ve type-1 resmî piyasa uç noktalarını kullanır. Kamuya açık `/v1/live/status` ve `/v1/public/status`, 10 saniyelik ortak önbellek ve tek uçuş kontrolüyle hesaplanır. 16 likit + 8 dönüşümlü TRY paritesi 15 dakikada bir seçilir. Dönüşüm saat dilimine bağlı ve deterministiktir; soğuk başlangıç veya yedek sunucuya geçiş sırayı başa döndürmez. Kapalı ve ardışık 1m/1h mumlar, güncel fiyat, BTC bağlamı ve emir defteri zamanları ayrı doğrulanır. Eski puanın zamanı hatada yenilenmez.

## Kota düzeltmesi

Önceki sürüm aynı büyük model JSON'larını ve piyasa durumunu sık sık PostgreSQL'den okuyordu. Eski projenin 5 GB aylık aktarım sınırı aşıldı. Yeni piyasa motoru (`market-memory.mjs`) sıfır PostgreSQL sorgusuyla çalışır. Modeller dağıtımda gerçek TR geçmişinden hazırlanan başlangıç verisini ve sıkıştırılmış **özel** nesne deposunu kullanır; depo anahtarları platformdan sunucuya enjekte edilir. Öğrenme vektörleri telefona veya kamuya açık API'ye verilmez. Her yeni süreç özel depodaki güncel modelleri bir kez yükler; sıcak süreçte okuma altı saatte birle sınırlıdır. Depo erişilemezse paketlenmiş başlangıç modelleri kullanılır; model 24 saati aşarsa araştırma katkısı sıfırlanır.

APK iki sabit, güvenilen sunucu arasında otomatik geçer. HTTP 429/503, bağlantı hatası ve güncel puan bulunamaması yedeği tetikler. Retry-After ve artan bekleme süresi uygulanır. Kamuya açık piyasa istekleri cihaz anahtarı taşımaz. Her iki kaynak da doğrulanamazsa eski kayıt açıkça çevrimdışı gösterilir.

Sanal kasa ayrı, küçük bir PostgreSQL projesindedir (`broad-fog-26964869`, `br-gentle-glade-bai48xkz`). İki piyasa sunucusu tek kasa otoritesini kullanır; yedek için ikinci bakiye oluşturulmaz. Yazımlar transaction, row lock, revision ve tekil request_id ile korunur. Kaybolan cevap aynı alışın iki kez düşülmesine yol açmaz. Kapanmış eski emirler sayfalanan arşive taşınır. Ücretsiz planda 0,25 CU üst sınırı ve 90 CU-saat / 4 GB aktarım / 100 MB veritabanı koruma sınırları vardır. Ücretli plan veya otomatik yükseltme açılmadı. Ücretsiz hizmetlerin sınırsızlık veya kesintisizlik garantisi yoktur.

**Eski kasa durumu:** Eski proje kotası SQL okumalarını da engelliyor. Eski kayıtlar silinmedi ve yeni kasaya aktarılmış gibi gösterilmiyor. Önceki cihaz anahtarında uygulama bunu açıkça belirtir. Yeni oluşturulan sanal kasa ayrıdır. Yetkili eski kayıt okuma yolu `/v1/wallet/legacy` korunmuştur; sağlayıcı erişimi açılmadan eski bakiye kurtarıldığı iddia edilmez.

## Karar ve göstergeler

Optimal mod çalışma bütçesi gerektirir. **Nihai puan >= seçilen eşik** ve güncel veri varsa aday kabul edilir; ayrıca araştırma hazır/pozitif olsun diye ikinci bir veto uygulanmaz. Gerçek bütçe, coin yoğunlaşması, pozisyon sayısı, bekleme süresi, spread, geçerli stop/hedef ve açık işlem doğrulaması sınırları geçerlidir. Kabul edilmeyen aday için nedeni, puanı ve izin verilen azami tutar kaydedilir.

Piyasa alışta güncel doğrulanmış fiyatla sanal pozisyon hemen açılır. Limit seçiminde araştırmanın seçtiği fiyat beklenir; gerçekleşmemiş emir alış gibi gösterilmez. Nihai puan eşiğin altına inerse bekleyen limit ancak önceki işlem akışı doğrulandıktan sonra iptal edilir. Manuel mod otomatik yeni alış yapmaz; kullanıcı coin, tutar, alış, stop ve hedefi seçer. İlk APK'daki BASE/FINAL, AUTO/FIXED, MARKET/LIMIT ve risk ayarları korunur; Optimal nihai puan ve otomatik tutar kullanır.

Göstergeler: EMA20/50, MACD, Wilder RSI, Stoch RSI, Bollinger, ADX, CCI, VWAP, MFI, CMF, OBV, Donchian, ATR, hacim oranı ve dakika getirilerine ek olarak Kaufman trend verimliliği, ROC ivmesi, Williams %R, Keltner konumu, gerçekleşen/aşağı yönlü oynaklık, BTC'ye göre 5 dakikalık göreli güç, piyasa genişliği ve derinlik dengesizliği. İlişkili göstergeler bağımsız oylar gibi toplanmaz; rejime göre ağırlıklı gruplarda sınırlı katkı verir.

Araştırma dört önceden tanımlı politikayı kronolojik eğitim/doğrulama/test ayrımı ve 54 saatlik etiket boşluğuyla karşılaştırır. Politika ayrı testte seçilmez. Benzer geçmiş olayların ve ayrı testin maliyet sonrası getirisi nihai puanı sınırlı kanıtta en fazla ±8, aynı yönlü güçlü kanıtta ±20 puan etkiler. Örnek sayısı ve belirsizlik katkıyı küçültür. Araştırma tutarı ve ATR planını da etkiler. Python `dynamic.py` ile Node kuralları parite testindedir. Bu betimsel puan bir olasılık veya kâr garantisi değildir.

## Çalışma ve doğrulama

Uygulama açıkken puan 10 saniyede, kullanıcı kasası 60 saniyede kontrol edilir. Telefon kapalıyken ücretsiz sunucu tetikleyicisi 15 dakikada bir piyasa, kasa ve sıradaki iki araştırma modelini işler. Hedef/stop sırası resmî aggregate trades ile doğrulanır; eksik sıra varsa yeni alış bekletilir. Limit 6 saat, dolumdan sonra en fazla 48 saat takip edilir. Alış/satış ayrı %0,10 komisyon; stop/zaman çıkışında ek %0,10 kayma varsayılır. Gerçek para emri verilmez.

`npm ci && npm test && npm run build`. Normal build ana sunucudur; `ORAN_BUILD_ROLE=backup npm run build` yedeği üretir. Function ZIP kökünde index.mjs bulunur. `neon.ts` özel model deposu ve 15 dakikalık tetikleyiciyi belgeler. Mevcut deploy ortamına secret değerleri yeniden gönderilmez.

Kasa uçları: `/v1/session`, `/v1/wallet`, `/init`, `/settings`, `/manual-order`, `/buy`, `/cancel`, `/close`, `/tick`, `/archive`, `/legacy`. Yazımlarda revision ve request_id gerekir. Cihaz anahtarı SecureStore'da saklanır. Arka plan bildirimi yapılandırılmadı.
