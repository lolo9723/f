# Oran Radar Lab

Orijinal **alitra34/Oran-Radar** deposuna yazmadan hazırlanmış bağımsız çalışma. Yeni backend `backend_next/`, mobil entegrasyon `mobile/`, sunucusuz dağıtım `infra/template.yaml` içindedir. Eski backend `backend/` altında korunur.

## Eklenen davranışlar

- 22 temel ölçümün 0, 1, 3 ve 6 saat gecikmeli halleri: 88 özellik. Hacim, agresif alış payı, fiyat/hacim ilişkisi, trend, sıkışma, oynaklık, çarpıklık, otokorelasyon, mum gövdesi/fitilleri ve likidite vekili.
- Eğitim verisinden otomatik özellik birleşimleri; 1, 6 ve 24 saatlik yükseliş/düşüş araştırmaları. Grafik yorumu, koşullu/kontrol oranı, fark, güven aralığı, tekrar ve etkin örnek sayıları.
- Sabit “10 tekrar” yok. Güç/etki hedefleri, çoklu karşılaştırma ve araştırma turu bütçesi gereken sayıyı belirler. Yetersiz veri anlamlı sonuç gibi gösterilmez.
- Zaman sıralı %60 eğitim / %20 model seçimi / %20 test. Model yalnızca testte mevcut model/referans karşısında yeterli iyileşme gösterirse devreye alınır.
- Günlük yeniden eğitim, sürümlü modeller, ilk yayınlandığı hali korunan ileriye dönük tahminler, Brier/ECE ve dağılım değişimi takibi. Bozulan model yerine referans oran kullanılır.
- Mobilde **Kontrol et ve eksikleri uyandır**: kaynak boşluklarını, gecikmeleri, işler/model değerlendirmesi ve sanal takip eksiklerini denetler; gereken işleri kuyruğa alır. Kuyruğa alınma ile tamamlanma ayrı gösterilir.
- Mobil tek sürümlü snapshot okur, son başarılı sonucu önbelleğe alır, yeniden açılınca yeniler, eskimiş veriyi işaretler.
- Manuel sanal pozisyonlar bulutta kalır; gerçek public aggTrade sıra numaralarıyla eksik geçmiş tamamlanır. Eksik işlemlerden hedef başarısı uydurulmaz.
- Stop-loss araştırması: yüzde/ATR mesafeleri, hacim/oynaklık rejimleri, 6/24/48 saat, eşleşmiş net sonuç ve niceliksel örneklem planı. Günlük yeniden değerlendirme ve sürekli ileriye dönük gölge kayıtlar.
- Stop sonrası hedefe dönüş ayrı takip edilir; stop sonucu/zarar değişmez. Yeni manuel kayıtların gözlem anındaki göstergeleri korunur. Yöntem ve API [docs/STOP_LOSS.md](docs/STOP_LOSS.md) içinde.

**Lab 3.7 Android sürümü** tüm aktif TRY coinlerini otomatik keşfeder, eski Rally Score hesabını temel puan olarak korur; olgun yön/alış/stop araştırmalarıyla 0–100 puanı günceller; telefon içinde araştırma motoru, görünür arka plan servisi, periyodik işler ve seçilebilir Drive yedeği içerir. Radar ekranındaki Telefon motoru kartından başlatılır; bulut hesabı gerekmez. Lab 2.x ile aynı imza anahtarıyla güncellenebilir. 64 bit ARM Android 7+ gerekir. Android süre/ağ/pil sınırları ve Drive sağlayıcısına teslimin bulut onayından farkı [docs/PHONE_BACKEND.md](docs/PHONE_BACKEND.md) içinde açıklanır. Her coin kartında önerilen alış/hedef/stop fiyatları ve ayrı **Aldım gibi kaydet** formu vardır. Alış fiyatı güncel kotasyona göre kaymaz: son kapalı mum ve ayrı geçmiş karşılaştırmasından seçilen yüzde/ATR/destek/hacim ağırlıklı fiyat adayı kullanılır. Dolum, hedef, stop, belirsizlik ve masraf sonrası karar başına getiri birlikte ölçülür; ayrı test ve bilimsel örnek sayısı vardır. Veri azsa ATR ön adayı açıkça gösterilir; optimum veya kârlılık doğrulanmış sayılmaz. [Alış araştırması](docs/ENTRY_RESEARCH.md). Sanal alış formu güncel alış/hedef/stopu birlikte aktarır ve açılırken son planı kontrol eder. Eski/verisiz planda anlık fiyat ve +%3 hedef uydurmaz; son seviyeler ve bekleme sebebi görünür. Geciken yenileme manuel değişiklikleri ezmez. Küçük coin fiyatları 10 anlamlı basamakla gösterilir. Kayıt, fiyatları değiştirebileceğin ve şimdi başlamasını onayladığın manuel sanal alış takibidir. Canlı TRY fiyatları native WebSocket akışıyla puan/araştırmadan bağımsız alınır; REST yedeği, yeniden bağlanma ve fiyat yaşı gösterimi vardır. Ekran yaklaşık 2 saniyede güncellenir; görünür servis izinleriyle arka planda akış sürer. [Canlı fiyat takibi](docs/LIVE_PRICES.md). Puan taraması uzun araştırmadan ayrı çalışır; görünür servis/app aktifken 30 saniyelik iş aralığı hedefler. Ekrandaki coinlere öncelik verirken tüm coin sırasını korur. **Puanları şimdi yenile** düğmesi ve son tarama zamanı vardır. Araştırma katkısı için etkin örnek, ayrı test, sürüm ve güncellik kontrolleri gerekir; yön/stop için ileriye dönük kalite de aranır. Toplam etki ±20 ile sınırlıdır. Temel puan ve katkı ayrı görünür; veri/kalite bozulunca ilgili katkı kaldırılır. [Olgun araştırma puanı](docs/RESEARCH_SCORE.md). Canlı AWS kurulumu henüz yapılmadı.

## Çalıştırma

Python 3.12 ve Node 24:

```bash
python -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
PYTHONPATH=backend_next .venv/bin/python -m radar.local --port 8788
```

Mobilde `npm ci` ve `npx expo start`; ⚙ menüsüne API adresini yazın. Emülatör: `http://10.0.2.2:8788`; fiziksel cihaz: yerel ağdaki bilgisayar adresi. İnternet dağıtımında HTTPS ve erişim anahtarı kullanılır. Yerel sunucu açıkken zamanlayıcısı çalışır; gerçek sunucusuz süreklilik için bulut kurulumu gerekir.

Kurulabilir Android release APK için [docs/APK_BUILD.md](docs/APK_BUILD.md). Lab ayrı `com.oranradar.lab` paketiyle kurulur; JavaScript APK içine gömülür ve Expo Go gerekmez. APK kurulumu bulut backend'ini kendiliğinden dağıtmaz.

### Canlı kaynağa ihtiyaç duymayan demo

```bash
RADAR_PAIRS=BTCTRY PYTHONPATH=backend_next OPENBLAS_NUM_THREADS=1 .venv/bin/python -m radar.demo --root local-data/demo
RADAR_PAIRS=BTCTRY PYTHONPATH=backend_next OPENBLAS_NUM_THREADS=1 .venv/bin/python -m radar.local --demo --root local-data/demo --port 8788
```

Veriler açıkça **SYNTHETIC_DEMO** olarak işaretlenir; canlı başarı veya kârlılık kanıtı değildir. Demo manuel pozisyonlar için gerçek işlem kanıtı üretmez. Canlı ve demo için ayrı veri dizinleri kullanın.

## Frontend kapalıyken çalışma

AWS Lambda + EventBridge + SQS + DynamoDB + S3 kurulumu [DEPLOYMENT.md](DEPLOYMENT.md) içindedir. Yönetilecek VPS gerekmez; yönetilen bulut işlem/veri kaynakları yine gerekir. EventBridge her 5 dakikada çalışır; kapalı saatlik mumlar üzerinden analiz yapılır. Dağıtım yapılmadan bu çalışma ortamı kalıcı hizmet sayılmaz.

**Teslim anında canlı dağıtım yapılmadı.** Çalışma ortamında AWS kimliği veya ayrı hedef GitHub deposu tanımlı değil. Binance TR isteği bu ortamda HTTP 451 döndü; canlı veriyle uçtan uca doğrulama yapılamadı. Seçilen AWS bölgesinde kaynak erişimi doğrulanmalıdır.

## İstatistiksel tasarım ve sınırlar

Kaynak/formüller [docs/STATISTICS.md](docs/STATISTICS.md); mimari/API [docs/LAB_ARCHITECTURE.md](docs/LAB_ARCHITECTURE.md); test kanıtı [docs/VALIDATION.md](docs/VALIDATION.md).

Varsayılan izleme listesi `BTCTRY,ETHTRY,SOLTRY`, geçmiş 730 gündür; `RADAR_PAIRS`/`LOOKBACK_DAYS` ile değişir. Bu sürüm spot OHLCV verisini araştırır. Tarihsel order book, OI, funding, on-chain, haber veya bağlı olmayan başka kaynaklar ölçülmüş gibi sunulmaz. Yeni kaynak/özellik ekleme yolu mimari belgesindedir. Sürekli gelişme, yeni veriyle ölçülen model/özellik güncellemeleridir; kendiliğinden kod değiştirmek veya her gün daha yüksek kazanç garantisi değildir.

Gerçek emir gönderilmez. Yön olasılığı, limit girişin dolma veya hedefin stop öncesi görülme olasılığı değildir. Eski trade başarı metriklerine yeni yön tahminleri karıştırılmaz. Orijinal MVP belgeleri [docs/SOURCE_README.md](docs/SOURCE_README.md) ve [docs/SOURCE_DEPLOYMENT.md](docs/SOURCE_DEPLOYMENT.md) altında saklanır.
