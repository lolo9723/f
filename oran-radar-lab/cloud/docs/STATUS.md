# Teslim durumu — 17.10.2026

Cloud 4.0 uygulama dosyaları erişilebilir `lolo9723/f` Android kaynaklarına entegre edilmiştir. Bu belge ilk veri toplama çalışmasının ayrıntılı faz kaydıdır; güncel Android derlemesi üst dizindeki README ve GitHub Actions işinde izlenir.

| Faz | Somut çıktı | Durum / sınır |
|---|---|---|
| 1. İnceleme | APK yapılandırması, Python modül envanteri, paket kimliği | Tamamlandı; GitHub kaynakları 404 nedeniyle görülemedi |
| 2. Altyapı | Cloudflare Worker, D1 migration, yetki rolleri, kullanıcı ayarları, API | Yerelde test edildi; hesap üzerinde yayımlanmadı |
| 3. Veri bankası | Global/TR adaptörleri, tarihsel katalog, hash doğrulama, Parquet, karantina, devam kuyruğu | Global envanter 3.754 benzersiz parite; üç paritede gerçek tarihsel indirme yapıldı. Bütün evren tamamlanmadı. TR HTTP 451 |
| 4. Güncelleme | Bütçeli Python çalıştırıcısı, GitHub Actions, cron iş kaydı, R2 kalıcılık adaptörü | Kod hazır; hesap secret'ları/kalıcı çalıştırıcı bağlanmadı. Dakikalık hizmet devrede değil |
| 5. Analiz havuzu | Nedensel özellikler, pencere sürümü, manifest bağı, Parquet feature set | Test edildi; bütün sembollerin analiz havuzu henüz hesaplanmadı |
| 6. Ralli/örüntü | Baz oran, lojistik ve gradient boosting karşılaştırması; geçmiş benzerlik sorgusu | Tarihsel araştırma kodu; doğrulanmış TR üretim modeli yok. Rejim izleme ve zaman serisi karşılaştırması eksik |
| 7. Laboratuvar | Limit fill, maliyet, hedef/stop, pending/ambiguous/unverifiable; kronolojik holdout | Otomatik testler mevcut; gerçek ileriye dönük TR denemesi başlamadı |
| 8. Öğrenme | Aday model kaydı, ileriye dönük kanıt gerektiren terfi, rollback | Kod/test düzeyi; otomatik eğitim/terfi/bozulma izleme döngüsü devrede değil |
| 9. Panel/Android | Türkçe panel, API cache istemcisi, SecureStore/FCM bağdaştırıcısı | API yerelde test edildi. Görsel tarayıcı kontrolü Chromium indirilemediği için tamamlanmadı. APK kaynak entegrasyonu/derleme ve FCM cihaz testi yapılmadı |

## Gerçek doğrulama

- `reports/coverage.json`: Kaynak bazlı envanter, gerçek satır/boyut/tarih sınırları, aralık bazında kapsam ve eksik dönemler. Tarih uçları arasındaki bütün zamanların bulunduğu anlamına gelmez.
- `reports/python-tests.txt`: Python test sonucu.
- `reports/node-tests.txt`: API ve Android cache istemcisi test sonucu.
- `reports/local-integration.json`: Gerçek yerel workerd + D1 üzerinde sağlık, giriş zorunluluğu, kapsam aktarımı/okuma, ayar yazımı, rol sınırı, CORS, anahtar iptali, cron kontrolü.
- `reports/research-btc.json` varsa: Global BTCUSDT üzerinde tarihsel aday deneyidir. Üretim modeli, kalibre olasılık veya TR işlemi başarısı değildir.
- `pilot-data/`: İndirilen gerçek Parquet dosyaları, kaynak manifesti, SQLite kontrol noktası ve karantina. Banka dışa aktarımıdır.

## Erişim gerektiren noktalar

1. `https://github.com/aiaiai97/Oran-Radar-Lab` bağlı hesaplardan erişilebilir olmalı. Kaynaklar görüldükten sonra mevcut motorun korunacak bölümleri ve gerçek Android dosyalarına yapılacak yamalar belirlenebilir. Başka depoya yönlendirme yapılmadı.
2. Workers/D1 yayını için Cloudflare hesabı ve yetkisi; sürekli işler için kalıcı çalışan ortam gerekir. R2 isteğe bağlıdır ve abonelik onayı olmadan açılmaz.
3. TR resmî API'sinin erişilebilir olduğu bir çalışma konumu gerekir. Bu ortamın HTTP 451 engeli aşılmadı.
4. Android kaynak projesi, imzalama süreci ve Firebase Android yapılandırması gerekir. Mevcut APK içinden eksiksiz kaynak elde edilmiş gibi davranılmadı.

Ücretsiz hizmetler ve bu işin kaynak sınırları `FREE-TIER.md` içinde. Gizli anahtar veya ödeme bilgisi pakette yoktur.
