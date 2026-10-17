# İşletim ve veri sözleşmesi

## Veri kimliği

Ham bölüm: `exchange/spot/symbol/interval/year/month/period-sourceHash-parquetHash.parquet`. Alanlar `open_ms`, `open`, `high`, `low`, `close`, `volume`, `close_ms`, `quote_volume`, `trades`, `taker_base`, `taker_quote`. Fiyat/hacim ondalık metin olarak saklanır; araştırmada sayıya çevrilir. Zaman UTC milisaniyedir. Arşivin 2025 sonrası mikrosaniye değerleri açık büyüklük kontrolüyle dönüştürülür; bilinmeyen birim reddedilir.

Benzersiz mum anahtarı borsa + spot + parite + aralık + açılış zamanıdır. Coin adı parite kimliğinin yerine geçmez. Aynı ticker'ın iki borsada aynı varlık olduğu varsayılmaz. `identity_status=exchange_ticker_only` bu sınırlamayı korur.

Kaynak URL, kaynak SHA256, Parquet SHA256, doğrulama zamanı, satır sayısı, fiziksel boyut ve şema sürümü manifestte tutulur. Yeniden hesaplama, manifest hash'ine bağlanır. `ingestion_checkpoints` en son doğrulanan üst sınırdır; eksik eski işleri atlama gerekçesi değildir. Otorite bölüm bazındaki iş kuyruğudur.

## Hatalar ve yeniden başlatma

Yazıcı kilidi kalıcı veri klasöründedir. Kesilen `running` işler bir sonraki çalıştırmada `pending` olur. Nesne yazılıp transaction tamamlanmadan kesilirse aynı içerik adresli nesne yeniden kullanılır. Bozuk bölümde checkpoint ilerlemez. Karantina dosyası, satır ve hata nedenini korur.

Başlangıç/bitiş içindeki veri boşlukları doldurulmaz; eksik mum üretilmez. Bazı eski resmî arşiv dosyalarında saat sonundan erken biten kısmi mumlar bulunabilir. Bu sürüm bunları standart tam mum olarak kabul etmez; ilgili bölümü karantinaya alır. Bu nedenle dosyanın varlığı doğrulama başarısı anlamına gelmez.

## Güncelleme ve zamanlama

Workers cron 5 dakikada bir idempotent iş kaydı bırakır. Bu kayıt taramanın veya eğitimin bittiği anlamına gelmez. Gerçek veri/araştırma işini Python çalıştırıcısı yapar. Varsayılan Actions akışı günde bir sınırlı parti çalıştırır; dakikalık tarama garantisi yoktur. Backend cihazdan bağımsızdır fakat henüz bir hesaba yayımlanmadı.

Her çalışmada son üç tamamlanan gün planlanır; daha eski eksik işler kalıcı kuyruğundan sürdürülür. Üç günden uzun ilk kesintide, planlanmamış günleri ayrıca `recent --days N` ile ekleyin. Yeni ay arşivi tamamlanınca önceki günlük nesneler silinmeden superseded olarak arşivlenir. Cron defterinin otomatik tüketimi ve 5 dakikalık canlı REST döngüsü henüz uçtan uca bağlanmadı; bekleyen işler tamamlandı diye işaretlenmez.

## Kalıcılık ve kurtarma

Yerel mod yalnızca diskin kalıcılığı kadar süreklidir. Scratch veya geçici Actions runner'ı kalıcı veri bankası değildir. Teslimdeki `pilot-data` dışa aktarılabilir gerçek bir kontrol noktasıdır. Tam klasör yedeği alın; açık SQLite veritabanını kopyalamak yerine SQLite backup API kullanın. R2 modunda `control/metadata.sqlite` consistent backup olarak saklanır. ETag çakışması veri üstüne yazmayı engeller; eski manifest kaybına karşı tek yazıcı kuralı ayrıca zorunludur.

Varsayılan politika hiçbir nesneyi silmez. Aylık dış yedek, hash doğrulaması ve geri yükleme provası önerilir. Nesne silme/kompaktlaştırma uygulanmadan önce yeni kapsam raporu ve kullanıcı onaylı saklama politikası gerekir. Yedekler de kota tüketir.

## Ücretsiz kullanım sınırları

Yerel/R2 nesne deposu 8 GB proje sınırında yeni yazmayı durdurur; R2 işlem sayıları proje defterinde ücretsiz kotanın %80'inde durur. Bu sayaç başka uygulamaların aynı hesaptaki kullanımını bilmez. D1/Workers hesap metrikleri ve ücret engelleyici sağlayıcı ayarları henüz bağlanmadı. Ölçülmemiş kullanım sıfır olarak gösterilmez. 0 TL hedefi, sınırsız kapasite veya sağlayıcı tarafında kesin fatura sınırı değildir.

## Veri içe aktarma

```bash
python -m pip install -e '.[excel]'
python -m oran_cloud.cli import dataset.csv --metadata metadata.json --mapping mapping.json
```

`metadata.json`: `source`, `methodology`, `created_at` (UTC ms), `as_of` (UTC ms), `version`. `mapping.json` hedef alan → kaynak sütun haritasıdır; `as_of` ve `event_time` zorunludur. Her satırda `as_of <= event_time` ve `as_of <= metadata.as_of` denetlenir. CSV, XLSX ve Parquet desteklenir; en fazla 25 MB. İlk çalışma sayfası okunur. Kaynak güvenilirliği otomatik onaylanmaz; sonuç `structurally_valid_pending_source_review`, `training_eligible=false` kalır.

## Araştırma sınırları

OHLC veri ile gerçek emir sırası, spread veya gerçekleşme kuyruğu bilinmez. Limitin yalnızca dokunulması fill sayılmaz. Aynı mumda giriş/hedef/stop sırası belirsizse kâr yazılmaz. Henüz sonuçlanmayan olay `pending`, eksik veri `unverifiable` olur. Net sonuç tahmini komisyon/kayma içerir. Hedefe ulaşma zamanı mum sınırlarıyla raporlanır; saniyesi biliniyormuş gibi gösterilmez.

MFE/MAE, giriş mumundaki bilinmeyen olay sırasından etkilenebilir; kesin işlem içi ölçüm değildir. Maksimum düşüş işlevi sıralı, çakışmayan işlem varsayımıyla etiketlenmiştir; saatlik örtüşen sinyallere portföy sonucu olarak uygulanmamalıdır. Tarihsel emir defteri ve on-chain metrik uydurulmaz.
