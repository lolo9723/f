# Android APK

Lab paket kimliği `com.oranradar.lab`, sürümü `3.7.0`, versionCode `10`'dir. Orijinal uygulamayla birlikte kurulabilir. Önceki Lab 2.x/3.0 ile aynı imza anahtarı kullanılır; kurulu Lab uygulamasının üzerine güncellenebilir. JavaScript/Hermes kodu release APK içine gömülür; Expo Go veya Metro sunucusu gerekmez. Minimum Android sürümü 7.0 (API 24), hedef API 36'dır.

Radar sekmesindeki **Telefon motoru · Drive yedeği** kartından araştırma başlatılır ve Drive hesabı/klasörü seçilir. **Kontrol et ve eksikleri uyandır** düğmesi her zaman görünür; telefon işlerini, WorkManager zamanlamasını ve yedek teslimini denetler. Eksik işler kuyruğa alınır; sonuç sonraki güncellemede doğrulanır. Bulut backend bağlantısı isteğe bağlıdır.

## Yerel derleme

JDK 21, Node 24, Python 3.13 ve aşağıdaki Android SDK bileşenleri gerekir:

```bash
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' \
  'ndk;27.1.12297006' 'cmake;3.22.1'
sdkmanager --licenses
```

Yeni Lab anahtarınızı PKCS12 biçiminde oluşturun; parolayı yalnızca sahibi tarafından okunabilen ayrı bir dosyada tutun. Sonraki güncellemeler aynı anahtarla imzalanmalıdır. Anahtarı veya parolayı Git'e eklemeyin.

```bash
export ANDROID_HOME=/absolute/path/android-sdk
export JAVA_HOME=/absolute/path/jdk21
export RADAR_KEYSTORE=/private/path/lab.p12
export RADAR_PASSWORD_FILE=/private/path/password.txt
export RADAR_KEY_ALIAS=oranradarlab
export RADAR_BUILD_PYTHON=/absolute/path/python3.13
export RADAR_OUTPUT=/absolute/path/Oran-Radar-Lab.apk
bash mobile/scripts/build_lab_apk.sh
```

Script bağımlılıkları, tipleri, lint ve mevcut paper testlerini kontrol eder; native projeyi üretir, 64 bit ARM telefonlar için (`arm64-v8a`) release paket oluşturur. Expo'nun geçici debug imzasının yerine verilen Lab anahtarıyla imzalar. DEX/Hermes bütünlüğünü, APK imzasını ve ZIP hizalamasını doğrular. Proxy gereken ortamlarda Gradle proxy ayarları ve kurum CA güven deposu ayrıca yapılandırılmalıdır; TLS doğrulaması kapatılmaz.

## Telefon motoru ve backend bağlantısı

Lab 3.7 telefonun araştırma motorunda tüm aktif TRY coinlerini otomatik takip eder; önceki Rally Score / 100 hesabı araştırma ve stop-loss ile birlikte gösterilir. Her coin kartında alış/hedef/stop ön planı ve ayrı sanal alış kaydı vardır. Puan taraması uzun araştırmadan bağımsız çalışır; ekrandaki coinlere öncelik ve manuel yenileme içerir. Bulutsuz kullanım ve Drive seçimi için [PHONE_BACKEND.md](PHONE_BACKEND.md). Bu sürüm ARM64 gerektirir; önceki APK'lar ayrıca korunur.

APK'nın kurulması bulut altyapısını dağıtmaz. Telefon motoru bulut gerektirmez; Android'in süre/ağ/pil izinleriyle çalışır. İsteğe bağlı bulut kullanımında ⚙ menüsünden dağıtılan backend HTTPS adresi ve erişim anahtarı girilir. Yerel testte telefonun erişebildiği bilgisayar adresi kullanılır. Bulut kurulumu `DEPLOYMENT.md` içinde açıklanır. Fiziksel cihazda açılış, piyasa erişimi ve Drive senkronizasyonu ayrıca doğrulanmalıdır.

Lab 3.4, kapalı mumda sabitlenen alış seviyeleri ve ayrı limit alış adayı araştırması içerir. Alış araştırması, puan-only işte eğitilmez; önceki rapor kullanılır. Kaynak ve paketlenen Python modülleri `verify_phone_apk.py` ile karşılaştırılır.

Lab 3.5, olgun yön/alış/stop araştırmalarının sınırlı puan katkısını içerir; temel Rally formülü korunur. Katkı ve olgunluk durumu kartta gösterilir. Kanıt/güncellik bozulunca son piyasa taramasını değiştirmeden katkı kaldırılır. Paketlenmiş Python kaynakları ve Hermes ekran metinleri ayrıca doğrulanır.

Lab 3.6, sanal alış formunda quote/+%3 geri dönüşünü kaldırır; alış/hedef/stop ve güncellik aynı formda gösterilir. Forma girişte son plan kontrol edilir. Manuel değişiklikler, coin seçimi ve gecikmiş yanıtlar korunur. Küçük coinlerde fiyat karşılaştırması 10 anlamlı basamakla yapılabilir. Olgun araştırma puanı korunur.

Lab 3.7, native OkHttp WebSocket fiyat akışı, bağımsız REST yedeği, hızlı native fiyat köprüsü ve 2 saniyelik ekran okuması içerir. JNI/Python araştırma kilidi fiyat akışını tutmaz. `:radar-phone:testReleaseUnitTest` beş native PriceBook senaryosunu çalıştırır. WebSocket/REST yolunun fiziksel telefonda kontrolü derleme/testlerden ayrıdır.
