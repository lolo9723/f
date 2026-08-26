# Video Fabrikası — 100 Maddelik Nihai Kabul Matrisi

Bu dosya sohbetten bağımsız nihai kabul sözleşmesidir. Kural nettir: **kanıt yoksa PASS yok**. Bir özellik arayüzde görünse bile gerçek akış/test kanıtı yoksa tamamlanmış sayılmaz.

Durumlar:
- `PASS`: kod + unit/lint/build/emülatör/yapısal kanıt mevcut.
- `PARTIAL`: temel davranış var, fakat başlangıçta konuşulan tam ürün standardına henüz ulaşmıyor.
- `GAP`: ürün özelliği henüz uygulanmadı.
- `E2E-BEKLİYOR`: gerçek Kaggle hesabı/T4/çıktı zinciri olmadan PASS verilemez.
- `MANUEL-SON`: fiziksel telefonda son kullanıcı kabul turunda doğrulanacak.
- `RELEASE-BEKLİYOR`: kalıcı release signing/dağıtım kapısı bekliyor.

Son tam normal CI kilometre taşı: branch `video-fabrikasi-android`, commit `11d44f372b1cb4a0d3ae3669a0f6480162ad0736`, workflow run `32977214020`: build/unit/dry-E2E/lint/APK + Android API35 + Android API36 = **PASS**.

## 1–50 — Ürün / kullanım kabul soruları

| # | Soru | Kabul standardı | Durum |
|---|---|---|---|
| 1 | Uygulama Android odaklı mı? | Android native APK, telefon kontrol paneli. | PASS |
| 2 | Android 16 hedefleniyor mu? | compileSdk/targetSdk 36; API36 emülatör PASS. | PASS |
| 3 | Eski Android desteği gerçekten doğrulandı mı? | minSdk 26 ilan ediliyorsa API26–28 indirme/izin akışı da testlenmeli veya minSdk yükseltilmeli. | PARTIAL |
| 4 | APK doğrudan telefona kurulabilir mi? | Gerçek fiziksel telefonda temiz kurulum + açılış. | MANUEL-SON |
| 5 | Basit ve gelişmiş kullanım modu var mı? | Tek tuş basit mod + gerekirse gelişmiş kontroller. | GAP |
| 6 | Ağır AI telefonda mı çalışıyor? | Hayır; telefon yalnız kontrol eder, Kaggle GPU üretir. | PASS |
| 7 | Ana ücretsiz GPU kaynağı Kaggle mı? | T4 istenir, internet açık, özel kernel. | PASS |
| 8 | Kaggle kotası biterse otomatik/manuel ikinci sağlayıcı var mı? | En az bir provider fallback veya açık bekletme politikası. | GAP |
| 9 | Varsayılan üretim yolu ücret gerektirmiyor mu? | Kaggle ücretsiz kota yolu; ücretli API zorunlu değil. | PASS |
| 10 | Kalan GPU kotası uygulamada gösteriliyor mu? | Kullanıcı kota/uygunluk durumunu görebilmeli. | GAP |
| 11 | Shorts süresi kısa format için uygun mu? | 5 kısa sahne, final >=8 sn ve yaklaşık kısa-form hedef. | PASS |
| 12 | Video dikey 9:16 mı? | Final 1080×1920 zorunlu. | PASS |
| 13 | Taslak → final kalite modu var mı? | Düşük maliyetli draft ve onay sonrası final seçenekleri. | GAP |
| 14 | Video otomatik sahnelere ayrılıyor mu? | 5 dramatik rol: Hook/Escalation/Turning Point/Consequence/Payoff. | PASS |
| 15 | Karakter/nesne devamlılığı korunuyor mu? | Önceki sahnenin son karesiyle conditioning, strength 0.65. | PASS |
| 16 | Mektup dışında yeni nesne/hikâyeler üretilebilir mi? | Hard-coded envelope/mailbox yok; generic story planner. | PASS |
| 17 | Kalıcı karakter kütüphanesi var mı? | Kullanıcı karakter oluşturup adla saklayabilmeli. | GAP |
| 18 | Kullanıcı referans görsel yükleyebiliyor mu? | Görsel karakter/object reference girişi. | GAP |
| 19 | Kullanıcı yalnız hikâyeyi yazınca storyboard otomatik kuruluyor mu? | 5 rol otomatik üretiliyor. | PASS |
| 20 | İlk saniye yüksek duygusal uyarılma zorunlu mu? | Hook prompt + prosedürel yüksek-arousal ses. | PASS |
| 21 | Gizli nitelik reveal öncesi davranıştan sezdiriliyor mu? | STYLE_RULES içinde zorunlu. | PASS |
| 22 | Zayıf hook üretimden önce reddediliyor mu? | Otomatik hook kalite değerlendirmesi. | GAP |
| 23 | Hook/merak/duygu/özgünlük/final puanı var mı? | Otomatik kalite skoru. | GAP |
| 24 | Kalite eşiğinin altı otomatik bloklanıyor mu? | Threshold gate. | GAP |
| 25 | Yeni fikir geçmiş videolara aşırı benziyorsa yakalanıyor mu? | Similarity/originality kontrolü. | GAP |
| 26 | 20–50 fikri tek seferde kuyruğa ekleyebilir miyim? | Bulk paste/import queue. | PARTIAL |
| 27 | Proje durumları ayrıştırılıyor mu? | Kuyruk/üretim/tamamlandı/hatalı/fallback/indirme durumları. | PASS |
| 28 | Uzaktaki Kaggle işini gerçek anlamda durdurabiliyor muyum? | Remote cancel; yalnız telefon polling pause yeterli değil. | GAP |
| 29 | Takibi sonra devam ettirebilir miyim? | Polling tekrar başlar, uzak iş etkilenmez. | PASS |
| 30 | Uygulama kapanıp açıldığında proje/indirme geri bulunuyor mu? | ProjectStore + pending download reconciliation. | PASS |
| 31 | Tek sahne hata verirse o sahne otomatik yeniden deneniyor mu? | Aynı run içinde sahne başına max 3 retry. | PASS |
| 32 | Fazladan karakter/yüz bozulması gibi semantik kalite hataları otomatik yakalanıyor mu? | Görsel semantic QC. | GAP |
| 33 | Retry sayısı sınırlı mı? | Max 3; sonsuz GPU tüketimi yok. | PASS |
| 34 | Finalden önce sahne bazlı insan onayı modu var mı? | İsteğe bağlı review/approve. | GAP |
| 35 | SFX otomatik ekleniyor mu? | Scream/whoosh/impact/tension/sting, AAC final. | PASS |
| 36 | Diyalogsuz global format destekleniyor mu? | Temel motor konuşmaya ihtiyaç duymuyor. | PASS |
| 37 | Dil girişi Türkçe olabilir mi? | Türkçe algılanır, CPU’da tr→en çevrilir, LTX’e English prompt gider. | PASS |
| 38 | Altyazısız global format mümkün mü? | Promptlar readable text/subtitle üretmemeyi zorunlu kılıyor. | PASS |
| 39 | Arka plan ses/müzik katmanı var mı? | Prosedürel tension bed + sting + SFX. | PASS |
| 40 | Model/ses bileşenlerinin lisansı ticari kullanım için gözden geçirildi mi? | LTX lisans koşulları ve gelir eşiği açıkça kayıt altına alınmalı; final release notunda belirtilmeli. | PARTIAL |
| 41 | Final MP4 otomatik indirilip doğrulanabiliyor mu? | Ana uygulamada kullanıcı başlatır; DownloadManager + metadata doğrulama. | PASS |
| 42 | Google Drive otomatik yedekleme var mı? | Opsiyonel bulut yedek. | GAP |
| 43 | Eski videonun yalnız tek sahnesini sonradan değiştirip yeniden üretebilir miyim? | Cross-run scene repair. | GAP |
| 44 | v1/v2/v3 alternatifleri uygulamada sürüm olarak tutuluyor mu? | Explicit version tree. | PARTIAL |
| 45 | Hangi hook/reveal türlerinin kullanıldığı analiz hafızası var mı? | Performans/format history. | GAP |
| 46 | YouTube’a otomatik/taslak yükleme var mı? | İsteğe bağlı publish connector. | GAP |
| 47 | Üretim bitince Android bildirimi var mı? | DownloadManager bildirimi var; üretim tamamlanma bildirimi ayrıca olmalı. | PARTIAL |
| 48 | Token kaynak koda/plaintext prefs’e gömülüyor mu? | Android Keystore AES-GCM. | PASS |
| 49 | Temiz kurulum→gerçek GPU→AI→indirme→oynatma zinciri fiziksel telefonda geçti mi? | Gerçek uçtan uca kabul. | E2E-BEKLİYOR |
| 50 | Mektup #001 gerçek T4 ile baştan sona üretildi mi? | Canonical live certificate + real FINAL.mp4. | E2E-BEKLİYOR |

## 51–100 — Teknik / dayanıklılık kabul soruları

| # | Teknik soru | Kabul standardı | Durum |
|---|---|---|---|
| 51 | APK CI’da gerçek olarak derleniyor mu? | `assembleDebug` + non-zero artifact. | PASS |
| 52 | Android 15’te uygulama açılıyor mu? | API35 instrumentation. | PASS |
| 53 | Android 16’da uygulama açılıyor mu? | API36 instrumentation. | PASS |
| 54 | Boş/siyah ekranda kalıyor mu? | Kritik view’lar görünür. | PASS |
| 55 | Uygulama process yeniden açılışında proje bilgisi korunuyor mu? | ProjectStore persistent. | PASS |
| 56 | Telefon reboot sonrası uzak işi yeniden bulma tasarımı var mı? | Slug/version/token persistence; gerçek reboot son turda tekrar testlenmeli. | MANUEL-SON |
| 57 | Android geri hareketi uygulamayı bozuyor mu? | Normal Activity lifecycle; fiziksel cihaz final turu. | MANUEL-SON |
| 58 | Arka plan→ön plan dönüşü güvenli mi? | Uzak iş bağımsız; UI state yeniden render. | PARTIAL |
| 59 | Ekran dönüşü UI’yı bozuyor mu? | Portrait kilitli. | PASS |
| 60 | Birden çok ekran boyutunda test var mı? | Şu an Pixel 6 profili API35/36; ek küçük/büyük profil yok. | PARTIAL |
| 61 | “Videoyu üret” butonu gerçek handler’a bağlı mı? | `startGeneration(false)`. | PASS |
| 62 | Üret butonu gerçek Kaggle job başlatıyor mu? | SaveKernel canlı çağrı. | E2E-BEKLİYOR |
| 63 | Hızlı çift tıklama duplicate job yaratıyor mu? | busy gate + button disable. | PASS |
| 64 | Stop gerçek remote job’u kesiyor mu? | Remote cancel gerekir. | GAP |
| 65 | Yenile takip sistemini yeniden başlatıyor mu? | `resumeTracking()` + status fetch. | PASS |
| 66 | Hatalı sahne yalnız aynı run içinde yeniden deneniyor mu? | Evet; cross-run tek sahne retry yok. | PARTIAL |
| 67 | Proje silme UI’sı var mı? | Güvenli delete/confirm. | GAP |
| 68 | Yazılan hikâye proje değişiminde kayboluyor mu? | ProjectStore ile korunur. | PASS |
| 69 | Uzun metin girişi kabul ediliyor mu? | EditText çok satırlı; production prompt chunking/translation mevcut. | PASS |
| 70 | Türkçe karakterler Python scriptini bozuyor mu? | Base64 embedding + UTF-8 + py_compile. | PASS |
| 71 | Özel karakter/emoji güvenli mi? | Base64 nedeniyle script injection riski düşürülmüş; emoji özel instrumentation testi eklenebilir. | PARTIAL |
| 72 | Klavye alt kontrolleri erişilemez yapıyor mu? | ScrollView + edge-to-edge/inset + Espresso. | PASS |
| 73 | İş sırasında durum metni gösteriliyor mu? | Busy/status states. | PASS |
| 74 | Gerçek progress yüzdesi var mı? | Scene/stage var; yüzde/ETA yok. | PARTIAL |
| 75 | Kaggle token gerçek sunucuda doğrulandı mı? | Live token test. | E2E-BEKLİYOR |
| 76 | Yanlış token anlaşılır hata veriyor mu? | Error path kodda var; canlı yanlış-token acceptance ayrıca yapılmalı. | E2E-BEKLİYOR |
| 77 | Token plaintext saklanmıyor mu? | SecureStore instrumentation. | PASS |
| 78 | Gerçek private T4 kernel oluşturuluyor mu? | Live E2E SaveKernel. | E2E-BEKLİYOR |
| 79 | Uzak iş için yeterli kimlik saklanıyor mu? | username + slug + version saklanır; session_id yok. | PASS |
| 80 | Uzak status okunuyor mu? | RPC sözleşmesi var; canlı kanıt bekliyor. | E2E-BEKLİYOR |
| 81 | Queued/running/complete/error ayrımı var mı? | normalizeStatus. | PASS |
| 82 | Kaggle error yanlışlıkla tamamlandı sayılıyor mu? | Failure/status/fallback ayrımı. | PASS |
| 83 | İnternet kesilince uygulama crash olmuyor mu? | Exception handling var; network-switch instrumentation yok. | PARTIAL |
| 84 | İnternet geri gelince status tekrar denenebilir mi? | Yenile/autoPoll. | PASS |
| 85 | Telefon ekranı kapalıyken bulut işi devam eder mi? | Kaggle remote; telefon compute yapmaz. | PASS |
| 86 | App process kapanınca Kaggle işi devam eder mi? | Remote architecture. | PASS |
| 87 | App açılınca proje/indirme takibi geri yükleniyor mu? | ProjectStore + reconcilePendingDownloads. | PASS |
| 88 | Gerçek FINAL.mp4 Kaggle’da oluştuğu kanıtlandı mı? | Live certificate. | E2E-BEKLİYOR |
| 89 | Gerçek final telefon oynatıcısında açıldı mı? | Physical/live final acceptance. | E2E-BEKLİYOR |
| 90 | 0-byte/çok küçük MP4 başarı sayılıyor mu? | Server + Android min-size checks. | PASS |
| 91 | Final gerçekten 1080×1920 mi? | ffprobe + MediaMetadataRetriever. | PASS |
| 92 | 5 sahne doğru sırada birleştiriliyor mu? | Generated list sırasıyla concat. | PASS |
| 93 | Siyah/bozuk görsel boşluk semantik olarak yakalanıyor mu? | ffprobe teknik bozukluğu yakalar; içerik-semantic boşluk analizi yok. | PARTIAL |
| 94 | Final süresi kontrol ediliyor mu? | >=8s. | PASS |
| 95 | Fazladan karakter otomatik tespit ediliyor mu? | Semantic vision QC. | GAP |
| 96 | Deforme yüz/nesne otomatik tespit ediliyor mu? | Semantic vision QC. | GAP |
| 97 | Başarılı sahneler aynı run içinde hata sonrası korunuyor mu? | Önceki generated list korunur; sonraki scene retry edilir. | PASS |
| 98 | Sonradan yalnız scene N yeniden üretilebiliyor mu? | Persisted scene artifact + repair job gerekir. | GAP |
| 99 | 100+ proje birbirine karışmadan saklanıyor mu? | 120 proje izolasyon + 500 cap instrumentation. | PASS |
| 100 | Final APK teslim kapısı nedir? | 1–99 içindeki kritik GAP’ler kapatılmalı, gerçek Kaggle T4 E2E PASS alınmalı, kalıcı release signing yapılmalı ve fiziksel telefon son turu geçmeli. | E2E-BEKLİYOR / RELEASE-BEKLİYOR |

## Gerçek E2E sertifika altyapısı

İki ayrı yol hazırdır:

1. APK içindeki geçici `VF Canlı Test` ekranı (`LiveE2EActivity`): gerçek token ile private T4 kernel başlatır, `status.json` V3 sertifikasını doğrular, `FINAL.mp4` indirir ve Android medya metadatasını kontrol eder.
2. `.github/workflows/kaggle-live-e2e.yml`: GitHub Secrets `KAGGLE_USERNAME` + `KAGGLE_API_TOKEN` ile, Android uygulamasının kullandığı aynı V3 Python scriptini T4’e yollar ve `e2e_certificate.json` üretir. Normal commitlerde GPU kullanmaz.

Canlı sertifika PASS koşulları: `ai_ok=true`, `stage=COMPLETE`, engine `story-v3`, 5 sahne, English prompt, `translation.mode=tr_to_en`, previous-scene continuity, strength yaklaşık 0.65, AAC audio, `FINAL.mp4`, hata yok; ardından dosya >=100 KB, 1080×1920, video+audio stream ve >=8s.

## Nihai politika

- CI kırmızıysa final APK yok.
- Fallback hiçbir koşulda AI başarı değildir.
- Test geçsin diye assertion kaldırılmaz/basitleştirilmez.
- Gerçek T4 sertifikası alınmadan #48/#49/#50 ve #62/#75/#78/#80/#88/#89/#100 PASS olamaz.
- Kalıcı release signing çözülmeden “son dağıtım APK’sı” denmez.
- Bu matris her önemli geliştirme turunda güncellenir.
