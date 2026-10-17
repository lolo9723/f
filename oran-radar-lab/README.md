# Oran Radar Cloud 4.0

[Android APK](https://github.com/lolo9723/f/raw/refs/heads/oran-radar-cloud-4.0/oran-radar-lab/downloads/Oran-Radar-Cloud-4.0.0.apk) · [APK derlemesi](https://github.com/lolo9723/f/actions/workflows/oran-cloud-4.yml) · [Gerçek veri kapsamı](cloudflare-coverage.json)

Cloud 4.0, `mobile/` içindeki mevcut Expo/Android uygulamasıyla `cloud/` içindeki yeni tarihsel veri bankası ve API'yi birleştirir. Açılış ekranı Cloud merkezidir. Çakışan sürüm/bağlantı yapılandırmalarında yeni Cloud 4.0 esas alınır. Telefon motoru ve önceki sanal işlem ekranlarına Cloud merkezinden ulaşılır.

## Bu sürümde çalışanlar

- GitHub'da yayımlanmış gerçek veri kapsamını otomatik okuma; API hesabı olmadan rapor görüntüleme. İlk raporda 3.754 Global parite envanteri, BTC/ETH/BNB için 217.954 doğrulanmış mum bulunuyor. Bütün paritelerin geçmişi tamamlanmadı.
- Sekiz Türkçe Cloud sayfası, son aktarım zamanı ve çevrimdışı önbellek. API bağlantısı HTTPS ile doğrulanır; kullanıcı anahtarı Android güvenli deposunda tutulur.
- Cloudflare Worker + D1 kaynakları, gerçek dosya kataloğuna dayalı kontrollü backfill, SHA256 doğrulaması, Parquet, karantina ve kaldığı yerden devam. R2 isteğe bağlıdır.
- Nedensel göstergeler, kronolojik/purged model karşılaştırmaları, maliyetli limit simülasyonu, aday model kayıtları ve kanıt gerektiren model geçişi.
- Mevcut cihaz içi Python/Chaquopy motoru, canlı TRY fiyat takibi ve sanal işlemler korunur.

## APK

`Oran Radar Cloud Test`, sürüm 4.0.0, versionCode 12, paket `com.oranradar.lab.cloud`. ARM64 içindir. JavaScript/Hermes ve telefon motoru APK içine paketlenir; Expo Go/Metro gerekmez. Geliştirme sertifikalı test dağıtımıdır; eski Lab kurulumunun yanına ayrı uygulama olarak kurulur. Yeni paket kimliği, önceki Lab imzalama anahtarına erişim gerektirmeden derlenmesini sağlar.

GitHub Actions derlemesi APK içeriğini, telefon Python kaynak eşleşmesini, imzayı ve 16 KB hizalamayı denetler; başarılı APK'yi `downloads/` altına ve Actions artifact'ına koyar. [Derleme bilgisi](downloads/Cloud-4.0-BUILD.json) kaynak commit'ini ve iş adresini içerir.

## Çevrimiçi hizmet durumu

Varsayılan GitHub kanalı yayımlanmış **tarihsel kapsam raporudur**. Uygulamanın açılmasından bağımsız güncellenen bir piyasa analiz sunucusu bu depoya kod yüklemekle kurulmuş olmaz. Sürekli veri toplama için [Cloud kurulumunu](cloud/docs/SETUP.md) tamamlamak gerekir. Cloud API ekranından gerçek sunucu adresi ve sınırlı kullanıcı anahtarı bağlanabilir.

Bu teslimde Workers/D1/R2/Firebase hesap yayını yapılmadı. TR API bu çalışma ortamında HTTP 451 döndü; engel aşılmadı. Global veri TR işlem fiyatı olarak kullanılmaz. FCM native cihaz kaydı hazırlanmıştır; kapalı uygulamada gerçek bildirim teslimi Firebase yapılandırması ve cihaz testi bekler. Gerçek para emirleri kapalıdır.

## Geliştirme ve test

```bash
cd mobile
npm ci
npx tsc --noEmit
npx expo lint
npm run test:cloud
npx expo prebuild --platform android --no-install
```

Cloud motoru için `cd cloud`, `python -m pip install -e '.[test]'`, `python -m pytest -q`. Ayrıntılar: [Cloud rehberi](cloud/README.md), [API](cloud/docs/API.md), [kotalar](cloud/docs/FREE-TIER.md), [ölçülmüş doğrulama](cloud/reports/VALIDATION.md). Önceki cihaz motorunun ayrıntıları [teknik arşivde](docs/LEGACY_3.7_README.md).
