# Oran Radar — Binance TR paper trading MVP

Android öncelikli fırsat radarı. Binance TR spot TRY piyasasındaki likit pariteleri izler; adayları, giriş seviyelerini, hedef/stop seviyelerini ve sonradan gerçekten görülen fiyat temaslarını kaydeder. Gerçek emir göndermez.

## Çalıştır

Python 3.11+ ve Node 22+:

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

`http://localhost:8787/health` ile veri durumunu, `/opportunities` ile üst 10 adayı kontrol edin. İlk websocket paketinden sonra ilk tam tarama en çok 5 dakika içinde başlar. Geliştirmede `RADAR_SCAN_SECONDS=30` kullanılabilir; çok sık tarama borsa limitlerini artırır.

```bash
cd mobile
npm ci
npx expo start
```

APK ilk açılışta doğrudan Binance TR public websocket akışını kullanır. Likit TRY fiyatlarını ve kapalı mumlar/derinlik verilerinden gelen açıklanabilir ön radar skorlarını gösterir. Bu cihaz modu WATCH/SCOUT gösterir; ENTRY üretmez ve başarı olasılığı iddia etmez. Cihaz modu yalnızca uygulama açıkken çalışır.

Kalıcı paper tracking için uygulamanın ⚙ menüsüne sunucu adresini girin. Android emülatöründe `http://10.0.2.2:8787`, fiziksel telefonda aynı Wi-Fi ağındaki bilgisayarın `http://192.168.x.x:8787` adresini kullanın. Adres kaydedilmeden önce PAPER_ONLY health yanıtı doğrulanır. Sunucu varsayılan olarak `0.0.0.0:8787` dinler; yalnızca güvenilir ağda kullanın. İnternetten erişimde HTTPS kullanın ve RADAR_API_TOKEN tanımlayın; APK ayarlarında erişim anahtarı alanını doldurun. Teslim edilen release APK JavaScript paketini içerir; Metro veya Expo Go gerekmez.

## Push kurulumu

1. Expo EAS projesi açıp `mobile/app.json` içinde `expo.extra.eas.projectId` değerini kendi project ID'nizle doldurun.
2. Android FCM kimlik bilgilerini EAS'e ve `google-services.json` dosyasını Expo Android konfigürasyonuna tanımlayıp `npx eas-cli@latest build --profile preview --platform android` ile release APK oluşturun.
3. Fiziksel Android cihazda uygulamanın ⚙ menüsünden push kaydı yapın.
4. Sunucuyu `RADAR_PUSH_ENABLED=1 python main.py` ile açın. Sunucunun dışarıya Expo Push Service erişimi gerekir. Bildirimler kullanıcının işlem yaptığına dair kanıt değildir.

Teslim edilen APK'de Expo proje/FCM kimlik bilgileri bulunmadığından uzaktan push henüz etkin değildir. Sunucu da yayınlanmamıştır; otomatik paper tracking sunucuyu çalıştırıp adresini bağladığınızda etkin olur.

## Konfigürasyon

| Değişken | Varsayılan | Anlamı |
| --- | ---: | --- |
| `RADAR_DB` | `radar.sqlite3` | Kalıcı SQLite yolu |
| `RADAR_SCAN_PAIRS` | 20 | En likit kaç TRY paritesi |
| `RADAR_MIN_QUOTE_VOLUME_TRY` | 1000000 | Minimum 24s TRY hacmi |
| `RADAR_SCAN_SECONDS` | 300 | Tarama aralığı |
| `RADAR_MAKER_FEE_BPS` | 10 | Sanal giriş komisyon varsayımı |
| `RADAR_EXIT_FEE_BPS` | 10 | Sanal çıkış komisyon varsayımı |
| `RADAR_SLIP_BPS` | 8 | Sanal çıkış kayma varsayımı |
| `RADAR_PUSH_ENABLED` | 0 | Bildirim teslimatını aç |

Ücretler kullanıcının Binance TR hesabından okunmaz; hesap komisyonunuza göre ayarlayın. Veritabanını yedekleyin ve sunucuyu sürekli açık tutun. Gaps `/health` içindeki `blocked_pairs` alanında görünür.

## Mevcut sürümün dürüst sınırı

Skor açıklanabilir bir kural setidir; sayısal oranlar bağımsız tarihsel pencerelerden gelen **ön kalibrasyon** oranlarıdır. Henüz eğitilmiş çoklu model, ayrı walk-forward test, gerçek emir gerçekleşmesi, iki borsa türünün tam kapsaması, futures/on-chain verisi ve tam üretim altyapısı yoktur. İlk sonuçlar gerçek ileriye dönük paper tracking olarak kaydedilir. Genişletme tasarımı ve veritabanı ayrıntıları [ARCHITECTURE.md](ARCHITECTURE.md) içindedir.

## v1.0.2: Manuel sanal alış

Radar/Aktif ekranında `+ Aldım gibi kaydet` düğmesiyle Binance TR paritesini seç, alış maliyetini ve hedefi gir. Başlangıç +%3 hedefi sadece düzenlenebilir örnektir; tahmin değildir. Stop isteğe bağlıdır. Her kayıt UUID taşır ve 48 saatlik sayaç kayıt anında başlar. Geçmiş alış saatlerini geriye dönük beyan etme bu sürümde yoktur.

- Hedef görülürse ilk gerçek Binance TR işlem zamanı, alıştan hedefe süre, brüt ve toplam %0,20 komisyon varsayımı sonrası getiri gösterilir.
- Stop önce görülürse sonraki hedef teması başarı sayılmaz.
- 48 saat dolduğunda ilk doğrulanmış sonrasındaki işlem fiyatıyla kapanır; tam 48. saat fiyatı varsayılmaz.
- Cihaz kayıtları AsyncStorage içinde saklanır; yeniden açılışta aggTrade geçmişi sıra numaralarıyla tamamlanır. Erişilemeyen veya eksik veri hedef sonucu üretmez.
- Cihaz takibi uygulama arka plana geçince durur; sunucu kayıtları bağlı backend üzerinde takip edilir. Sunucu ve cihaz kayıtları ayrı köken etiketi taşır.
- Kullanıcının seçimleri model sinyali sayılmaz, model kalibrasyon/başarı metriklerine katılmaz.
- Manuel getiri spread veya kayma içermez; gerçek emir dolumu ölçülmez.

Sunucu `/pairs` ve `/paper-positions` uçlarını destekler; `POST /paper-positions` aynı UUID için idempotenttir. Sunucu dağıtım durumu ve ücretsiz plan sınırları [DEPLOYMENT.md](DEPLOYMENT.md) içindedir.

Kontroller: `cd backend && python3 -m unittest discover -v`; mobilde `npx tsc --noEmit`, `npx expo lint`, `node scripts/test_paper.cjs`.
