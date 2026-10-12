# Stop-loss araştırması — backend 2.1

Stop-loss araştırması ilk teslim edildiğinde APK üretilmemişti. Sonraki Lab 2.1 APK bu ekranları içerir ve kontrol düğmesini backend bağlantısı olmadan da gösterir. Backend'in veri toplaması ve manuel stop takibi frontend'in açık olmasına bağlı değildir. Bunun kalıcı olarak çalışması için bulut dağıtımı gerekir; bu teslimatta canlı AWS kurulumu yapılmadı.

## Ne ölçülüyor?

Her paritede 6, 24 ve 48 saat için 12 stop politikası vardır: %0,5 / 1 / 1,5 / 2 / 3 / 5 ve 0,75 / 1 / 1,5 / 2 / 3 / 4 × ATR. ATR, son 14 kapalı mumun gerçek aralıklarının basit ortalamasıdır; Wilder yumuşatması kullanılmaz. Gerçek aralık önceki kapanışa göre açılış boşluklarını da kapsar.

Mesafe, maliyet tamponu ile %10 arasında sınırlandırılır. Tampon `(fee_bps + 2 × slippage_bps) / 10000`'dir; sınırlandığında raporlanan mesafe gerçek kullanılan mesafedir. Tüm politikalar aynı sanal giriş, aynı hedef ve aynı süreyle karşılaştırılır. Hedef geçmiş ATR'nin 2 katıdır; minimum hareket/maliyet tamponu ve %50 üst sınır uygulanır. Hedef stop mesafesiyle değişmez. Böylece geniş stopun daha uzak hedefle karıştırılması önlenir. Stopsuz referans da aynı hedef/süreyi kullanır.

Oynaklık ve hacim dört rejim oluşturur. Oynaklık ölçüsü ATR/fiyat, hacim ölçüsü son üç mumun quote-volume ortalamasının önceki 24 mum ortalamasına oranıdır. Rejim eşikleri yalnızca eğitim döneminin medyanlarından gelir. Aday politika seçim dönemindeki konservatif ortalama net getiriyle seçilir; test döneminden aday seçilmez.

Kaydedilenler: stop/hedef temasları, temas sırası belirsiz örnekler, ham/etkin örnek sayısı, stop mesafesi, kazanç/risk, ücret/kayma sonrası net getiri sınırları, ufuk boyunca MAE/MFE, stop sonrası sonraki mumlarda hedefe dönüş ve toparlanma. Aynı mumda iki temasın sırası bilinmiyorsa `AMBIGUOUS` olur. Stop altında açılışta nominal stop fiyatından çıkılmış gibi yapılmaz; açılış fiyatı kullanılır. Bu açılış sıralamayı kanıtlıyorsa aynı mumdaki sonraki hedefe dönüş de sayılabilir.

Getiri sınırları, OHLC temas sırasına ve belirtilen fill/kayma varsayımına koşulludur; gerçek borsa dolumlarının veya en kötü piyasa kaymasının sınırları değildir. Varsayılan tarihsel/gölge maliyet 28 bps, varsayılan çıkış kayması 5 bps'dir. Gerçek emir yoktur. Bu çalışma bütün UTC saat ankrajlarında sanal long girişleri araştırır; belirli bir alış sinyalinin kârlılığını kanıtlamaz, short stratejisi değildir.

## Gereken tekrar sayısı

Eşleşmiş sonuç `stoplu konservatif net getiri − stopsuz referans net getiri` olarak hesaplanır. Eğitimden tahmin edilen fark standart sapması `s_train`, önceden seçilen minimum fark `δ`, güç ve karşılaştırma bütçesiyle yaklaşık plan:

`n = ceil((z_(1−alpha_c/2) + z_power)^2 × s_train^2 / δ^2)`

Sıfıra yakın eğitim varyansında `δ/2` tabanı kullanılır. Ayrıca oran hassasiyeti için `precision_n(CI_HALF_WIDTH)` alt sınırı vardır; varsayılan hedefte 97'dir. Bu 97 tüm stoplar için yeterli sayı değildir: gerçek gereken n genellikle çok daha yüksektir. Testteki yüksek gözlenen kazanç, gereken n'yi azaltmaz. `STOP_MIN_GAIN_BPS=10` **10 tekrar değildir**; veri görülmeden seçilen %0,10 net-getiri farkı hedefidir.

Örnekler %60 eğitim / %20 seçim / %20 test olarak zamanla ayrılır; etiket sonu sonraki grubun özellik zamanına ulaşan eğitim/seçim örnekleri dışarı alınır. UTC ankrajları aynı ufukta örtüşmez. Otokorelasyon ham n'yi azaltır. Eşleşmiş ortalama fark aralığı [Student-t nicelikleri](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.t.html) ve etkin n ile hesaplanır; örneklem planında [normal dağılım nicelikleri](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.norm.html) kullanılır. Oran belirsizliğinde [Wilson yöntemi](https://www.statsmodels.org/stable/generated/statsmodels.stats.proportion.proportion_confint.html) sürdürülür. Tahmini varyans/etkin n nedeniyle bunlar piyasa verisinde yaklaşık yöntemlerdir, kesin güç/hata garantisi değildir.

Stop ailesinin toplam nominal bütçesi ayrı %1'dir: pariteler arasında bölünür, r'inci turda `0.01 / (parite_sayısı × r × (r+1))` harcanır. Tur bütçesi tüm politika/ufuk/rejimlere Bonferroni ile bölünür; varsayılanda 144 karşılaştırma vardır. Mevcut yön araştırmasının %5 bütçesi ayrı kalır. Tüm stop ve yön iddialarının birleşimi için %5 hata garantisi iddia edilmez. Tasarım değişince tur sayısı sıfırlanmaz.

`HISTORICAL_SUPPORT`: etkin n planı geçmiş, çoklu/tekrar değerlendirmeye göre farkın alt sınırı pozitif ve ortalama fark önceden seçilen etki hedefine ulaşmış. `UNDERPOWERED`: örnek yetersiz. `NOT_SUPPORTED`: kanıt eşiği geçilmemiş. Stopsuz referanstan daha az zarar etmek hâlâ zarar etmek olabilir; ortalama net getiri ayrıca gösterilir. `FORWARD_CAUTION` ileriye dönük son tutulan sonuçlarda bozulma uyarısıdır; operasyonel uyarı, yeni bir resmi anlamlılık/kârlılık iddiası değildir. Hiçbir aday otomatik emir veya manuel stop değişikliği yapmaz.

## Sürekli kayıt

EventBridge'in mevcut 5 dakikalık iş akışı kapalı mumları alır. Yeni veri varsa araştırma günlük yeniden yapılır. İlk yayınlanan gölge planlar politika, mesafe, hedef, kaynak, maliyet, rejim, tur ve tasarım kimliğiyle sabitlenir. Kapanıştan 10 dakikadan geç yayın eski saatte yapılmış gibi kaydedilmez. Araştırma bitişindeki saat kullanılır.

Gölge giriş kapanıştan sabit bir saat sonraki saatlik mumun açılışına planlanır; gerçek sanal giriş o gelecekteki mum kapandığında açılışından okunur. Tarihsel değerlendirme de aynı bir saat tamponunu kullanır. Böylece yayın öncesindeki hareket ve zamanlayıcı gecikmesi sonuçlara karışmaz; aynı ufuk pencereleri örtüşmez. Bunlar kalıcı OHLCV gölge denemeleridir, gerçekleşmiş piyasa alımları değildir.

Eksik saat varsa sonuç `DATA_GAP` olur, deneme sonuçlanmaz; audit düğmesi eksik işi tekrar kuyruklar. İlk stop/çıkış sonucu sonraki toparlanmayla değiştirilmez. Sonuçlanan ham denemeler `max_forward` sınırında tutulur; bekleyenler atılmaz. Bir kez sayılan kümülatif sayaçlar atomik parite state'inde korunur ve ham kayıtlar budansa da silinmez. Kaynak/tasarım kimlikleri ayrı olduğundan demo/canlı veya farklı maliyet protokolleri karıştırılmaz. Kümülatif ham n bağımsız örnek sayısı değildir; tutulan son kohortların etkin n ve belirsizliği ayrıca hesaplanır.

## Manuel stop kayıtları

Sanal pozisyon kaydında stop mesafesi, kazanç/risk ve o anda backend'de bulunan kapalı-mum göstergeleri/ATR-hacim bağlamı saklanır. Kaynak, gözlem zamanı ve eskilik bilgisi korunur; ileri tarihli gösterge kayda girmez. Aynı UUID tekrarında ilk bağlam/ilk seviyeler korunur.

Gerçek public aggTrade ID'leri kesintisiz işlenir. Stop görülünce pozisyon `STOPPED` olarak kalır; çıkış, kapanış zamanı ve hesaplanan zarar sabittir. Backend ilk kayıt zamanından 48 saat dolana kadar hedefe dönüşü ayrı takip eder. Zaman sınırından sonraki hedef dönüş sayılmaz. Takip sonrası hedefe dönmesi pozisyonu `TARGET_HIT` yapmaz. ID boşluğunda dönüş iddiası bekletilir ve denetimde eksik görünür.

`stop_slippage_pct`, gözlenen ilk eşik aşımıdır; kullanıcının borsadaki stop emrinin dolum kayması ölçülmüş değildir. Manuel kayıtların %0,20 komisyon varsayımı önceki APK ile korunur; gölge modelinin %0,28 varsayımıyla aynı başarı havuzuna karıştırılmaz. Manuel tercihler rastgele atanmış deney değildir; sayı/toparlanma izleme içindir, nedensel kanıt değildir.

## API ve ayarlar

Snapshot her paritede `stop_loss` alanını içerir. Ayrıntılar:

| Uç | İçerik |
|---|---|
| `GET /v2/stop-loss?pair=BTCTRY` | Güncel adaylar, gereken/etkin n, referans fiyat/hedef, tarihsel ve ileriye dönük ölçüm |
| `GET /v2/stop-loss/research?pair=BTCTRY&offset=0` | Tüm test politikaları, 100 satırlık sayfalar |
| `GET /v2/stop-loss/forward?pair=BTCTRY&offset=0` | İlk yayınlanan planlar ve sonuçları, 100 satırlık sayfalar |

Bulutta Bearer token gerekir. Tüm fiyat önerileri raporun referans kapanış fiyatı/hedefi/süresi içindir; başka alış/hedefe aynı kanıtı doğrudan taşımayın. `STOP_MIN_GAIN_BPS` ve `STOP_SLIPPAGE_BPS` ortam değişkenleri; SAM'de `StopMinimumGainBps` ve `StopSlippageBps` parametreleri vardır. Grid/ufuk/maliyet tercihlerinin kalanları `Config` içindedir. Parametreler araştırmadan önce seçilmelidir.
