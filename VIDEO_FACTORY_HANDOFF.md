# Video Fabrikası — Kalıcı Devam Noktası

Bu dosya sohbet bağlamından bağımsız kalıcı handoff kaydıdır. Yeni bir ChatGPT konuşmasında çalışma kaybolursa önce bu dosyayı ve `video-fabrikasi-android` branch'ini oku, sonra kaldığın yerden devam et.

## Proje hedefi

Telefondan yönetilen, yüzlerce dikey kısa video üretebilecek kalıcı Android sistemi. Telefon AI hesaplamasını yapmaz; Android uygulaması Kaggle GPU işini oluşturur, durumunu takip eder, doğrulanmış `FINAL.mp4` çıktısını indirir ve telefonda oynatır.

İlk örnek içerik konsepti: iki antropomorfik mektup aynı kişiye gider. İyi haber taşıyan mektup özgüvenli/mutlu, kötü haber taşıyan mektup panik ve üzüntü içindedir. İlk saniyede güçlü duygusal giriş şarttır; kötü haber mektubu çığlık atarak koşar, mutlu mektubu posta kutusuna iter. Kişi önce kötü haberi okuyunca çöker ve iyi haberi açmadan yere düşürür. Genel seri prensibi: nesnenin görünmeyen niteliği reveal öncesinde davranışından anlaşılmaya başlamalı; ilk saniyede yüksek duygusal uyarılma bulunmalı.

## Repository / branch

- Repo: `lolo9723/f`
- Çalışma branch'i: `video-fabrikasi-android`
- Bu handoff oluşturulmadan önceki son ürün kodu commit'i: `045fd82861ae3d8a6e46153241616f0c9c93822a`
- Önceki büyük motor commit'i: `9ba298ca6d9298f0eb95a41aa532afdc00c466a9` — LTX pipeline cache, sahne doğrulama ve 3 otomatik retry.

Her zaman branch HEAD'i gerçek kaynak kabul et; yukarıdaki SHA'lar yalnız geri dönüş/karşılaştırma noktalarıdır.

## Ana dosyalar

- `app/src/main/java/com/videofabrikasi/app/MainActivity.java`
  - Android kullanıcı arayüzü
  - Kaggle bağlantı testi
  - video üretimi başlatma
  - proje durum takibi
  - DownloadManager indirme
  - indirme sonrası MP4 doğrulama
  - uygulama içi VideoView oynatıcı
  - app yeniden açıldığında bekleyen indirmeleri uzlaştırma

- `app/src/main/java/com/videofabrikasi/app/KaggleClient.java`
  - Kaggle API/RPC istemcisi
  - `https://api.kaggle.com/v1/...` resmi RPC tabanı
  - kernel push/status
  - `ListKernelSessionOutput` ile çıktı listesi
  - `FINAL.mp4` / `status.json` tam isim eşleşmesi
  - imzalı output URL çözümleme

- `app/src/main/java/com/videofabrikasi/app/VideoFactoryScript.java`
  - Kaggle'da çalışacak Python üretim scriptini Java içinde üretir
  - LTX-Video 2B distilled 0.9.6 sabit commit
  - Tesla T4 için FP16 patch
  - GPU/VRAM/FP16 CUDA preflight
  - bağımlılık pinleri
  - 5 sahne üretimi
  - pipeline cache
  - sahne başına en fazla 3 retry
  - her sahnede ffprobe doğrulaması
  - final 1080x1920 render
  - prosedürel/telifsiz ses katmanı: çığlık, whoosh, impact, metal mailbox, paper fall
  - final video/audio ffprobe doğrulaması
  - AI başarısız olursa fallback üretir fakat `ai_ok=false`; fallback hiçbir zaman AI başarı olarak gösterilmez
  - geçici repo/sahneler `/tmp/video-factory`; kalıcı Kaggle çıktıları `/kaggle/working`

- `app/src/main/java/com/videofabrikasi/app/ProjectStore.java`
  - proje geçmişi
  - maksimum 500 kayıt
  - projeler arası durum izolasyonu
  - slug bazlı status update

- `app/src/main/java/com/videofabrikasi/app/SecureStore.java`
  - Kaggle token Android Keystore koruması

## Uygulanmış kritik davranışlar

1. Telefon GPU işi yapmaz; Kaggle üretir.
2. Kullanıcı adı/token güvenli kaydedilir.
3. Birden çok proje oluşturulabilir; proje geçmişi 500 kayıtla sınırlandırılır.
4. Kernel slug çakışmasını azaltmak için milisaniye timestamp kullanılır.
5. Kernel title slug ile aynı olacak şekilde gönderilir.
6. Kaggle RPC hostu güncel resmi SDK ile hizalanmıştır: `api.kaggle.com`.
7. Çıktı indirme `ListKernelSessionOutput` sonucu üzerinden gerçek imzalı URL ile yapılır; exact filename gerekir.
8. `status.json` içinde yalnız `stage=COMPLETE`, `ai_ok=true`, `final=FINAL.mp4` koşulu gerçek AI başarı sayılır.
9. Fallback çıktı indirilebilir AI başarı gibi gösterilmez.
10. Final sunucuda ffprobe ile doğrulanır: video stream + audio stream + 1080x1920 + anlamlı süre.
11. Android indirilen dosyayı tekrar doğrular: DownloadManager status, boyut, URI, MediaMetadataRetriever width/height/duration.
12. İndirilen dosya doğrulanmadan proje `İNDİRİLDİ` sayılmaz.
13. DownloadManager receiver yalnız daha önce uygulamanın kaydettiği pending download ID'lerini işler; sonra DownloadManager kayıt satırını otorite olarak tekrar sorgular.
14. Android 13+ receiver `Context.RECEIVER_EXPORTED` ile kayıt edilir çünkü DownloadManager yayını uygulama dışından gelir; spoof edilmiş yayın pending ID + DownloadManager authoritative query katmanını geçemez.
15. Uygulama kapalıyken indirme biterse açılışta pending kayıtlar tekrar sorgulanır.
16. Doğrulanmış MP4 uygulama içinde oynat/duraklatılabilir.
17. Bir projenin indirme sonucu başka açık projeye yanlışlıkla yazılmaz.
18. LTX pipeline başarılı sahneler arasında cache edilir; her sahnede modeli baştan yüklememek hedeflenir.
19. Scene generation hata/bozuk çıktı halinde 3 kez otomatik denenir; hata sonrası pipeline cache temizlenebilir.
20. Kaggle `/working` alanı ara dosyalarla şişirilmez; esas kalıcı çıktılar `FINAL.mp4`, `status.json` ve hata durumunda tanı dosyalarıdır.

## Test altyapısı

GitHub Actions workflow: `.github/workflows/video-factory-android.yml`

Teslim kapıları:

1. `gradle testDebugUnitTest`
2. Java tarafından üretilen Python script için gerçek `python3 -m py_compile`
3. `gradle lintDebug`
4. debug APK build
5. APK structural check
6. Android 35 emulator smoke/instrumentation tests
7. Proje geçmişi / 120 proje izolasyonu / 500 cap
8. Android gerçek `org.json` parser testleri
9. fallback'in AI başarı sayılmaması
10. Download completion receiver crash/izolasyon testleri
11. kritik UI kontrolleri ve oynatıcı görünürlüğü

## Son bilinen CI durumu

`9ba298ca6d9298f0eb95a41aa532afdc00c466a9` için run `32947966838`:

- Unit tests: PASS
- Lint: FAIL
- Build / emulator: lint fail nedeniyle çalışmadı
- Lint'in tek error'u: `MainActivity.registerDownloadReceiver()` için receiver export flag eksikliği.

Bu lint problemi `045fd82861ae3d8a6e46153241616f0c9c93822a` commit'inde düzeltildi:

- Android 13+ -> `registerReceiver(..., Context.RECEIVER_EXPORTED)`
- Eski Android -> legacy `registerReceiver(...)`
- Pending download ID + DownloadManager authoritative query güvenlik katmanı korundu.

Yeni CI sonucunu ilk iş olarak kontrol et. PASS değilse testleri/baseline'ı gevşetmeden gerçek sebebi düzelt.

## Kesinlikle yapılmaması gerekenler

- CI kırmızıyken APK'yı final diye verme.
- Fallback'i AI üretimi diye gösterme.
- Testleri geçsin diye önemli assertion/test kapılarını kaldırma veya lint baseline ile hatayı saklama.
- Token'ı düz SharedPreferences'a plaintext yazma.
- Kaggle output indirmeyi tahmini URL'ye bağlama; exact output listing / signed URL mantığını koru.
- `/kaggle/working` içine LTX repo/model cache/ara sahneleri doldurma.
- Sohbet geçmişine güvenerek mimariyi yeniden tahmin etme; branch + bu handoff kaynak olsun.

## Sonraki işler — öncelik sırası

1. `045fd...` sonrası GitHub Actions run'ını kontrol et.
2. Unit + lint + APK + structural + Android 35 emulator tamamen yeşil olana kadar düzelt.
3. `VideoFactoryScript` pipeline cache patch'inin gerçek üretilen Python'da `py_compile` ve sözleşme testlerini geçtiğini yeniden teyit et.
4. Kaggle'ın güncel resmi API/SDK alanlarıyla push/status/output JSON sözleşmesini tekrar çapraz kontrol et.
5. Gerçek Kaggle token olmadan yapılabilecek en güçlü mock/parser/HTTP sözleşme testlerini tamamla.
6. Gerçek token sağlandığında tek gerçek end-to-end smoke: Android -> Kaggle T4 -> LTX sahneler -> audio -> `FINAL.mp4` -> `status.json` -> Android download -> local media verification -> playback.
7. End-to-end gerçek üretim doğrulanmadan “kusursuz/final” deme.
8. Ardından 50 kullanıcı + teknik kabul sorusunun tamamını test matrisi olarak repoda kilitle ve sonuçları PASS/FAIL kaydet.
9. Son olarak installable APK artifact'ını kullanıcıya teslim et.

## Yeni sohbet için tek cümlelik devam talimatı

`GitHub'daki lolo9723/f reposunun video-fabrikasi-android branch'indeki VIDEO_FACTORY_HANDOFF.md dosyasını oku, branch HEAD ve son GitHub Actions sonucunu kontrol et ve Video Fabrikası APK çalışmasına testleri gevşetmeden kaldığı yerden devam et.`

## Ürün kalite prensibi

Amaç yalnız APK'nın açılması değildir. Kullanıcının istediği son durum: yüzlerce videoya ölçeklenebilen, telefonun yükünü taşımadığı, gerçek AI video üreten, yüksek duygusal girişli Shorts konseptlerini kalıcı pipeline ile üretebilen, hata/fallback durumunu dürüst ayıran ve indirilen medyayı gerçekten doğrulayan bir sistemdir.
