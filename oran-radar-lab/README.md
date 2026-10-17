# Oran Radar Cloud 4.0

[Android APK](downloads/Oran-Radar-Cloud-4.0.1.apk) · [Canlı panel](https://br-falling-term-b9315oqi-radar.compute.c-7.eu-central-1.aws.neon.tech) · [Derleme](https://github.com/lolo9723/f/actions/workflows/oran-cloud-4.yml)

Uygulama canlı bulut servisine hazır bağlıdır. Telefon kapalıyken 15 dakikalık sunucu zamanlayıcısı TR piyasasını, geçmiş örüntüleri ve sanal işlemleri takip eder. Ücretli plan veya hizmet açılmadı; Neon Free kotaları geçerlidir.

- **Manuel:** Coin, TL tutarı, piyasa/limit alış, hedef ve stop kullanıcı tarafından seçilir. Otomatik yeni alış açılmaz. Açık işlemler bulutta izlenir.
- **Optimal:** Kullanıcı çalışma bütçesini ve risk sınırlarını belirler. Bulut; nihai puanı, benzer tarihsel olayların maliyet sonrası sonucunu, ayrı testi, belirsizliği ve güncel spread'i birlikte değerlendirerek coin, araştırılmış limit fiyatı ve tutarı seçer. Uygun kanıt veya fırsat yoksa nakitte bekler.
- **Sanal kasa:** Başlangıç bakiyesi bir kez belirlenir. Açık ve bekleyen emirler bütçeden birlikte sayılır. Coin yoğunlaşması, işlem/portföy riski, bekleme ve eşzamanlı işlem sınırları korunur. İstanbul gününe göre gerçekleşen net K/Z gösterilir.
- **Sade arayüz:** Radar, Kasa ve Ayarlar. Eski optimizasyon kontrolleri gelişmiş ayarlarda, telefon radarı sistem menüsünde bulunur.

Geçmiş araştırma temel puanı, nihai puanı ve alış/hedef/stop politikasını gerçekten değiştirir. Python ve canlı Node motoru aynı saf fonksiyonlarla karşılaştırılır. Politika yalnızca kronolojik doğrulama döneminden seçilir; ayrı test politika seçmez. Puan kazanma olasılığı değildir. Günlük kâr ve her gün işlem garantisi yoktur. Gerçek para emirleri verilmez. Bütün tarihsel banka tamamlanmadı; Firebase arka plan bildirimi henüz etkin değil.

APK adı **Oran Radar Cloud 4.0**, Android sürümü 4.0.1 / versionCode 14 / `com.oranradar.lab.cloud`, ARM64. Release JS/Hermes paketi ve geliştirme imzasıyla bağımsız kurulur; Expo Go gerekmez. İlk 3.8.0 uygulamasının yanında kurulur. Actions; mobil kontrolleri, 360/390 px ekran testlerini, Python kaynak eşleşmesini, DEX/Hermes, imza ve 16 KB hizalamayı doğrular.

[Canlı mimari ve API](cloud/live/README.md) · [Eski telefon motoru](docs/LEGACY_3.7_README.md)

## Geliştirme

Mobil: `cd mobile && npm ci`, ardından `npx tsc --noEmit`, `npx expo lint`, `npm run test:cloud`.

Canlı backend: `cd cloud/live && npm ci && npm test && npm run build`. Python araştırma: `cd cloud && python -m pip install -e '.[test]' && python -m pytest -q`.
