# Doğrulama raporu — Oran Radar Cloud 4.0

17.10.2026 tarihinde gerçek resmî kaynaklar ve yerel çalışma ortamında ölçüldü.

| Ölçüm | Sonuç |
|---|---:|
| Global API pariteleri | 3.729 |
| Global arşiv pariteleri | 3.745 |
| Birleşik benzersiz Global parite | 3.754 |
| TR keşfi | HTTP 451 — ölçülemedi |
| Tarihsel veri alınan parite | 3: BTCUSDT, ETHUSDT, BNBUSDT |
| Verisi henüz alınmayan Global parite | 3,751 |
| Doğrulanmış mum | 217,954 |
| Günlük / saatlik mum | 9.915 / 208.039 |
| Doğrulanmış Parquet bölüm | 613 |
| Zaman kapsamı tam bölüm | 571 |
| Eksik aralıklı bölüm | 42 |
| Başarısız bölüm | 43 |
| Geçersiz satır nedeniyle karantinadaki kaynak dosya | 41 |
| Doğrulanmış Parquet bayt | 15,420,079 |
| İlk / son kayıt (UTC) | 2017-08-17T00:00:00+00:00 / 2026-09-30T23:59:59.999000+00:00 |

Bu tarih uçları arasında kesintisiz veri olduğu veya bütün paritelerin geçmişinin alındığı anlamına gelmez. Mumlar 613 doğrulanmış bölümde tutuluyor; eksik dönemler ve başarısız dosyalar coverage.json içinde listeleniyor. Eski arşivlerde erken biten kısmi saatlik mumlar görüldü; standart tam mum sayılmadı.

İndirilebilen 654 kaynak bölümünün 613 tanesi satır/şema/hash denetimini geçti, 41 tanesi reddedildi. Bu %93.73 oranı bir **dosya doğrulama oranıdır**; veri evreninin tamamlanması veya model başarısı değildir. Arşivde bulunmayan iki BNB Ağustos 2017 bölümü ayrıca başarısız olarak kayıtlıdır.

Tam yeniden okuma denetimi: 217,954 satır; 217,954 benzersiz mantıksal mum; hash/satır hatası yok.

## Testler

- 50 Python testi geçti: zaman birimleri, veri kalitesi, mükerrer kayıt, devam, kesilme, karantina, kota, nedensellik, belirsiz işlem sırası, model terfi/rollback, içe aktarma.
- 12 Node testi geçti: API yetkileri, CORS, veri sözleşmesi, istek sınırı ve Android cache davranışı.
- 9 gerçek yerel workerd + D1 entegrasyon kontrolü geçti.
- Worker bundle dry-run ve yerel D1 migration uygulaması başarılı.
- JavaScript sözdizimi ve Python derleme kontrolleri geçti.
- Tarayıcı görsel kontrolü tamamlanmadı: Chromium indirmesi çalışmadı. Gerçek telefon/FCM teslim testi yapılmadı.

## Tarihsel aday model

Yalnızca Global BTCUSDT üzerinde deney yapıldı. Purging/embargo sonrası 35,239 geliştirme ve 8,821 son kronolojik holdout gözlemi kullanıldı. Seçilen aday logistic. Model, feature-set ve kaynak manifest hash'leri research-btc.json içinde. Üretime alınmadı; ileriye dönük TR işlem sayısı 0. Koşullu hedef olasılığı henüz kalibre edilmedi. Bu deneyden gelir/başarı garantisi çıkarılamaz.

## Yayın ve erişim

İstenen GitHub deposu 404, TR API 451 döndü. GitHub'a yazılmadı. Cloudflare hesabına yayın, R2 aboneliği, otomatik veri işi etkinleştirme, yeni APK derleme veya gerçek işlem yapılmadı. Ayrıntılı faz durumu docs/STATUS.md içinde.
