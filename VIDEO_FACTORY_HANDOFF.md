# Video Fabrikası — Kalıcı Devam Noktası (V3)

Bu dosya sohbetten bağımsız kalıcı handoff kaydıdır. Yeni bir ChatGPT konuşmasında önce bu dosyayı, `VIDEO_FACTORY_ACCEPTANCE_100.md` dosyasını ve `video-fabrikasi-android` branch HEAD'ini oku; sonra GitHub Actions sonuçlarını kontrol ederek kaldığın yerden devam et.

## Repo / branch
- Repo: `lolo9723/f`
- Branch: `video-fabrikasi-android`
- Güncel kabul matrisi: `VIDEO_FACTORY_ACCEPTANCE_100.md`
- Eski 50'lik matris yalnız tarihsel kayıttır.

## Son doğrulanmış tam normal CI
Commit `11d44f372b1cb4a0d3ae3669a0f6480162ad0736`, workflow run `32977214020`:
- Unit tests: PASS
- V3 generated Python `py_compile`: PASS
- Real Python planner execution: PASS
- Türkçe→İngilizce helper execution: PASS
- Live-E2E tooling dry contract: PASS
- Lint: PASS
- APK build: PASS
- APK structural check: PASS
- Android 15 / API35 instrumentation: PASS
- Android 16 / API36 instrumentation: PASS

CI kırmızıyken final APK verme.

## Aktif üretim motoru
`VideoFactoryScript.java` V3 motorunu kullanır. V3 özellikleri:
- Generic 5-beat planner: HOOK → ESCALATION → TURNING_POINT → CONSEQUENCE → PAYOFF.
- Hard-coded envelope/mailbox zorunluluğu kaldırıldı.
- İlk sahne text-to-video; sonraki sahneler önceki sahnenin son karesiyle conditioning alır.
- Continuity strength başlangıç değeri 0.65.
- İlk saniyede yüksek duygusal uyarılma ve davranıştan gizli niteliği sezdirme kuralları prompt sözleşmesindedir.
- Türkçe fikir algılanır; CPU'da sabit revision'lı `Helsinki-NLP/opus-mt-tr-en` ile İngilizce üretim promptuna çevrilir.
- LTX-Video 2B distilled 0.9.6 sabit commit kullanılır.
- Kaggle T4 için FP16 patch, CUDA/VRAM/FP16 preflight, pinned dependencies vardır.
- Sahne başına maksimum 3 retry; başarılı sahneler arasında pipeline cache vardır.
- Her sahne ffprobe ile doğrulanır.
- Final 1080×1920, H.264/AAC, prosedürel telifsiz SFX/tension bed ile üretilir ve ffprobe ile doğrulanır.
- AI başarısızsa fallback üretilebilir ama `ai_ok=false`; fallback hiçbir zaman AI başarı sayılmaz.
- Ara dosyalar `/tmp/video-factory`; kalıcı Kaggle çıktıları `/kaggle/working`.

## Android uygulama
Ana dosyalar:
- `MainActivity.java`: kullanıcı arayüzü, Kaggle bağlantısı, üretim, proje geçmişi, status, DownloadManager, indirme doğrulama, VideoView playback.
- `KaggleClient.java`: `api.kaggle.com` RPC, SaveKernel, status, ListKernelSessionOutput, exact filename signed URL çözümleme.
- `SecureStore.java`: Android Keystore AES-GCM token koruması.
- `ProjectStore.java`: maksimum 500 proje, slug bazlı durum izolasyonu.

Android özellikleri:
- compileSdk/targetSdk 36, minSdk 26.
- Android Test Orchestrator ile test izolasyonu.
- API35 ve API36 emülatör kapıları.
- Token plaintext saklanmaz.
- Download tamamlanmadan başarı yazılmaz; Android MediaMetadataRetriever ile 1080×1920 ve süre tekrar doğrulanır.
- App kapanırsa pending download kayıtları açılışta reconcile edilir.
- Bir projenin indirme sonucu başka projeye yazılmaz.
- Ana güvenli butonlara gerçek tıklama testleri vardır.

## Canlı E2E sertifika altyapısı
İki yol hazırlandı:
1. APK içinde geçici `VF Canlı Test` ekranı: gerçek Kaggle hesabı ile token doğrulama → kernel → ai_ok/stage/scenes/engine/prompt_language/translation doğrulama → FINAL.mp4 download → Android media verification → canlı sertifika.
2. GitHub Actions gerçek T4 workflow'u: GitHub Secrets içindeki Kaggle bilgilerini kullanır; normal commitlerde GPU harcamaz. Aynı V3 Java generator'ından kernel scripti üretir, Kaggle T4'e yollar, status/output alır ve `e2e_certificate.json` artifact üretir.

Canlı E2E runner ve Java→Python üretim zinciri normal CI içinde GPU kullanmadan dry-contract testinden geçer.

## Kaggle sözleşmesi
Güncel resmi Kaggle CLI ile çapraz kontrol edildi:
- language=`python`
- kernel_type=`script`
- private kernel
- enable_gpu=true
- enable_internet=true
- machine_shape=`NvidiaTeslaT4`
- timeout desteklenir.
Kaggle'ın kendi dokümanı varsayılan imajda P100 CUDA uyumsuzluğu olabileceğini belirtiyor; T4 yolu korunmalı.

## Kabul matrisi
`VIDEO_FACTORY_ACCEPTANCE_100.md` tek gerçek kabul kaynağıdır.
Durumlar: PASS / PARTIAL / GAP / E2E-BEKLİYOR / MANUEL-SON / RELEASE-BEKLİYOR.
Kanıt yoksa PASS yok.

Önemli açıklar (final öncesi öncelik):
1. Gerçek Kaggle T4 canlı sertifika + Mektup #001 gerçek FINAL.mp4.
2. Semantic görsel QC: ekstra karakter, ağır deformasyon, subject kaybı, continuity kırılması gibi hataları otomatik yakalama.
3. Cross-run tek sahne onarma: bütün videoyu yeniden üretmeden yalnız seçili/hatalı sahneyi yeniden üretip finali yeniden birleştirme.
4. Bulk 20–50 fikir kuyruğu.
5. Hook/duygu/merak/özgünlük kalite puanı ve threshold gate.
6. Release signing + fiziksel telefonda temiz kurulum/reboot/network-switch/oynatma son kabul turu.
7. Eski Android (minSdk 26) gerçek cihaz/emülatör kapsamını genişletmek veya minSdk politikasını yeniden değerlendirmek.
8. Remote Kaggle cancel yalnız güvenilir session id alınabiliyorsa uygulanmalı; sahte stop butonu yapılmamalı.

## Kesin kurallar
- Testleri geçsin diye assertion/lint kapısı silme veya gevşetme.
- Fallback'i AI başarı olarak gösterme.
- Tokenı kaynak koda veya plaintext prefs'e koyma.
- Tahmini Kaggle output URL kullanma; exact output listing / signed URL mantığını koru.
- `/kaggle/working` alanını model cache/ara sahnelerle şişirme.
- Gerçek T4 E2E yapılmadan “kusursuz/final” deme.
- Branch HEAD ve Actions sonucu sohbet hafızasından daha otoritatiftir.

## Sonraki iş sırası
1. Branch HEAD + son normal CI + `VIDEO_FACTORY_ACCEPTANCE_100.md` kontrolü.
2. Semantic QC + tek sahne onarma mimarisini testlerle uygula.
3. Bulk queue ve kalite scoring/gate açıklarını kapat.
4. Normal CI'yı API35/API36 üzerinde tamamen yeşil tut.
5. GitHub Secrets veya APK canlı test ekranı üzerinden gerçek Kaggle T4 E2E çalıştır.
6. `status.json` + gerçek `FINAL.mp4` + Android download/playback sertifikasını kaydet.
7. Release signing ve fiziksel telefon son kabul turu.
8. Yalnız tüm kritik maddeler yeşil olduğunda final APK artifact'ını kullanıcıya teslim et.
