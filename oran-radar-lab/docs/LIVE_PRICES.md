# Canlı TRY fiyat akışı — Lab 3.7

Önceki telefon modu, fiyatları puan turunun başındaki tek `/ticker/24hr` isteğinden alıyordu. Puan işi/parite ağı ağırlaşınca fiyatlar da bekliyordu; ekran 15 saniyelik snapshot okumasıyla güncelleniyordu. Uygulamanın JS cihaz radarı WebSocket'i telefon modunda devreye girmiyordu. Lab 3.7 bu fiyat yolu için ayrı native akış kullanır.

## Kaynak ve çalışma

- Native OkHttp: `wss://stream-cloud.binance.tr/ws/!miniTicker@arr`. TRY sembolleri için kapanış/son fiyat, 24 saat açılışa göre değişim, quote volume ve exchange event zamanı doğrulanır. USD/USDT fiyatını kurla TRY'ye çeviren farklı kaynak kullanılmaz.
- REST yedeği: `https://api.binance.me/api/v1/ticker/24hr`. Sağlıklı WS'de yaklaşık 30 saniyelik genel snapshot, yeni geçerli TRY frame'i yokken yaklaşık 10 saniyelik yedek aralığı hedeflenir. İnternet/Retry-After/HTTP süreleri aralığı uzatabilir. Aynı anda yalnızca bir REST call vardır.
- Ayrı OkHttp network thread'leri, tek fiyat durum yürütücüsü ve hafızada JSON snapshot vardır. Python/GIL, research/score native kilidi ve SQLite model işleri bu yolu tutmaz. Fiyatların native bridge okuması main queue üzerindedir; ağır Python API queue'sunu beklemez.
- UI tek paylaşılan poller ile aktif uygulamada yaklaşık 2 saniyede snapshot alır. Radar, detay ve satın alma formundaki piyasa fiyatı güncellenir. Son fiyat yaşı ayrıca görünür; model/puan ve mum zamanları yenilenmiş gibi gösterilmez. Başarısız bridge okumalarında fiyat/tarih korunur ve durum STALE olur.
- Coin sayısı kesilmez. Native test 201 TRY fiyatını saklar; aktif coin listesi ve yeni keşif mevcut Binance TR spot katalog kuralını korur.

## Bağlantı, sıralama ve kesinti

Socket hata/kapanışta 2, 4, 8…60 saniyeye sınırlı gecikmeyle yeniden bağlanır. Ağ geri gelince uygun istek tekrar denenir. Son geçerli WS TRY gözleminden 30 saniye geçtiğinde bağlantı yenilenir; kontrol mesajı veya eski/tekrarlanmış frame socket'i veri bakımından sağlıklı saydırmaz. LIVE yalnızca yeni WS fiyatıyla, REST yalnızca yeni HTTP gözlemiyle gösterilir.

WS event zamanı son 30 saniye içinde ve telefonun en fazla 5 saniye ilerisinde olmalıdır. Sembol, fiyat/açılış/volume ve sayı sonluluğu kontrol edilir. Eski veya eşit event-order güncellemesi atlanır; tekrar edilen eski paket gözlem yaşını tazelemez. Aynı fiyat yeni event zamanı ile tekrar gözlenirse zaman güncellenebilir; değerin mutlaka değişmesi gerekmez.

REST yanıtı, istek başladıktan sonra gelmiş WS gözlemini ezmez. Geçerli yeni REST gözlemi sonra kaynak olabilir. Restore, önceki gözlem zamanını ve kaynağını korur; açılışta cache canlı data gibi yeniden tarihlendirilmez. Kapanmış/önceki socket veya REST callback'i generation kontrolüyle yeni oturumu değiştiremez. Body okuma timeout'u call'ı kalıcı meşgul bırakmaz.

HTTP 418/429 Retry-After native durumda hemen saklanır ve cache'ye geciktirilmeden yazılır. Python REST istekleri aynı backoff'u okur; native de Python'un mevcut backoff'unu denetler. Açık ve veri sağlayan WS sırf REST kısıtından dolayı kapatılmaz; yeni istek/reconnect borsa beklemesine uyar. TLS doğrulaması açıktır; anahtar/OAuth/şifre socket'e veya APK'ya eklenmez.

## Android yaşam döngüsü

Telefon motorunu **Telefonda araştırmayı başlat / Tüm coinlerin hızlı takibini başlat** ile etkinleştir. Görünür servis, UI kapanınca akışın sahibi olmaya devam eder. Servis enabled durumunda START_STICKY yeniden başlatma ister; OS bunun ne zaman/nasıl olacağına karar verir. Timeout veya duraklatma socket sahibini bırakır. Aktif ekran, hızlı servis yoksa 15 saniyelik yenilenen lease ile socket tutabilir. Ekran arka plana çıkınca bu lease bırakılır; FGS de yoksa hızlı bağlantı durur. WorkManager periyodik çalışma ve sınırlı REST okuması ayrı kalır.

Telefonun kapanması, Zorla durdur, ağ yokluğu, Doze/üretici kısıtları ve Android 15+ dataSync için 24 saatte toplam 6 saat sınırı hâlâ geçerlidir. 24 saat kesintisiz çalışma veya her coinin 2 saniyede kesin değişmesi garanti edilmez. Ekrandaki 2 saniye hedefi, native cache okuma aralığıdır; exchange yayın sıklığı ve gerçek fiyat değişimi ayrı kavramlardır.

Kontrol düğmesi native fiyat akışındaki ilk veri bekleme, gecikme veya hızlı akışın duraklamasını ayrıca gösterir. Eksik iş kurtarma eylemi fiyat bağlantısını da tekrar denemeye alır; Retry-After aşılmaz. Yeniden denemeyi başlatmak yeni fiyat alınmış demek değildir.

## Araştırma ve veri bütünlüğü

Native fiyatlar yaklaşık 5 saniyede `PHONE-LIVE.json` geçici tam dosyasının atomik rename'iyle saklanır; Android 7 dahil Python okuyucu yarım yazı okumaz. Bir quote'un kendi alındığı zaman saklanır, dosya yazıldığı zaman quote'a verilmez. Python son quote'u eski metadata ve native cache arasında zamanla seçer; hata/gelecek/başka kaynak veya yarım JSON önceki veriyi bozmaz. Son fiyat, modelin anchored alış/hedef/stop seviyelerini kaydırmaz. Güncel fiyat alışın altına inerse aynı seviyeler/id korunup plan stale olur.

Fiyat güncellemek Brier/forward örneği, model promotion, kapanmış OHLCV mumu veya gerçekleşmiş stop/target dolumu değildir. Sanal kayıtların gerçek trade-backfill doğrulaması korunur. Puan-only iş taze native quote varsa ikinci toplu ticker isteğini tekrar etmez; book/candle/Rally hesabı ve süre bütçesi korunur. Mevcut model arşivi/puan yaşını JS anlık fiyat katmanı değiştirmez.

Drive ZIP mevcut native snapshot'ını ve araştırma arşivini içerir; sonsuz ham frame/tick arşivi değildir. Bu çalışma ortamında REST ve stream HTTPS uçları HTTP 451 verdi. Fiziksel telefonda socket bağlantısı, Android arka plan süreleri, gerçek güncelleme gecikmesi ve Drive senkronizasyonu uçtan uca doğrulanmadı.

Kaynaklar: [OkHttp WebSocket](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket/), [Android Service/START_STICKY](https://developer.android.com/reference/android/app/Service#START_STICKY), [Android foreground service süre sınırı](https://developer.android.com/develop/background-work/services/fgs/timeout), [WorkManager periyodik işler](https://developer.android.com/develop/background-work/background-tasks/persistent-work/getting-started/define-work).
