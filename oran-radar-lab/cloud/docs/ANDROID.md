# Android Cloud 4.0 bağlantısı

Kaynak proje `../mobile/` içinde. Expo 57, React Native 0.86.3 ve Chaquopy/Python 3.13 telefon motoru kullanılır. Cloud 4.0 yeni sürüm yapılandırması çakışan önceki ayarları günceller.

- Uygulama açılışı `/cloud` ekranıdır. Sekiz sayfa gerçek kapsam raporunu gösterir.
- Varsayılan veri kanalı aynı GitHub dalındaki `cloudflare-coverage.json`dır. Yayımlanan geçmiş kapsamını okur; anlık TR fiyatı veya kesintisiz analiz hizmeti olarak göstermez.
- Gerçek Cloud API bağlandığında `/v1/status`, `/v1/devices` sözleşmesi kullanılır. HTTPS zorunludur. Kullanıcı anahtarı Expo SecureStore üzerinden Android güvenli deposunda tutulur.
- Son başarılı rapor ve alma zamanı AsyncStorage'da korunur. 401/403 kimlik doğrulama hatası cache başarısı gibi gösterilmez. Ağ kesilmesi açıkça yazılır.
- Telefon radarı ve önceki sanal işlem ekranlarına Cloud ana ekranındaki düğmeyle ulaşılır.
- Native FCM cihaz kaydı vardır; Firebase hesap yapılandırması ve kapalı uygulamada arka plan teslimi için ayrıca native alıcı/cihaz doğrulaması gerekir.

Yeni paket `com.oranradar.lab.cloud`, sürüm 4.0.0, versionCode 12; ARM64 test APK'sidir. Geliştirme sertifikası kullanır ve önceki Lab uygulamasının yanına kurulur. Önceki Lab özel imzalama anahtarı depoda bulunmadığından onun üzerine güncelleme imzası üretilmez.

Kök GitHub Actions işi bağımlılıkları/tipleri/lint'i, mobil regresyon senaryolarını, Android PriceBook testlerini, Hermes/DEX/ELF bütünlüğünü, paketlenen Python kaynak eşleşmesini, imzayı ve 16 KB hizalamayı denetler. Başarılı APK `../downloads/Oran-Radar-Cloud-4.0.0.apk` olarak yayımlanır. Gerçek telefon açılış ve bildirim teslim testi bu ortamda yapılamaz.

Workers/D1 ve sürekli Python çalıştırıcısının hesap üzerinde kurulumu tamamlanmadan APK kapalıyken yeni analiz üretimi etkin sayılmaz. Uygulama yeni modeli veya sahte başarı oranını kendiliğinden uydurmaz.
