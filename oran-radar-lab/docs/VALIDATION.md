# Teslimat doğrulaması

Backend 2026-10-09 tarihinde, Android kontrol düğmesi düzeltmesi 2026-10-11 tarihinde bağımsız çalışma kopyasında doğrulandı.

| Kontrol | Sonuç |
|---|---|
| Kaynak kopya | 48 dosya içeriği Git blob SHA-1 ile doğrulandı; kaynak revizyon d62650d438ba30643e6f28671ed5ee960766f70f |
| Yeni backend / telefon motoru | 113 pytest testi başarılı; uyarılar hata sayılarak çalıştırıldı |
| Legacy backend | Orijinal 10 unittest başarılı |
| Python statik kontrol | ruff check başarılı |
| Mobil tipler | TypeScript noEmit başarılı |
| Mobil statik kontrol | Expo lint başarılı |
| Telefon sayısal uyumluluğu | NumPy 1.26.2 ve taşınabilir hesaplamalarla 107 araştırma/takip testi başarılı; Student-t/Fisher/normal/L-BFGS SciPy ile karşılaştırıldı |
| Mobil paper davranışı | Mevcut Node paper testleri başarılı |
| Radar kontrol düğmesi | Bağlantısız ilk ekran düğmeyi içerir; ayar yönlendirmesi, eski backend reddi, araştırma kurtarma isteği ve ağ hatası testleri başarılı |
| Mobil JS paketi | Android Metro/Hermes export başarılı |
| Android APK 2.1 | assembleRelease başarılı; com.oranradar.lab 2.1.0 / versionCode 2, API 24–36, ARM64 ve ARMv7; kontrol ve stop araştırma ekranları pakette doğrulandı |
| APK imza/bütünlük | Lab RSA 3072 anahtarıyla v2/v3 imza doğrulandı; ZIP CRC, 3 DEX, gömülü Hermes ve 16 KB ZIP hizalaması geçti |
| Android 16 KB sayfalar | APK içindeki 23 ARM64 ELF kütüphanesinin LOAD hizalaması doğrulandı |
| Fiziksel cihaz | Kurulum/açılış testi yapılmadı; derleme ve dosya doğrulaması cihaz testi değildir |
| Sunucusuz altyapı | SAM validate --lint ve SAM build başarılı |
| Lambda paket boyutu | Worker build yaklaşık 205 MiB; 250 MiB açılmış ZIP sınırının altında |
| Bulut iş/veri API'leri | Moto ile DynamoDB/S3/SQS lease, atomik pointer, idempotency, scheduler, DLQ ve kısmi başarısızlık testleri |
| HTTP entegrasyonu | Yerel demo health/audit/snapshot ve üç stop araştırma ucu 200; kontrol ucu 202 davranışı testlerde doğrulandı |
| Kurtarma | Kontrollü hata sonrası audit=false → RECOVERY_QUEUED → tamamlanmış worker → audit=true |
| Uzun veri akışı | 17.520 sentetik saatlik mum, 564 yön hipotezi ve 144 stop/ufuk/rejim karşılaştırması; ilk 40 yön sonucunda desteklenmiş sinyal yok |
| Stop araştırması | Causal ATR/hacim, purged bölme, seçimde politika seçimi, varyans/etki/bütçeye göre n; az veri anlamlı sayılmadı |
| Stop takip güvenliği | Mum içi belirsizlik, açılış boşluğu, sabit gelecek giriş tamponu, geç yayın, veri boşluğu, ilk planın korunması ve kümülatif sayaç idempotency |
| Stop sonrası ölçüm | Gerçek trade ID boşluğu dönüşü bekletir; toparlanma kapatılmış zararı/hedef zamanını değiştirmez; 48 saat sınırı korunur |
| Manuel olay bağlamı | Kayıt anı göstergeleri sabit; UUID tekrarında korunur, ileri tarihli gösterge kayda girmez |
| Bulut stop entegrasyonu | Moto ile gerçek pipeline S3 snapshot/deneme ve DynamoDB stop_trained_at/audit alanlarını doğruladı |
| Canlı kaynak | Bu ortamdan Binance TR HTTP 451; canlı veri uçtan uca doğrulanamadı |
| Canlı AWS | Hesap kimliği olmadığından dağıtılmadı; IAM, gerçek bölge erişimi ve bulut çalışma süresi canlı doğrulanmadı |
| Ayrı GitHub yayını | Yeni hedef depo belirtilmedi; kaynak depoya yazma/push yapılmadı |

## Kritik testlerin kapsamı

- Gelecek mumları değiştirmek geçmiş özellikleri değiştirmiyor.
- Eğitim/seçim/test sınırları, etiket son zamanlarına göre ayrılıyor.
- Ufuk örnekleri UTC'ye sabit ve sonuç pencereleri örtüşmüyor.
- Tasarım güç/etki ve çoklu arama gereken n'yi belirliyor; serial dependence etkin n'yi düşürüyor.
- Sentetik öğrenilebilir ilişki model tarafından öğreniliyor; az/rastgele veri anlamlı hipotez sayılmıyor.
- Başarısız optimizasyon eski modeli koruyor ve hata raporluyor.
- Açık/geçersiz/eksik mumlardan inference üretilemiyor; kısmi backfill tamamlandı denmiyor.
- Lease süresi dolmuş worker güncel sonucu ezemiyor; başarısız işler son iyi snapshot'ı bozmuyor.
- Manuel kayıt UUID tekrarında idempotent; farklı seviyelerle aynı UUID reddediliyor.
- Gerçek işlem ID boşluğu hedef başarısı sayılmıyor; 48 saat sonrası hedef TIMEOUT sayılıyor.
- Gelecek etiketi yalnızca tam hedef kapanış zamanı geldiğinde sonuçlanıyor.
- İlk yayınlanan forward kayıtları tekrar işler/yeniden tarama ile çoğalmıyor veya değişmiyor.
- Dispatcher frontend gerektirmiyor; DLQ mesajını başarılı enqueue sonrası siliyor.
- Yetkisiz kontrol çağrısı reddediliyor; kurtarma kabulü tamamlanma gibi sunulmuyor.
- Öğrenilebilir sentetik zarar azaltma ilişkisinde stop karşılaştırması kanıt eşiğini geçebiliyor; bunun kârlılık olmadığı ayrı işaretleniyor.
- Ham denemeler budanınca kümülatif stop sayaçları kaybolmuyor; tamamlanmamış denemeler budanmıyor.

Tekrar çalıştırma:

```bash
PYTHONPATH=backend_next OPENBLAS_NUM_THREADS=1 .venv/bin/pytest tests -q -W error
PYTHONPATH=backend .venv/bin/python -m unittest discover -s backend -v
.venv/bin/ruff check backend_next tests
sam validate --lint --template-file infra/template.yaml --region eu-central-1
sam build --template-file infra/template.yaml --parallel
```

Legacy test için ayrıca `pip install -r backend/requirements.txt` gerekir. Mobilde `npm ci`, `npx tsc --noEmit`, `npx expo lint`, `node scripts/test_paper.cjs`, `node scripts/test_backend_control.cjs`, `node scripts/test_phone_bridge.cjs`, `node scripts/test_trade_plan.cjs`, `node scripts/test_score_refresh.cjs`, `npx expo export --platform android` çalıştırılır. Kaynak depodaki eksik/uyumsuz mobil peer kilidi SDK 57'nin önerdiği bağımlılıklarla düzeltildi; sürüm ve Android package ayrı Lab uygulaması olarak ayarlandı.

Yerel imzalı APK derlemesi [APK_BUILD.md](APK_BUILD.md) içindedir. Teslim dosyası `Oran-Radar-Lab-2.1.0.apk`, 49.091.648 bayt; SHA-256 `8cccedc54546cf0d169e6dc1ace116d09a02e4878f5acf9f7050c238a4589277`. APK içine backend adresi/anahtarı gömülmedi; ayarlardan bağlanır.

İlk stop backend tesliminde 2.0 APK değiştirilmemişti. 2026-10-11 tarihli 2.1 APK stop ekranlarını içerir ve bağlantısız durumda kontrol düğmesini görünür tutar. Yeni paket önceki Lab sürümüyle aynı imza anahtarını kullanır; 2.0 dosyası ayrıca korunmuştur. SAM template stop parametreleriyle validate --lint ve cached build geçti; Worker/Api/Scheduler paketlerindeki bütün radar Python dosyaları güncel kaynaklarla byte-for-byte karşılaştırıldı. Canlı AWS dağıtımı yapılmadı.

Sentetik demo gözlemleri ve Moto testleri gerçek piyasa kârlılığı veya canlı altyapı doğrulaması değildir. Üretime geçiş için DEPLOYMENT.md içindeki kaynak erişimi, API auth ve frontend kapalı çalışma kontrolleri tamamlanmalıdır.

Lab 3.0 telefon motorunun tasarımı ve gerçek cihaz/Drive test sınırları [PHONE_BACKEND.md](PHONE_BACKEND.md) içinde. Önceki 2.1 APK doğrulaması yukarıda tarihsel olarak korunmuştur.

## Lab 3.0 telefon APK doğrulaması — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.0.0.apk`: 65.776.226 bayt, SHA-256 `8e0586fc5907297441947441f24354805bb1a5d1026c06cc1bb42ce585d8f9b9`.
- `com.oranradar.lab`, versionCode 3, Android API 24–36, yalnızca ARM64. Önceki 2.1 ARMv7 desteği bu Python 3.13 paketinde yoktur.
- Yerel Expo modülü, Android bildirimli servis, WorkManager, Drive dosya seçicisi ve telefon Python motoru derlendi.
- Önceki Lab sertifikasıyla v2/v3 APK imzası doğrulandı; ZIP CRC, 3 DEX, Hermes checksum ve ZIP 16 KB hizalaması geçti.
- 31 APK ARM64 kütüphanesi ve Python arşivlerindeki 67 ELF kütüphanesi 16 KB LOAD hizalamasını geçti.
- Python 3.13 bytecode karşılaştırması, paketlenmiş 18 radar modülünün teslim edilen kaynakla eşleştiğini doğruladı. Telefon başlatma, Drive seçim ve kontrol düğmesi metinleri Hermes içinde doğrulandı.
- Son kaynakla 56 pytest testi geçti; yeni gösterge arşivi kaydı, tekrar işte tekillik ve tutarlı ZIP yedeği kontrol edildi. Mobil native köprü testi; telefon API yönlendirmesi, bulut anahtarının taşınmaması ve seçim iptali davranışını doğruladı.
- SAM cached build geçti; Worker, Api ve Scheduler paketlerindeki radar kaynakları güncel dosyalarla eşleşti. AWS dağıtımı yapılmadı.
- Fiziksel Android cihazı, gerçek piyasa toplama, hesap/klasör seçimi ve Drive bulut senkronizasyonu uçtan uca denenmedi. Telefon kapanınca veya Zorla durdur yapılınca çalışılamaz; Android iş zamanlaması sürekli çalışma garantisi değildir.

Drive APK dosyası: https://drive.google.com/file/d/1FcSkXgB_8BnAAE-ofEgoQteLAqijUxx2/view?usp=drivesdk


## Lab 3.1 tüm coinler ve Rally Score — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.1.0.apk`: 65.796.706 bayt; SHA-256 `25c314963c2f6b097462b17ba1c77451e3f55367281534e1b7ee916803098375`.
- `com.oranradar.lab`, versionCode 4, ARM64, API 24–36; önceki Lab sertifikasıyla v2/v3 imza doğrulandı.
- ZIP CRC, 3 DEX, Hermes checksum ve 16 KB ZIP hizalaması; 31 APK + 67 Python ARM64 ELF LOAD hizalaması geçti. Paketlenmiş 19 Python modülü teslim kaynaklarıyla karşılaştırıldı.
- Tüm coinler, Rally Score, coin araması, v3.1, kontrol ve Drive seçim metinleri paket içinde doğrulandı. TypeScript, Expo lint, paper/kontrol/native köprü testleri geçti.
- 64 backend testi ve NumPy 1.26.2 / telefon sayısal ortamında 58 test geçti. Orijinal Rally fonksiyonuyla 15 piyasa bağlamında eşitlik; açık mumların dışlanması; mum boşluğunda puanın tutulması; 128 aktif düşük hacimli/stable TRY paritesinde sayı kesilmemesi; 31 coinlik süreli taramada diskten tekrar başlayıp her coine ulaşılması; aktif paper önceliğinin normal coinleri aç bırakmaması; keşif hatasında listenin korunması; 429 sonrası kalıcı Retry-After beklemesi; eski puanların işaretlenmesi; coin listesi/sıra/puanların ZIP yedeği kontrol edildi.
- SAM validate --lint ve cached build geçti; Worker/Api/Scheduler kaynak dosyaları güncel dosyalarla eşleşti. AWS dağıtılmadı.
- Canlı keşif ve fiyat uçlarına bu çalışma ortamından erişim HTTP 451 ile engellendi. Telefonun kendi ağından erişim, kurulum, uygulama arka planı ve Drive senkronizasyonu fiziksel cihazda denenmedi. Masaüstü testleri ve derleme bu kontrollerin yerine geçmez.

Drive APK dosyası: https://drive.google.com/file/d/1PP_9jGaCLrV26ZIflja16X3ZeSNWC9Vn/view?usp=drivesdk

## Lab 3.2 alış planı ve coin başına sanal kayıt — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.2.0.apk`: 65,800,802 bayt; SHA-256 `ea46fd13af399655223ab51e1edff8de8afdaced23a99c40e33a9019c099ce27`.
- `com.oranradar.lab`, versionCode 5, ARM64, API 24–36; önceki Lab sertifikasıyla v2/v3 imza doğrulandı. ZIP CRC, 3 DEX, Hermes checksum ve 16 KB ZIP hizalaması; 31 APK + 67 Python ELF LOAD hizalaması geçti. Paketlenmiş 20 Python modülü teslim kaynaklarıyla karşılaştırıldı.
- Alış/hedef/stop, coin başına sanal kayıt, v3.2, kontrol, Drive ve Rally Score metinleri paket içinde doğrulandı. TypeScript, Expo lint ve dört Node davranış kontrolü geçti.
- 73 backend testi; telefon NumPy 1.26.2 / taşınabilir sayısal ortamında 67 test; orijinal backend 10 test geçti. Ön plan açık/gelecek mumları dışlıyor; eğitim medyanı sonraki dönemden etkilenmiyor; yetersiz veride alış olasılığı uydurmuyor. Yalnızca destekli 48 saatlik stop mesafesi uyarlanıyor ve farklı giriş planında kârlılık doğrulandı sayılmıyor. Eski/geçersiz fiyat, mum boşluğu ve aşırı spread güncel plan üretmiyor.
- Gerçek telefon cycle yolu taklit piyasa verisiyle, uzun araştırma tamamlanmadan iki coinin fiyat planını API'ye veriyor. Ayrı coin kayıtları öneri bağlamını koruyor; UUID tekrarında değişmiyor. İlk veri bekleme sebebi korunuyor. Deep pipeline planı şarj olmadan kaydediyor; aynı gözlemle tekrar iş ledger'ı çoğaltmıyor.
- Mobil test, kapalı analiz kartında üç fiyatın görünmesini; her formun ilgili coine sabitlenmesini; alış/hedef/stopun dolmasını; kullanıcının bu fiyatları değiştirdiği ve virgüllü stop girdiği kayıt akışını doğruladı. Başka coinin veya eski planın seviyeleri aktarılmıyor.
- SAM validate --lint ve cached build geçti; Worker/Api/Scheduler içindeki 20 radar modülü güncel kaynakla eşleşti. Orijinal GitHub deposuna yazılmadı; AWS dağıtılmadı.
- Fiziksel cihazda kurulum/açılış, gerçek piyasa toplama ve Drive senkronizasyonu denenmedi. Bu ortamda canlı kaynak erişiminin HTTP 451 sınırı devam ediyor. Ön fiyat planı gerçek limit dolumu veya kârlılık kanıtı değildir.

Drive APK dosyası: https://drive.google.com/file/d/1kGbiXj-x4xBf0pp2FvQyyuVAFIEN3Kft/view?usp=drivesdk

## Lab 3.3 bağımsız puan güncellemesi — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.3.0.apk`: 65821282 bayt; SHA-256 `7cf57b0d4188af2a88c08b50d0d385804d73d1d982e5a91fae23afaa73285b0f`. `com.oranradar.lab`, versionCode 6, ARM64, API 24–36; aynı Lab sertifikasıyla v2/v3 imza doğrulandı.
- ZIP CRC, 3 DEX, Hermes checksum ve 16 KB ZIP hizalaması; 31 APK + 67 Python ELF LOAD hizalaması geçti. Paketlenmiş 20 Python modülü güncel kaynakla eşleşti. Native `refreshScores` köprüsü ve puan durum anahtarları DEX'te; yenileme düğmesi, son tarama zamanı, v3.3 ve önceki alış/stop/kayıt/kontrol/Drive metinleri Hermes'te doğrulandı.
- 79 backend testi, telefon NumPy 1.26.2 / taşınabilir ortamda 73 test; orijinal backend 10 test geçti. TypeScript, Expo lint ve beş Node davranış kontrolü geçti. Telefon uyumluluk ortamına APK ile aynı `certifi==2025.10.5` eklenerek TLS/hız limiti yolu da test edildi.
- Puan-only iş iki turda API puanını/zamanını yeniliyor ve araştırma çağırmıyor. Tek coinlik süre bütçesinde görünür coin önceliği sürerken 31 coinin tamamına ulaşılıyor. Gerçek eşzamanlı thread testinde araştırma eski metadata ile başlayıp bekliyor; yeni puan turu yazabiliyor; araştırma bitince yeni ticker, puan sırası, tarama zamanı ve Retry-After ezilmiyor.
- HTTP 429 beklemesi kaynak istemcisinde hemen saklanıyor; diğer iş yolu sonraki HTTP isteğini göndermiyor. Puan hatasında son başarılı değer/zaman korunuyor. Cache aynı dakika/saatte yalnızca emir defterini yeniden çağırıyor; yeni dakika/saatte doğru mum uçlarını yeniliyor. Aynı puanın yeni zamanda yeniden hesaplanması geçerli sonuç olarak test edildi.
- UI testi görünür coin referansı değişince yeni coinin gönderilmesini, aktif uygulamada periyodik yenilemeyi, arka planda ekran önceliğinin temizlenmesini, öne dönüşü, duraklatılmış motoru, kuyruk/çalışıyor/bekleme geri bildirimini ve hatayı doğruladı. Kuyruk kabulü tamamlanma gibi gösterilmiyor. CI'ye mevcut ve yeni beş mobil davranış kontrolü eklendi.
- SAM validate --lint ve cached build geçti; Worker/Api/Scheduler içindeki 20 radar modülü kaynakla eşleşti. Orijinal GitHub deposuna yazılmadı; AWS dağıtılmadı.
- Fiziksel Android cihazında gerçek 30 saniyelik tarama süresi, yeni piyasa verisi, arka plan servisi ve Drive senkronizasyonu denenmedi. Bu ortamın Binance TR HTTP 451 sınırı devam ediyor. 30 saniye tüm coinler için garanti değildir; ağ, tur büyüklüğü, borsa ve Android sınırları geçerlidir.

Drive APK dosyası: https://drive.google.com/file/d/1addxmGkPFwgIHZIfUZZvn7T0AHFsjdAy/view?usp=drivesdk


## Lab 3.4 sabit alış seviyeleri ve limit aday araştırması — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.4.0.apk`: 65.825.378 bayt; SHA-256 `a109fb029acb44db5d2f8f434661bb377334d32eca19156b51e2de6b94f7ec51`. `com.oranradar.lab`, versionCode 7, ARM64, API 24–36; önceki Lab sertifikasıyla v2/v3 imza doğrulandı.
- ZIP CRC, 3 DEX, Hermes checksum, 16 KB ZIP ve 31 APK + 67 Python ELF hizalaması geçti. Paketlenmiş 21 Python modülü son kaynakla semantik bytecode karşılaştırmasını geçti. Alış araştırması/adayı karşılaştırması, eksik/gecikmiş alış araştırması, v3.4 ve önceki alış/stop/kayıt/puan yenileme/kontrol/Drive metinleri Hermes içinde doğrulandı.
- 88 backend testi `-W error` ile; NumPy 1.26.2/telefon sayısal ortamında 82 test; legacy backend 10 test geçti. TypeScript, Expo lint, ruff ve beş Node davranış kontrolü geçti. Dokuz yeni backend testi ve mevcut plan/UI testlerinin güncel senaryoları alış düzeltmesini doğruladı.
- Sentetik tekrar eden %2 geri çekilme/iyileşme verisinde seçici %2 adayını seçiyor; %3 dolmayan emri ve masraflı hemen alışı ayırıyor. Dolmayanlar karar başına sıfırla ortalamaya giriyor. Yeni ayrı test sonuçlarını değiştirmek aday seçimini değiştirmiyor. Kronolojik bölüm sınırları ve 55 saatlik ankrajlar örtüşmüyor; veri boşluğu örnekleri atlanıyor.
- Aynı mumda limit dolumunun öncesinde oluşmuş olabilecek high kesin hedef sayılmıyor; açıkta dolum, stop gap'i, daha iyi gap alış fiyatı ve altı saatlik dolum sonu doğrulandı. Açık/gelecek mumlar seviye üretmiyor. Kotasyon değişince entry/stop/target ve plan kimliği korunuyor; yeni kapalı mumda seviye değişebiliyor; fiyat alış seviyesinin altına inince eski aday forma aktarılmıyor. Eski v1 planları güncel sayılmıyor.
- Gereken n güç/etki/varyans/hassasiyet, çoklu aday/coin/tur bütçesine göre değişiyor. Etki hedefini küçültmek gereken n'yi azaltmıyor. Yeni mum yoksa tekrar araştırma turu açılmıyor. Şarj bekleme, maliyet/coin sayısı tasarım değişimi, kalıcı rapor, SQLite tur kaydı, yeniden başlatma, Drive ZIP içindeki rapor ve kontrol/kurtarma kuyruğu doğrulandı. Puan-only yol tarihsel optimizasyon çağırmadan kayıtlı raporu kullanıyor.
- Mobil davranış testi kapalı kartta seçilen kuralı, örnek/kanıt durumunu ve ayrı test sonuçlarını; açık kartta aday karşılaştırmasını; güncelliği kaybolmuş alışın forma aktarılmamasını doğruladı. Coin başına sanal kayıt ve düzenlenebilir alış/hedef/stop önceki davranışı koruyor.
- SAM validate --lint ve cached build geçti; Api/Worker/Scheduler içindeki 21 radar kaynağı güncel kaynakla eşleşti. Orijinal GitHub deposu değiştirilmedi; AWS dağıtılmadı. Önceki APK ve kaynak sürümleri korundu.
- Fiziksel Android cihazında kurulum, gerçek piyasa toplama, arka plan süreleri ve Drive senkronizasyonu denenmedi. Bu ortamın önceki canlı Binance TR HTTP 451 sınırı nedeniyle canlı dolum/optimum kanıtı iddia edilmez. Yeni araştırma geçmiş sanal sonuçtur; tam limit dolumu, geçmiş maliyet/spread ve gelecekte üstünlük garanti değildir. Drive'a yüklenen APK'nın metadata boyutu yerel dosyayla karşılaştırıldı.

Drive APK dosyası: https://drive.google.com/file/d/100p1aJHzzxvYjxqDL9UDpr8vR45CBG_4/view?usp=drivesdk


## Lab 3.5 olgun araştırma puanı — 2026-10-11

- İmzalı `Oran-Radar-Lab-3.5.0.apk`: 65.845.858 bayt; SHA-256 `c5478ab26c03b2c7389e98bbb9d3fc878fe8c452e9cde99e08ba07d33d131c6f`. `com.oranradar.lab`, versionCode 8, ARM64, API 24–36; önceki Lab sertifikasıyla v2/v3 imza doğrulandı. Drive metadata boyutu yerel APK ile eşleşti.
- ZIP CRC, 3 DEX, Hermes checksum, 16 KB ZIP ve 31 APK + 67 Python ELF hizalaması geçti. Paketlenmiş 22 radar Python modülü son kaynakla semantik bytecode karşılaştırmasını geçti. Temel puan/araştırma etkisi, olgunlaşma/devrede/duraklatılmış durumu, puan katkısı ayrıntısı, v3.5 ve önceki alış/kayıt/puan yenileme/kontrol/Drive metinleri Hermes'te doğrulandı.
- 105 backend testi `-W error` ile; NumPy 1.26.2/telefon ortamında 99 test; legacy backend 10 test geçti. Son örnek gösterimi düzeltmesi için 17 araştırma puanı testi her iki sayısal ortamda yeniden geçti. TypeScript, Expo lint, ruff ve beş Node davranış kontrolü geçti.
- On tarama/yetersiz forward örneği puan değiştirmiyor. Olgun sentetik yükseliş/düşüş modeli puanı iki yönde değiştiriyor; tekrar okuma katkıyı katlamıyor; 0–100 sınırı ve gerçek değişim korunuyor. Drift, kötü kalibrasyon/Brier, eski tahmin/araştırma/forward sonuç, yanlış sürüm/tasarım veya eğitim hatası katkıyı kaldırıyor. Baseline ve eski sürüm örnekleri yeni sürümü olgunlaştırmıyor.
- Alış katkısı yeterli etkin net/fark n, belirgin ayrı test sınırları ve aynı güncel planı istiyor; belirsiz sınır veya eski plan puan değiştirmiyor. Açık olumsuz tarihsel alış sonucu negatif katkı yapabiliyor. Stop katkısı aynı rejim/politika/tasarım/source ve yeterli tarihsel/forward kanıtı istiyor; yanlış rejim, az forward n, caution ve veri boşluğu katkıyı kapatıyor. Demo, eksik geçmiş, başka coin ve eski/gelecek/hatalı piyasa verisi katkı vermiyor.
- Gerçek telefon score-only yolu taklit piyasa ve kontrollü olgun kanıtla araştırma eğitimi çağırmadan toplam puanı güncelliyor. Yeniden başlatmada SQLite/RALLY kaydındaki temel puan, katkı ve kanıt durumu korunuyor. Yeni drift raporu gelince GET, yeni exchange taraması olmadan temel puana dönüyor ve son piyasa tarama zamanını değiştirmiyor.
- Korunan eski model, yeni aday kendisini yenemediğinde de güncel ayrı testte baseline'a üstünlük belgesini kazanıyor; eski model nesnesi/bağlamı mutasyona uğramıyor. Promotion ve aktif-model kabul kontrolü aynı tur model bütçesini bölüşüyor. Yeni kabul kontrolü geçmeyen belgenin korunmasına izin verilmiyor.
- Mobil davranış testi kartta temel/katkı/devrede durumunu, ayrıntıda aile katkısını ve etkin/gereken n'yi; olgunlaşma ve duraklatma metinlerini doğruladı. Önceki coin başına düzenlenebilir alış/hedef/stop ve skor yenileme davranışları korundu.
- SAM validate --lint ve cached build geçti; Api/Worker/Scheduler içindeki 22 radar kaynağı son kaynakla eşleşti. Orijinal GitHub deposu değiştirilmedi; AWS dağıtılmadı. Önceki APK/kaynak sürümleri korundu.
- Fiziksel Android cihazında kurulum, canlı coin taraması, arka plan süreleri veya Drive senkronizasyonu uçtan uca denenmedi. Araştırma katkısı bir endeks kuralıdır; olasılık, optimum ağırlık, birleşik istatistiksel garanti veya gelecekte kârlılık kanıtı değildir. Alış katkısı tarihsel sanal kanıttır; yön ve stopun forward olgunluk kontrolleriyle aynı tür doğrulama diye sunulmaz.

Drive APK dosyası: https://drive.google.com/file/d/16juQChO579A7Dv6gzG2YAL1cf5iW2H3C/view?usp=drivesdk


## Lab 3.6 sanal alış/stop formu — 2026-10-12

- İmzalı `Oran-Radar-Lab-3.6.0.apk`: 65.854.050 bayt; SHA-256 `baa18a2c788932eac225c657be80b4ae105b9e1c7885515792f49e673d651d9e`. `com.oranradar.lab`, versionCode 9, ARM64, API 24–36; aynı Lab sertifikasıyla v2/v3 imza doğrulandı. Drive metadata boyutu yerel APK ile eşleşti.
- ZIP CRC, 3 DEX, Hermes checksum, 16 KB ZIP ve 31 APK + 67 Python ELF hizalaması geçti. Paketlenmiş 22 radar Python modülü güncel kaynakla semantik bytecode karşılaştırmasını geçti. Piyasa fiyatı, önerilen limit alış/stop, son seviyeleri manuel kullanma, öneri yenileme/uygulama, v3.6 ve olgun araştırma puanı/önceki özellik metinleri Hermes içinde doğrulandı.
- 107 backend ve NumPy 1.26.2/telefon ortamında 101 test `-W error` ile geçti. TypeScript, Expo lint, ruff ve mevcut beş Node davranış kontrolü geçti. Ek form yarış/hata senaryoları güncel Node trade-plan kontrolünde ayrıca geçti. Legacy backend kaynakları ve önceki 10 testin doğrulanmış davranışı değiştirilmedi.
- Verisiz/eski/offline taslak, alış alanını piyasa fiyatıyla veya hedefi +%3 ile doldurmuyor. Güncel üç fiyat birlikte aktarılıyor. Son planın stopu/form gerekçesi görünür kalıyor; eski üç seviye yalnızca açık manuel seçimle kopyalanıyor. Genel form `/market` son raporunu okuyor ve stopu da dolduruyor.
- Gecikmiş yenileme, değiştirilmiş alış/hedef veya bilinçli boş bırakılmış stopu ezmiyor. Eski coinin yanıtı yeni coinin seviyelerini değiştirmiyor. Ağ hatasında son stop görünür kalıyor ve quote alış yerine geçmiyor. Tam sayı/virgüllü stop ve coin başına düzenlenebilir kayıt önceki kontrolleri geçmeye devam ediyor.
- API `/pairs`, `/market` ve snapshot aynı alış/stop/plan kimliğini taşıyor; plan girişi doğrudan piyasa fiyatı değil. Daha yeni ticker girişin altına inince API ve form eski seviyeyi güncel saymıyor; seviyeler/id ve arşivdeki ilk plan korunuyor. Detay formu opportunity planını da iletiyor.
- Küçük coin fiyatı/giriş farkı, sabit altı ondalıkta birleşen iki fiyat çiftiyle test edildi; 10 anlamlı basamakta ayrı gösteriliyor. Hesap/depodaki değerler ve kullanıcı girişleri bu gösterimle yuvarlanmıyor.
- SAM validate --lint/cached build geçti; Api/Worker/Scheduler içindeki 22 radar modülü güncel kaynakla eşleşti. Olgun araştırmanın puana etkisi 3.5'ten korunuyor. Orijinal GitHub deposu değiştirilmedi, AWS dağıtılmadı; 3.5 ve önceki APK/kaynaklar korundu.
- Fiziksel cihazda form/ağ/kurulum ve gerçek piyasa akışı denenmedi. Son planı okumak yeni exchange taraması veya eğitim tamamlandı demek değildir. Güncel veri yoksa alış/stop için bekleme sebebi gösterilir; optimum/kârlılık veya gerçek dolum iddia edilmez. Manuel kayıt hâlâ şimdi alınmış maliyet varsayımıdır.

Drive APK dosyası: https://drive.google.com/file/d/1HW5TKFd_ukqHfA3pCAMNKyXhoClzesT2/view?usp=drivesdk


## Lab 3.7 bağımsız canlı fiyat akışı — 2026-10-12

- İmzalı `Oran-Radar-Lab-3.7.0.apk`: 65.862.242 bayt; SHA-256 `91530e7dab6075ae2e9b797131abb34122b3e9c03fc5677e8281fa9243b5bf00`. `com.oranradar.lab`, versionCode 10, ARM64, API 24–36; aynı Lab sertifikasıyla v2/v3 imza doğrulandı. Drive metadata boyutu yerel APK ile eşleşti.
- ZIP CRC, 3 DEX, Hermes checksum, 16 KB ZIP ve 31 APK + 67 Python ELF hizalaması geçti. Paketlenmiş 23 radar modülü güncel kaynakla semantik bytecode karşılaştırmasını geçti. Canlı fiyat/REST yedeği, fiyat yaşı, gecikme kontrolü, v3.7 ve önceki özellik metinleri Hermes'te doğrulandı. Native fiyat köprüsü/cache/protokol/WS URL, last_ws_quote_at ve fiyat kurtarma anahtarları DEX'te doğrulandı.
- 113 backend, NumPy 1.26.2/telefon ortamında 107 test `-W error` ile geçti. TypeScript, Expo lint, ruff ve altı Node davranış kontrolü geçti. Son akış kontrolü/derleme kaynağıyla tip/lint ve live-price/kontrol/trade-plan Node kontrolleri tekrar geçti. Native `:radar-phone:testReleaseUnitTest`: beş PriceBook testi, sıfır hata/başarısızlık. CI'ye yeni Node canlı fiyat kontrolü eklendi.
- Native testler 201 TRY fiyatında sayı sınırı olmamasını, aynı fiyatın yeni event zamanında yeni gözlem olmasını, invalid/non-TRY/nonpositive/NaN/future/old/control frame'lerin atlanmasını doğruladı. Eski/eşit frame ve geciken REST yeni WS fiyatını ezmiyor. Wrapped payload, cache restore'da önceki zaman/source, snapshot mutasyon izolasyonu ve hata yanıtının eski fiyatı koruması geçti.
- Python native-cache doğrulaması invalid/future/başka venueyi dışlıyor; bozuk/yarım dosya eski state'i bozmuyor. Yeni native fiyat, eski metadata'ya üstün geliyor; metadata dosyası okuma sırasında mutasyona uğramıyor. Araştırma lease'i başka gerçek thread'de beklerken iki fiyat değişimi API'ye geçiyor; score zamanı/anchored entry-stop değişmiyor. Entry altına inince aynı plan stale oluyor.
- Native REST backoff Python HTTP isteklerini de bekletiyor. Taze native fiyat varken puan-only iş ikinci toplu ticker isteği atmıyor. Native cache mevcut Drive ZIP'ine giriyor; tekrar fiyat okumaları mum/model/forward n yaratmıyor.
- Node kontrolü bağımsız native poll'ların aktif uygulamada sürmesini, arka planda UI lease'inin bırakılmasını, öne dönüşü, hata sırasında son değer/zamanın korunmasını ve cleanup'ı doğruladı. Price overlay score/candle zamanlarını değiştirmiyor; malformed/future/başka kaynak atlanıyor; crossing anchored planı stale yapıyor; fiyat yaşı eskimeyi gösteriyor. Mevcut formda manuel değer/stop ve önceki score/kontrol davranışları korundu.
- Native `prices` hazır bellek snapshot'ını main queue'da okur; Python/GIL araştırma kuyruğuna girmez. Servis START_STICKY'yi yalnızca enabled durumunda ister; pause/timeout sınırları korunur. Kontrol audit'i ilk fiyat bekleme, fiyat gecikmesi veya hızlı akış duraklamasını ayrıca gösterir; kurtarma bağlantıyı tekrar dener, Retry-After aşılmaz. Android foreground/WorkManager yaşam döngüsü gerçek cihazda denenmedi; native unit test parser/order/restore doğrulamasıdır.
- SAM validate --lint/cached build geçti; Api/Worker/Scheduler içindeki 23 radar modülü güncel kaynağa eşleşti. Orijinal GitHub deposu değiştirilmedi; AWS dağıtılmadı. Önceki APK ve kaynaklar korundu.
- Bu ortamdan REST ve stream HTTPS uçlarına denemeler HTTP 451 döndü. Fiziksel telefonda gerçek WS/TLS bağlantısı, fiyat gecikmesi, ağdan dönme, arka plan süresi ve Drive cloud sync uçtan uca doğrulanmadı. İki saniye UI cache okuma hedefidir; gerçek exchange yayın sıklığı/sürekli fiyat değişimi veya 24 saat sınırsız Android arka plan garantisi değildir. Mini ticker gerçek fill/stop sonucu veya bağımsız araştırma örneği sayılmaz; Drive'a son native snapshot girer, sonsuz ham frame arşivi değildir.

Drive APK dosyası: https://drive.google.com/file/d/1o63qqN-5OarYiGxWyqukLcgWvOqegu7a/view?usp=drivesdk
