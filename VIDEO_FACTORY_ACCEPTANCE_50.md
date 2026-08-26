# Video Fabrikası — 50 Maddelik Kabul Testi

Kural: `PASS` yalnız kod/CI/emülatör ile doğrulanmış maddelere verilir. Gerçek Kaggle hesabı/token/T4 çalıştırması gerektiren maddeler, gerçek uçtan uca test yapılmadan PASS sayılamaz.

Durum etiketleri:
- `PASS`: otomatik veya yapısal kanıt mevcut.
- `CI-BEKLİYOR`: ilgili kod hazır, son Android emülatör turu sonucu bekleniyor.
- `E2E-BEKLİYOR`: gerçek Kaggle token/GPU çalıştırması gerektiriyor.
- `MANUEL-SON`: son gerçek telefonda kullanıcı kabul turunda tekrar kontrol edilecek.

| # | Kullanıcının soracağı temel/teknik soru | Kabul cevabı | Kanıt / test | Durum |
|---|---|---|---|---|
| 1 | APK gerçekten derleniyor mu? | Evet, APK build başarısızsa teslim yok. | GitHub Actions `assembleDebug`. | PASS |
| 2 | APK dosyası boş/0 byte olabilir mi? | Hayır. | Workflow `test -s app-debug.apk`. | PASS |
| 3 | Uygulama Android 35'te açılıyor mu? | Crash olmadan açılmalı. | Android 35 emulator instrumentation. | CI-BEKLİYOR |
| 4 | Ana ekrandaki kullanıcı adı alanı görünüyor mu? | Görünmeli ve düzenlenebilir olmalı. | Espresso `username`. | CI-BEKLİYOR |
| 5 | Token alanı görünüyor ve yazılabiliyor mu? | Evet; parola tipi alan. | Espresso + UI kodu. | CI-BEKLİYOR |
| 6 | Video fikri alanı çalışıyor mu? | Uzun metin yazılabilmeli/değiştirilebilmeli. | Espresso replaceText. | CI-BEKLİYOR |
| 7 | “Videoyu Üret” tuşu basılıyor mu? | Evet; eksik bilgilerde crash yerine uyarı vermeli. | `generateRejectsMissingCredentialsWithoutCrash`. | CI-BEKLİYOR |
| 8 | “Yenile” tuşu var mı? | Evet ve uzak durumu yeniden sorgulamalı. | UI/ID + MainActivity akışı. | CI-BEKLİYOR |
| 9 | “Tümünü yeniden üret” tuşu var mı? | Evet, yeni Kaggle işi başlatmalı. | UI/ID + startGeneration(true). | CI-BEKLİYOR |
| 10 | “MP4 indir” tuşu var mı? | Evet; yalnız doğrulanmış AI üretiminde çalışmalı. | UI/ID + status gate. | CI-BEKLİYOR |
| 11 | İndirilen videoyu uygulama içinde oynatabiliyor muyum? | Doğrulanmış MP4 için oynat/duraklat olmalı. | VideoView + play/pause UI testi. | CI-BEKLİYOR |
| 12 | Telefon AI hesaplaması yüzünden çöküyor mu? | AI modeli telefonda yüklenmemeli; telefon yalnız kontrol istemcisi. | Mimari/kod incelemesi; model Python'u Kaggle'a gider. | PASS |
| 13 | Kaggle token düz metin SharedPreferences'a mı yazılıyor? | Hayır; Android Keystore ile korunmalı. | `SecureStore` instrumentation testleri. | CI-BEKLİYOR |
| 14 | Uygulama token yokken üretime başlamaya çalışır mı? | Hayır, güvenli şekilde reddetmeli. | Espresso missing credentials testi. | CI-BEKLİYOR |
| 15 | Kaggle bağlantısını ayrı test edebilir miyim? | Evet, “Bağlantıyı Test Et” akışı olmalı. | `validateToken()` + UI kontrolü. | E2E-BEKLİYOR |
| 16 | Kaggle API çağrısı güncel hosta mı gidiyor? | `api.kaggle.com/v1` resmi RPC tabanı kullanılmalı. | KaggleClient unit contract + resmî CLI çapraz kontrolü. | PASS |
| 17 | Kernel gerçekten T4 istemeyi deniyor mu? | `machineShape=NvidiaTeslaT4` gönderilmeli. | KaggleClient push body sözleşmesi. | PASS |
| 18 | İnternet kernel içinde açık mı? | Model/repo indirmek için açık olmalı. | `enableInternet=true`. | PASS |
| 19 | GPU gerçekten var mı diye üretimden önce kontrol ediyor mu? | Evet. | `torch.cuda.is_available()` preflight. | PASS |
| 20 | GPU belleği yetersizse bunu yakalıyor mu? | 12 GiB altını başarısız saymalı. | VideoFactoryScript unit contract. | PASS |
| 21 | T4'te FP16 gerçekten çalışıyor mu diye kontrol var mı? | Küçük gerçek CUDA FP16 matmul yapılmalı. | GPU preflight kodu/test sözleşmesi. | PASS |
| 22 | LTX sürümü rastgele değişip sistemi bozabilir mi? | Ana motor commit'i pinlenmiş olmalı. | Sabit LTX commit assertion. | PASS |
| 23 | Transformers/diffusers sürüklenmesi sistemi bozabilir mi? | Kritik sürümler pinli olmalı. | Unit assertion. | PASS |
| 24 | Java'nın oluşturduğu Python gerçekten syntax olarak geçerli mi? | `python3 -m py_compile` geçmeli. | Gerçek py_compile unit testi. | PASS |
| 25 | Kullanıcının Türkçe fikri Python stringini bozabilir mi? | Base64 gömülmeli, quote/triple quote güvenli olmalı. | Base64 unit testi. | PASS |
| 26 | Her video 5 sahneden oluşuyor mu? | Örnek motor 5 sahne üretmeli. | PROMPTS/sahne loop sözleşmesi. | PASS |
| 27 | Bir sahne bozuk çıkarsa bütün iş hemen çöpe mi gidiyor? | Hayır, sahne başına en fazla 3 retry olmalı. | Script retry contract. | PASS |
| 28 | Sahne dosyası var diye körlemesine kabul ediliyor mu? | Hayır; ffprobe ile video boyutu/süre doğrulanmalı. | `probe_scene`. | PASS |
| 29 | Model her sahnede yeniden yükleniyor mu? | Başarılı sahnelerde pipeline cache kullanılmalı. | LTX patch/cache contract. | PASS |
| 30 | Cache bozulursa sonsuz hata döngüsü olur mu? | Hata sonrası cache reset ve sınırlı retry olmalı. | Script retry/cache reset. | PASS |
| 31 | Kaggle çalışma alanı ara dosyalarla şişiyor mu? | Repo/sahneler `/tmp`; kalıcı çıktı `/kaggle/working`. | Unit persistence test. | PASS |
| 32 | Gerçek AI başarısız olsa da uygulama “AI tamamlandı” diyebilir mi? | Hayır. | `ai_ok` + stage parser; fallback Android test. | CI-BEKLİYOR |
| 33 | Fallback video varsa kullanıcı kandırılır mı? | Hayır; `AI BAŞARISIZ — FALLBACK` görünmeli. | Android parser instrumentation. | CI-BEKLİYOR |
| 34 | Final video dikey 1080x1920 mi? | Final doğrulaması bunu zorunlu kılmalı. | Server ffprobe + Android metadata check. | PASS |
| 35 | Final videoda ses var mı? | Audio stream zorunlu olmalı. | Server ffprobe; AAC mix. | PASS |
| 36 | İlk saniyedeki yüksek duygusal ses düşünülmüş mü? | Prosedürel scream/impact/whoosh katmanı olmalı. | Script ses sentezi sözleşmesi. | PASS |
| 37 | Dışarıdan telifli ses dosyası çekiliyor mu? | Temel efektler yerel/prosedürel üretilmeli. | Script incelemesi. | PASS |
| 38 | Final dosya çok küçük/bozuksa başarı sayılır mı? | Hayır; boyut + ffprobe kontrolleri geçmeli. | Server/Android doğrulama. | PASS |
| 39 | Kaggle çıktısını tahmini URL ile mi indiriyor? | Hayır; output listeden exact filename + signed URL. | `ListKernelSessionOutput` parser. | CI-BEKLİYOR |
| 40 | `FINAL.mp4.bak` yanlışlıkla `FINAL.mp4` sanılır mı? | Hayır, exact string match. | KaggleClientAndroidTest. | CI-BEKLİYOR |
| 41 | camelCase/snake_case Kaggle alan değişimleri tolere ediliyor mu? | Kritik output alanlarında ikisi de desteklenmeli. | Android parser testleri. | CI-BEKLİYOR |
| 42 | İndirme başladığı anda “başarılı” mı sayılıyor? | Hayır; DownloadManager tamamlanması ve medya doğrulaması beklenmeli. | MainActivity indirme akışı. | CI-BEKLİYOR |
| 43 | Başka bir uygulama sahte DOWNLOAD_COMPLETE gönderirse başarı yazabilir mi? | Pending ID + gerçek DownloadManager satırı olmadan yazamamalı. | Receiver kodu + unrelated broadcast crash test. | CI-BEKLİYOR |
| 44 | Uygulama indirme sırasında kapanırsa sonuç kaybolur mu? | Açılışta pending download kayıtları reconcile edilmeli. | `reconcilePendingDownloads()`. | CI-BEKLİYOR |
| 45 | 100+ video projesi birbirine karışır mı? | 120 proje izolasyon testi geçmeli. | ProjectStore instrumentation. | CI-BEKLİYOR |
| 46 | Geçmiş sonsuza kadar büyüyüp telefonu doldurur mu? | 500 proje cap uygulanmalı. | ProjectStore 501 kayıt testi. | CI-BEKLİYOR |
| 47 | B projesinin indirmesi bittiğinde açık A projesinin durumu bozulur mu? | Hayır, slug bazlı update gerekir. | ProjectStore status isolation testi. | CI-BEKLİYOR |
| 48 | Gerçek Kaggle T4 üzerinde LTX beş sahneyi gerçekten üretiyor mu? | Tek gerçek uçtan uca koşuda kanıtlanmalı. | Android→Kaggle→T4→LTX smoke. | E2E-BEKLİYOR |
| 49 | Gerçek `FINAL.mp4` Kaggle'dan telefona inip uygulamada açılıyor mu? | Gerçek hesapla indirme+metadata+playback tamamlanmalı. | Uçtan uca gerçek cihaz/Kaggle testi. | E2E-BEKLİYOR |
| 50 | Bu sürüm “final teslim” sayılabilir mi? | Yalnız 1–49 kritik kapılar yeşil ve gerçek E2E tamamlandıktan sonra. | CI + gerçek Kaggle smoke + son telefon kabul turu. | E2E-BEKLİYOR |

## Kabul politikası

- Her committe CI yeniden çalışır.
- Unit/lint/build/APK structural/emulator testlerinden biri kırmızıysa final APK verilmez.
- Gerçek Kaggle hesabı gerektiren 15, 48, 49 ve dolayısıyla 50. madde gerçek tokenla çalıştırılmadan “tam doğrulanmış” ilan edilmez.
- Her önemli ilerlemede bu tablo güncellenir; sohbet geçmişine güvenilmez.
