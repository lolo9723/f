# Telefon motoru ve Drive yedeği — Lab 3.7

64 bit ARM Android 7+ içinde Python 3.13 ve NumPy 1.26.2 çalışır. HTTP sunucusu, AWS hesabı veya uygulamaya gömülü Google OAuth anahtarı gerekmez. Lab 2.x paket kimliği ve imza anahtarı korunur.

## Kurulum ve seçim

1. Radar ekranında **Telefon motoru · Drive yedeği** kartını aç.
2. Tüm coinler otomatik seçilir: Binance TR'deki işlem yapılabilen bütün TRY pariteleri. Coin sayısı veya hacim alt sınırı yoktur; yeni listelemeler saatlik keşifle eklenir.
3. **Telefonda araştırmayı başlat** ile görünür bildirimli servisi ve periyodik işleri başlat.
4. **Drive hesabı ve yedek konumunu seç** düğmesine bas. Android dosya seçicisinin sol menüsünden Google Drive'ı aç, hesabını ve klasörünü seç, `Oran-Radar-Phone.zip` dosyasını oluştur.
5. **Şimdi Drive'a yedekle** ile ilk teslimi dene. Düzenli işler ağ uygunsa en az 15 dakika aralıkla aynı ZIP'i yeniler.

Google Drive Android uygulaması kurulu, hesaba giriş yapılmış ve DocumentsProvider üzerinden kalıcı yazma izni veriyor olmalıdır. İptal edilen seçim mevcut hedefi değiştirmez; İndirilenler klasörü Drive gibi kabul edilmez. Hedef değiştirmek önceki Drive dosyasını silmez. Erişim URI'si, hesap kimlik bilgileri ve uygulamanın sunucu anahtarı yedeğe girmez.

## Çalışma sınırları

Hızlı mod görünür bildirimli `dataSync` foreground service kullanır. Uzun araştırma işleri arasında 5 dakika bekler; bağımsız puan işi ayrı yürütücüde 30 saniyelik istek aralığı hedefler. Ekranı kapatmak tek başına servisi durdurmaz. Duraklat eylemi yeni işleri durdurur; devam eden Python işinin son verileri bitene kadar yazılabilir.

WorkManager ağ gerektiren kalıcı periyodik işleri en az 15 dakikalık aralıklarla hedefler. Doze, pil tasarrufu, üretici kısıtları ve sistem yükü geciktirebilir. Android 15+ arka plandaki dataSync servisini 24 saatte toplam 6 saatle sınırlar; `onTimeout` hızlı servisi durdurur, periyodik işler korunur. Arka plandan sınırsız foreground service başlatılmaya çalışılmaz. Telefon kapanınca, Zorla durdur yapılınca veya Android izin vermeyince çalışılamaz. Audit kartı boşlukları ve tamamlanmamış işleri gösterir; kuyruğa kabul tamamlanma değildir.

Rally puan taraması ve 730 günlük araştırma ayrı kalıcı sıralar kullanır. Her işin süre bütçesi vardır; yarıda kalan sıra sonraki işte devam eder. İlk coinlerden tekrar başlayıp sonraki coinleri aç bırakmaz. Puan ve uzun araştırma bağımsız native kilitlerle çalışır; uzun işin bitmesi yeni puan turu için beklenmez. Aktif manuel takibi sırayla araştırmaya karıştırır. HTTP 418/429 durumunda borsanın Retry-After süresi saklanır ve yeni piyasa istekleri bekletilir. Keşif/ağ hatası son bilinen listeyi veya geçmiş verileri silmez. İlk tam piyasa turu ve geçmiş tamamlama, coin sayısına ve ağ hızına göre birden fazla iş sürebilir; tüm coinler aynı anda güncel gibi sunulmaz.

Açık/eksik mumlar tamamlanmış sayılmaz. Eğitim şarj sırasında yapılır; şarj yokken puan, veri toplama, mevcut modelle değerlendirme ve sonuç takibi sürer. İlk model yoksa tahmin uydurulmaz, WAITING_CHARGE raporlanır. Telefon motorunda açılan yeni manuel kayıtlar arka planda izlenir; eski JS cihaz kayıtları otomatik yeniden tarihlendirilmez ve uygulama açıkken takip edilir.

## Önceki Rally Score ve tüm coin listesi

Temel 0–100 Rally Score, orijinal `backend/engine.py::score_market` hesabını korur: trend %25, erken hareket %17, dakika hacmi %21, sıkışma %14, emir defteri %16, Bitcoin 24 saat hareketi %7. Aynı dönüşümler ve ağırlıklarla çalışır; orijinal fonksiyonla sayısal eşitlik test edilir. Saatlik/dakikalık kapalı mumlar ve gerçek emir defteri gerekir. Veri azsa puan `null` olur; eski puan zamanı ve güncellik durumu ayrı gösterilir. Temel endeks başarı olasılığı değildir. Lab 3.5 olgun araştırmanın sınırlı katkısını bu temel puana ekler; araştırmanın olasılıkları ve stop-loss verileri ayrıca korunur.

Radar, keşfedilen tüm coinleri puana göre sıralar; arama alanı içerir. Liste sanallaştırılır, uzun araştırma detayları coin kartından açılır. Böylece tüm coinler gösterilirken yüzlerce ayrıntı aynı anda çizilmez. Lab 3.0'daki seçim sınırı kaldırılmıştır; güncellemede eski yerel veri, Drive hedefi ve kayıtlar korunur, telefon modu otomatik tüm coin keşfine geçer. Düşük hacimli ve stable varlıkların aktif TRY pariteleri de keşfe dahildir.

## Bağımsız puan güncellemesi — Lab 3.3

Lab 3.2'de ekran 15 saniyede bir snapshot okuyordu; yeni puan hesabı 7 dakikaya kadar sürebilen araştırma işinin başında ve sonrasında 5 dakikalık beklemeyle yapılıyordu. Lab 3.3 puan-only ve araştırma-only yollarını ayırır. Puan işinin 20 saniyelik yumuşak bütçesi vardır; en fazla tek paritenin sınırlı ağ istekleriyle aşılabilir. Aynı anda ikinci puan işi açılmaz. Servis görünürken 30 saniyelik hedef aralığı ve uygulama açıkken 15 saniyelik istek/snapshot döngüsü vardır. Borsa Retry-After, ağ ve Android sınırları gerçek süreyi uzatabilir.

Radar'da görünen coinler, detay ekranında seçilen coin öncelik listesine girer. Öncelik ve normal sıra birbirine karıştırılır; kısa bütçe yalnızca bir coine yetiyorsa öne geçen sıra sonraki turda değiştirilir. Tüm evrenin kalıcı puan sırası korunur; sayı sınırı konmaz. Bir coinin 30 saniyede mutlaka güncellendiği veya tüm coinlerin aynı anda tarandığı iddia edilmez.

**Puanları şimdi yenile** işi kuyruğa alır; tamamlanmış gibi bildirmez. Son tarama zamanı ve hata gösterilir; her coin kartı kendi son hesap zamanını taşır. Motor duraklatılmışsa düğme başlatma gerektiğini açıklar. Uygulama arka plana geçince ekran önceliği temizlenir; görünür servis varsa normal tüm coin taraması sürer. Servis çalışmıyorsa kalıcı WorkManager işleri en az 15 dakikalık hedefle küçük puan turu ve araştırma çalıştırır.

Son kapalı saatlik/dakikalık mumları içeren cache aynı kapanış döneminde tekrar kullanılabilir. Yeni dakika/saatte ilgili mum uçları yeniden çağrılır. Emir defteri her gerçek hesapta yeniden alınır. Açık mum veya eksik/veri boşluğu puan üretmez. Formül, ağırlıklar ve tam sayı yuvarlama korunur; veri/yuvarlama değişmiyorsa aynı puan yeni zamanda tekrar hesaplanabilir. Ortak piyasa/ilerleme metadata'sı atomik olarak birleştirilir; uzun araştırmanın eski kopyası yeni puan/ticker/sıra veya başka işin hız limiti bilgisini ezemez. HTTP 418/429 beklemesi her iki iş yolunun sonraki ağ isteğinden önce kontrol edilir.

## Alış planı ve coin başına sanal kayıt

Her coin kartında, analiz detayını açmadan **Önerilen alış**, **Önerilen hedef**, **Önerilen stop-loss** ve **Aldım gibi kaydet** görünür. En az 24 doğrulanmış kapalı saatlik mum, güncel fiyat ve kesintisiz veri gerekir; 120 saatlik kısa tarama uzun model eğitimini beklemeden ön plan üretebilir. Eksik veri sebebi gösterilir. Son plan silinmez ama eski fiyat veya yeni kapanmış saatlik mum eksikse güncel sayılmaz, forma aktarılmaz.

Lab 3.4 alış fiyatını canlı kotasyondan yeniden üretmez. Son kapalı mum fiyatı ve o andaki ATR, son 24 saat desteği veya hacim ağırlıklı tipik fiyatla seviye sabitlenir. Yeni kotasyon yalnızca güncellik ve fiyat mesafesini günceller; yeni kapalı mum veya araştırma turu gelince seviyeler yeniden hesaplanır. Fiyat seviyeye/altına inmişse eski aday forma aktarılmaz. Eski v1 planları yeni analiz gelene kadar güncel sayılmaz.

Sekiz sabit alış politikası ayrı, kronolojik eğitim/doğrulama/test dönemlerinde incelenir. Seçim geçmiş doğrulama dönemindeki kötümser, masraf sonrası karar başına net getiriden yapılır; dolmayan emirler sıfır getiriyle ortalamaya girer. Hemen alış referansı, hedef/stop sonuçları ve aynı mumdaki belirsiz dolum sırası ayrıca ölçülür. Araştırma şarjdayken yeni verilerle günlük yenilenir; kısa puan işi kayıtlı raporu kullanır. Başlangıçta uzun veri/şarj yoksa ATR ön adayı gösterilir. Her kartta **Alış seviyesi araştırması**, seçilen kural, etkin/gereken n ve ayrı test sonuçları görünür; ayrıntı açılınca adaylar karşılaştırılır. Optimum/kârlılık garantisi verilmez. [Yöntem ve kaynaklar](ENTRY_RESEARCH.md).

Alış araştırmasının eksik/gecikmiş olması kontrol düğmesinde ayrı görünür ve kurtarma işi kuyruğa alınır. Stop mesafesi araştırması korunur; alış adayının tarihsel testi mevcut ATR risk kuralını kullanır ve farklı stop stratejisine kanıt aktarmaz.

Her kartın formu o coine sabitlenir. Güncel planın alış/hedef/stop fiyatları doldurulur ve değiştirilebilir. **Sanal alışı kaydet**, o maliyetten zaten aldığını varsayan 48 saatlik takip açar; limit emir beklemez ve gerçek borsa emri vermez. Kayıtlar **Aktif** sekmesinde bağımsız görünür. Telefon motorunda kaydın ilk plan/gösterge bağlamı korunur; hedef, stop, zaman aşımı ve stop sonrası toparlanma mevcut doğrulama kurallarıyla izlenir. Yenilenen öneri kayıtlı stopu otomatik değiştirmez. Eski sürüm/verisiz coinlerde manuel fiyat formu kullanılabilir.

## Model, geçmiş ve yedek

Mevcut 88 öncü değişken, purged eğitim/seçim/test, güç/etki/multiplicity'ye göre örnek sayısı, kalibrasyon, yüzde/ATR stop araştırması ve stop sonrası takip kullanılır. Android'de SciPy yerine açıkça seçilen `portable-lbfgs-v1` sayısal motor vardır: normal kuyrukları erfc/NormalDist, Student-t eksik beta devamlı kesri, Fisher sabit marjlı tam hipergeometrik destek, ridge lojistik çözüm Armijo aramalı L-BFGS. Yakınsamayan model kabul edilmez. Bu rutinler SciPy referanslarıyla doğrulanır; ayrıntılı sonuçlar [VALIDATION.md](VALIDATION.md) içinde.

Atomik çitlemeli state/snapshot dosyaları ve SQLite arşivi birlikte kullanılır. SQLite kapalı mumları tekil anahtarlarla, her yeni kapalı mumda üretilen 88 değişkeni ve stop bağlamını, Rally puanlarını/bileşenlerini, model ve alış araştırma turlarını, yön tahminlerini, sabit alış planlarını ve stop denemelerini saklar. UI'nin 2.000 kayıt sınırı bu arşivi silmez. Gereksiz 5 dakikalık dosya kopyaları budanır; son iki state/public sürümü korunur. ZIP, SQLite backup API'nin tutarlı kopyasını, son araştırma durumlarını, coin listesini, tarama sıralarını, son puan/grafik dosyalarını, manuel kayıtları ve manifestoyu içerir. Geçmiş arşivi zamanla büyür; telefon ve Drive'da yer gerekir.

Drive dosyasına yazma yarıda kesilirse buluttaki ZIP eksik kalabilir. Tam yerel ZIP korunur ve sonraki teslimde yeniden yazılır; hata tamamlanmış yedek gibi gösterilmez.

Tam ZIP önce telefonda hazırlanır; sonra seçilen Drive dosyasına yazılır. Başarılı yazma/kapatma **Drive sağlayıcısına teslim** olarak gösterilir. Drive uygulamasının buluta senkronizasyonu ayrıca gerçekleşir; uygulama bunu Google Drive API ile doğrulamış gibi göstermez. Son teslim zamanı, boyut ve hata ayrı raporlanır. Ağ/izin hatasında yerel veriler korunur ve sonraki iş tekrar dener. Gerçek Android cihazında servis, Drive hesap/klasör seçimi ve bulut senkronizasyonu uçtan uca test edilmedi; derleme ve masaüstü testleri bu doğrulamaların yerine geçmez.

Kaynaklar: [Android servis süre sınırı](https://developer.android.com/develop/background-work/services/fgs/timeout), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent-work/getting-started/define-work), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [Chaquopy](https://chaquo.com/chaquopy/doc/current/android.html).


## Olgun araştırmanın puana katılması — Lab 3.5

Telefonun hızlı puan taraması, kayıtlı araştırma raporlarını ve güncel planı kullanarak temel Rally puanına en fazla ±20 puan ekler. Uzun eğitim hızlı iş içinde çalışmaz. Yön modeli (±14), alış adayı (±3), stop araştırması (0–3) ayrı olgunluk kapılarından geçer. Yön/stop için ayrı testin yanında ileriye dönük kalite gerekir; alış katkısı ayrı tarihsel testtir ve ileriye dönük kanıt gibi gösterilmez. Henüz olgunlaşmamış veri veya daha çok tarama puan artırmaz. Ham hipotez sayısı aynı kanıtın tekrar tekrar puanlanmasına dönüşmez.

Kartta **Temel puan**, **araştırma etkisi** ve **Olgun araştırma puana katılıyor / Araştırma olgunlaşıyor / Araştırma katkısı duraklatıldı** görünür. Detayda üç ailenin puanı, sebebi ve yön akışlarının etkin/gereken n değerleri bulunur. Tüm coin sıralaması bu güncel toplam puanı kullanır; 0–100 sınırı ve tam sayı yuvarlama korunur. Geçmiş kayıtlar ilk puan/kanıt bağlamını tutar.

Snapshot ve detay okuması, son temel puandan katkıyı yeniden hesaplayarak eskimiş veya bozulan araştırmayı yeni borsa isteğini beklemeden devreden çıkarır. Son piyasa tarama zamanı değiştirilmez. Yeniden okuma katkıyı katlayamaz. Offline ekranda son katkının güncelliğinin doğrulanmadığı gösterilir. Kısa puan taramasının SQLite kaydı, temel puanı, hesaplanan katkıları, sürümleri ve kullanılan kanıt zamanlarını içerir; mevcut Drive ZIP yedeğinde korunur. [Kurallar ve kaynaklar](RESEARCH_SCORE.md).


## Alış formunun fiyat/stop aktarımı — Lab 3.6

Önceki form, güncel fiyat planı kullanılamazsa alış alanını doğrudan piyasa fiyatıyla, hedefi +%3 örneğiyle doldurup stopu boş bırakıyordu. Lab 3.6 bu geri dönüşü kaldırır. Piyasa fiyatı ayrı gözlemdir; önerilen limit alış, hedef ve stop-loss ayrıca görünür. Güncel planın üç fiyatı birlikte aktarılır. Eksik/eski planda manuel alanlar boş kalır; varsa son seviyeler ve bekleme nedeni görünür. **Son analiz seviyelerini manuel taslağa al** açıkça güncel öneri sayılmayan seviyeleri üç alana birlikte kopyalar.

Form açıldığında veya **Öneriyi yenile** seçildiğinde `/market?pair=...` son planı okur. Bu okuma kendi başına yeni exchange taraması/eğitim başlatmış gibi gösterilmez; veri eskise motorun puan yenileme/kontrol işlemleri gerekir. Güncel yanıt, dokunulmamış alanları doldurabilir; kullanıcı alış/hedef/stopu değiştirince sonraki yanıt bu değerleri ezmez. Başka coin seçimi, kapanmış form veya farklı coine ait yanıt eski fiyatları geri getiremez. Başarısız okumada son öneri görünür kalır; hata ve güncellik durumu ayrı gösterilir. Kullanıcı güncel öneriyi açıkça alanlara tekrar uygulayabilir.

ResearchCard eski planı tamamen gizlemez; form eski seviyeleri güncel ilan etmeden gösterebilir. Genel seçim `/pairs` yolunda da trade_plan ve fiyat gözlem zamanı taşınır; detay formu opportunity planını geçirir. Piyasa fiyatı eski planın girişine/altına inince API ve form planı güncel saymaz; giriş/stop/hedef fiyatları veya önceki kayıt değişmez. Manuel sanal kayıt, eskisi gibi şimdi alınmış maliyeti varsayar; gerçek/bekleyen limit emir değildir.

100 TL altı fiyatlarda 10 anlamlı basamak gösterilir. Sabit altı ondalık, küçük coinlerde ayrı piyasa/alış/stop seviyelerini sıfır veya aynı sayı gibi gösterebiliyordu. Yeni gösterim fiyatları hesapta/depoda yuvarlamaz; yalnızca görüntüyü düzeltir. 100 TL ve üstünde iki ondalık korunur. Verisiz coin için stop/optimum iddia edilmez.


## Bağımsız canlı fiyat akışı — Lab 3.7

Telefon motoru artık yalnızca puan turunun başındaki toplu ticker isteğine dayanmaz. Native `PriceFeed`, Binance TR miniTicker WebSocket'ini Python'dan ve araştırma/puan yürütücülerinden bağımsız çalıştırır. `prices(visible)` köprüsü main queue üzerinde hazır bellek snapshot'ını okur; Python API'nin uzun işi bitmesini beklemez. Uygulama aktifken tek paylaşılan istemci yaklaşık 2 saniyede okur. Radar/detay fiyatları, alış formundaki piyasa gözlemi ve yaş etiketi yenilenir; kullanıcının düzenlediği alış/hedef/stop değişmez.

Görünür servis akış sahibidir; ekrandan çıkınca servis izinleriyle socket sürer. Servis sistem tarafından öldürülürse enabled durumu için START_STICKY yeniden başlatma denenebilir; bu Android'in izin/timeout/zorla durdur sınırını aşmaz. Hızlı servis yokken yalnızca aktif ekran kısa görünürlük lease'iyle akışı tutar. Arka plana çıkınca bu lease bırakılır; FGS yoksa hızlı socket kapanır. WorkManager işlerinde sınırlı REST fiyat okuması ve mevcut araştırma sürer. Android 15+ dataSync toplam süre sınırı ve telefonun kapanması hâlâ geçerlidir.

Geçersiz, gecikmiş, tekrar eden veya sırası eski frame'ler yeni fiyat/tarih üretmez. Yeni native fiyatlar `PHONE-LIVE.json` içinde yaklaşık 5 saniyelik aralıkla bütün dosya ve atomik rename olarak saklanır. Python okuması bu quote'ları eski REST metadata'sıyla zamana göre birleştirir; yeni quote plan fiyatını kaydırmaz, girişe/altına inilirse planı güncel saymayı durdurur. Snapshot/puan/plan okumaları son gözlemi görür. Kontrol düğmesi native akışın bekleyen/eski/duraklatılmış durumunu da denetler; kurtarma isteği borsa beklemesine uyarak fiyat bağlantısını tekrar dener. Yeni mum, model eğitimi veya bağımsız başarı örneği uydurulmaz. Mevcut Drive ZIP yedeği native fiyat snapshot'ını kapsar; her ham ticker frame'inin sonsuz arşivi değildir. [Detaylı yöntem ve sınırlar](LIVE_PRICES.md).
