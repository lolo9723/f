## Cloud 4.0 güncel düzeltme: 4.0.3

Ücretsiz ana + yedek piyasa API'si, PostgreSQL'den bağımsız canlı puanlama, eşik odaklı Optimal sanal alış ve güçlendirilmiş araştırma katkısı. [Canlı kurulum ve kota düzeltmesi](cloud/live/README.md). APK: `downloads/Oran-Radar-Cloud-4.0.3.apk` (CI doğrulamasından sonra yayımlanır). Önceki kasalar silinmedi; eski proje kotası nedeniyle bakiye aktarımı henüz tamamlanamaz. Uygulama bu durumu açıkça gösterir.

# Oran Radar Cloud 4.0

[Android APK](downloads/Oran-Radar-Cloud-4.0.2.apk) · [Canlı panel](https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech) · [Derleme](https://github.com/lolo9723/f/actions/workflows/oran-cloud-4.yml)

Uygulama canlı bulut servisine hazır bağlıdır. Telefon kapalıyken Canlı puan ekranda 10 saniyede bir, ekran kapalıyken puan ve sanal işlem kontrolü dakikada bir güncellenir. Derin tarama ve araştırma 15 dakikada bir sürer. Ücretli plan veya hizmet açılmadı; Neon Free kotaları geçerlidir.

- **Manuel:** Coin, TL tutarı, piyasa/limit alış, hedef ve stop kullanıcı tarafından seçilir. Otomatik yeni alış açılmaz. Açık işlemler bulutta izlenir.
- **Optimal:** Kullanıcı çalışma bütçesini ve risk sınırlarını belirler. Bulut; nihai puanı, benzer tarihsel olayların maliyet sonrası sonucunu, ayrı testi, belirsizliği ve güncel spread'i birlikte değerlendirerek coin, araştırılmış limit fiyatı ve tutarı seçer. Uygun kanıt veya fırsat yoksa nakitte bekler.
- **Sanal kasa:** Başlangıç bakiyesi bir kez belirlenir. Açık ve bekleyen emirler bütçeden birlikte sayılır. Coin yoğunlaşması, işlem/portföy riski, bekleme ve eşzamanlı işlem sınırları korunur. İstanbul gününe göre gerçekleşen net K/Z gösterilir.
- **Sade arayüz:** Radar, Kasa ve Ayarlar. Eski optimizasyon kontrolleri gelişmiş ayarlarda, telefon radarı sistem menüsünde bulunur.

Geçmiş araştırma temel puanı, nihai puanı ve alış/hedef/stop politikasını gerçekten değiştirir. Python ve canlı Node motoru aynı saf fonksiyonlarla karşılaştırılır. Politika yalnızca kronolojik doğrulama döneminden seçilir; ayrı test politika seçmez. Puan kazanma olasılığı değildir. Günlük kâr ve her gün işlem garantisi yoktur. Gerçek para emirleri verilmez. Bütün tarihsel banka tamamlanmadı; Firebase arka plan bildirimi henüz etkin değil.

APK adı **Oran Radar Cloud 4.0**, Android sürümü 4.0.2 / versionCode 15 / `com.oranradar.lab.cloud`, ARM64. Release JS/Hermes paketi ve geliştirme imzasıyla bağımsız kurulur; Expo Go gerekmez. İlk 3.8.0 uygulamasının yanında kurulur. Actions; mobil kontrolleri, 360/390 px ekran testlerini, Python kaynak eşleşmesini, DEX/Hermes, imza ve 16 KB hizalamayı doğrular.

[Canlı mimari ve API](cloud/live/README.md) · [Eski telefon motoru](docs/LEGACY_3.7_README.md)

## Geliştirme

Mobil: `cd mobile && npm ci`, ardından `npx tsc --noEmit`, `npx expo lint`, `npm run test:cloud`.

Canlı backend: `cd cloud/live && npm ci && npm test && npm run build`. Python araştırma: `cd cloud && python -m pip install -e '.[test]' && python -m pytest -q`.

## Canlı karar katmanı (4.0.3)

Puan 10 saniyede güncellenir. Nihai puan seçilen eşiği geçtiğinde, güncellik ve kasa sınırları uygunsa Optimal sanal alış açar. Araştırma puanı güçlü kanıtta ±20 puana kadar etkiler; ek araştırma vetosu yoktur. Piyasa/Limit seçimi uygulanır. Uygulama açıkken kasa dakikada bir, kapalıyken sunucu 15 dakikada bir kontrol eder. Ana ve yedek piyasa hesaplamaları PostgreSQL'den bağımsızdır. Ayrıntılar [canlı backend belgesinde](cloud/live/README.md).
