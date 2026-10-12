# Olgun araştırmanın puana etkisi — Lab 3.5

Telefon puan taraması mevcut trend/hacim/sıkışma/emir defteri/BTC formülünü değiştirmeden temel Rally puanını üretir. Ardından kayıtlı, yeterli kanıtı olan araştırmalarla sınırlı bir katkı hesaplar. Yeni toplam 0–100 arasında tam sayıdır. Temel puan ve gerçek katkı ayrı gösterilir. Orijinal GitHub deposu değiştirilmez.

Bu bir ürün endeksidir. Aşağıdaki ölçekler, başarı olasılığı/kârlılık tahmini veya araştırmadan öğrenilmiş optimum ağırlıklar değildir. Araştırmalar olgunlaşmadan puan değişmez; tarama sayısı bilimsel örnek yerine geçmez. Yön modeli 88 öncü özellikten beslenir; destekli hipotez sayısını ayrıca toplamak aynı bilgiyi tekrar ödüllendirmez.

| Aile | Azami etki | Olgunluk için gerekenler |
|---|---:|---|
| Yön modeli | ±14 | Aktif sürümün ayrı test kabulü; aynı sürüm/ufuk/yöne ait yeterli ileriye dönük etkin n, Brier üstünlüğü, kalibrasyon ve güncellik |
| Alış adayı | ±3 | Kendi tasarımında gereken n; ayrı testte net getiri ve hemen alışa fark için açık pozitif/negatif sınırlar; aynı güncel alış planı |
| Stop araştırması | 0…+3 | Mevcut rejim/politika/tasarımda tarihsel destek ve yeterli ileriye dönük eşleşmiş sonuç; boşluk/uyarı olmaması |

## Yön modeli

Promotion artık model tur bütçesinin yarısını, aktif-model kabul kontrolü diğer yarısını kullanır. Toplam altı promotion ve altı aktif-model kontrolü için her karşılaştırma `round_alpha/(4*horizon_count)` alır. Aktif modelin eğitim sonu yeni test başlangıcından önce olmalıdır. Frozen model, eğitim bölümünden çıkan baseline'a karşı ayrı testte tekrar değerlendirilir. Brier farkının pozitif alt sınırı ve hassasiyete göre gereken etkin n varsa, sürüm/tasarım/test başlangıcı/kalibrasyon içeren kabul belgesi saklanır. Eski sürümden gelen ve korunmuş model, yeni adayı kabul ettirmeden de kendi ayrı test kanıtını kazanabilir. Yeni kontrolde geçemezse belgesi temizlenir.

Puan ayrıca hem tarihsel hem ileriye dönük ECE'nin `ci_half_width` sınırında olmasını ister (varsayılan 0,10). Yalnızca yayımlanmış, sonucu kapanmış ve aynı `model_version`/ufuk/yöne sahip ileriye dönük tahminler kullanılır. Baseline tahminleri ve başka sürümler eklenmez. Son 200 sonuçtan etkin n, eşleşmiş Brier farkı ve `0.05/6` [Student-t](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.t.html) alt sınırı hesaplanır; alt sınır >0 gerekir. Etkin n en az [normal hassasiyet planı](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.norm.html) `precision_n(ci_half_width)` olmalıdır; varsayılan 97, sabit 10 değildir. Bunlar bağımlı piyasada yaklaşık, operasyonel olgunluk filtreleridir; sürekli gözlem için yeni resmi anlamlılık garantisi verilmez.

Güncel tahminin olasılığı ile baseline farkı, önceden belirlenen olasılık farkı hedefiyle ölçeklenir ve −1…+1'e sınırlandırılır. Yükseliş akışında pozitif, düşüş akışında negatif işaret kullanılır. Araştırmanın yükseliş ihtimalini baseline'dan düşük görmesi de negatif katkı yapabilir. Olgun akışlar eşit ortalamayla birleştirilir; ufuk sayısı puan bonusu olmaz.

Güven ağırlığı `n_eff/(n_eff+required)`'dır; yön için tarihsel/ileri n'nin küçüğü kullanılır. Eşiğe ilk ulaşınca ağırlık yaklaşık 0,5; veri biriktikçe 1'e yaklaşır. Bu bir endeks tasarımıdır, kalibre edilmiş güven olasılığı değildir.

## Alış ve stop

Alış raporu `closed-limit-entry-v2` ve mevcut tasarımla uyumlu, aynı coine ait ve güncel olmalıdır. Net/fark etkin n'nin küçüğü raporun güç/etki/varyans/çoklu karşılaştırmaya göre gereken n değerine ulaşmalıdır. `HISTORICAL_SUPPORT`, net alt sınırı >0 ve hemen alışa fark alt sınırı >önceden belirlenmiş minimum etki olduğunda olumlu katkı verir. `NOT_SUPPORTED` tek başına ceza değildir; yalnızca net üst sınırı <0 ve fark üst sınırı <−minimum etki birlikte varsa olumsuz katkı verir. Belirsiz/yetersiz sonuç nötrdür. Aynı kural/araştırma turuna ait güncel alış planı gerekir. Bu katkı **tarihsel sanal kanıttır**, ileriye dönük doğrulama diye sunulmaz. [Alış araştırması](ENTRY_RESEARCH.md).

Stop, hem seçilen mevcut öneriyi hem aynı rejim/politika/tasarım/source'taki tarihsel aday ve ileriye dönük shadow denemelerini kontrol eder. Tarihsel n kendi gereken sınırına, ileriye dönük n hassasiyet sınırına ulaşmalı; her iki eşleşmiş getiri farkının alt sınırı >0 olmalıdır. Veri boşluğu veya forward caution katkıyı durdurur. Stopun göreli zararı azaltması, coinin yükseleceği ya da mutlak getirisinin pozitif olacağı anlamına gelmez; etki +3 ile sınırlıdır. Birden fazla ufkun katkısı toplanmaz, ortalanır. [Stop yöntemi](STOP_LOSS.md).

## Güncellik, gerileme ve kayıt

- Temel piyasa taraması en fazla 15 dakika eski olabilir; gelecekteki zaman/hata katkıyı kapatır.
- Geçmiş veri tamam olmalı, araştırmanın son kapanmış saatlik mumu mevcut olmalıdır. Demo kanıtı katkı yaratmaz.
- Araştırma eğitimi en fazla iki yeniden eğitim aralığı eski olabilir (varsayılan 48 saat). Yön tahmini mevcut kapalı saatten üretilmiş, son bir saatte hazırlanmış ve henüz süresi bitmemiş olmalıdır.
- Son ileriye dönük model/stop sonucu `2*retrain_ms + horizon*HOUR` içinde olmalıdır. Model drift, optimizer hatası, kötü ECE/Brier veya eksik sürüm/tasarım kanıtı ilgili katkıyı kaldırır. Coin evreni değişince yön tasarım belgesi yeniden kontrol edilir.
- Her okuma son toplam puanın üzerine katkıyı tekrar eklemez; saklanan **base_score** üzerinden yeniden hesaplar. Araştırma bozulursa yeni exchange taraması beklemeden katkı kaldırılabilir. Piyasa tarama zamanı değişmez; yeni veri toplanmış gibi gösterilmez.
- Toplam katkı ±20'ye, son puan 0–100'e sınırlandırılır; tam sayıya yuvarlanır. Ekrandaki aile puanları yuvarlama/sınır öncesi katkılardır; gerçek toplam değişim ayrıca gösterilir.

Puan-only iş araştırmayı yeniden eğitmez; diskten gelen hafif rapor/belge/planlarla hesaplar. Tüm coin kalıcı sırası, görünür coin önceliği ve 30 saniyelik hedef tarama aralığı korunur. Snapshot ve `/market` aynı kuralları kullanır. Araştırma geçmişi/model kabul belgesi ve taramalardaki temel puan/katkı/kanıt durumu mevcut SQLite/state arşivinde ve Drive ZIP yedeğinde korunur. Eski sanal kayıtların ilk bağlamı yeniden yazılmaz.

Fiziksel cihaz ve canlı veri akışı uçtan uca test edilmedi. Gösterilen araştırma katkısı her zaman artmaz; zamanla geri çekilmesi veya uzun süre nötr kalması geçerli sonuçtur.
