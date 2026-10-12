# Alış seviyesi araştırması — Lab 3.4

Amaç her coin için, tanımlanmış adaylar arasında geçmiş doğrulama dönemindeki masraf sonrası **karar başına** getirinin alt sınırı en iyi olan kuralı seçmektir. Bütün mümkün fiyatlar arasında gelecekteki optimum bulunduğu iddia edilmez. Yükseliş/yön olasılığı, Rally Score ve stop araştırması ayrı kalır.

## Adaylar ve fiyatın sabitlenmesi

Sekiz önceden belirlenmiş kural: %0,5 / %1 / %2 / %3 geri çekilme; 0,5 / 1 ATR geri çekilme; son 24 saat en düşük low; son 24 saat quote hacmiyle ağırlıklandırılmış `(high + low + close) / 3`. Sonuncusu işlem seviyesinde gerçek VWAP değildir. Tüm girişler son kapalı mum close'una göre maliyet tabanı ve %10 azami iskonto sınırı içinde kalır. Yeni/aşırı kısa veride seçim araştırması beklenirken 1 ATR **ön adayı** gösterilir.

Güncel fiyat ayrı tutulur. Kotasyon yenilenince alış/hedef/stop ve plan kimliği değişmez. Yeni kapalı mum veya araştırma kuralı geldiğinde yeni analiz seviyesi oluşur. Son kapalı saat eksikse, fiyat zamanı 15 dakikadan eski/gelecekteyse veya kotasyon alışa/altına indiyse plan `STALE` olur ve sanal forma öneri olarak aktarılmaz. Manuel kaydın ilk bağlamı ve fiyatları korunur. `Aldım gibi kaydet` hâlâ şimdi alınmış varsayan manuel sanal kayıttır; bekleyen limit emri değildir.

## Kronolojik değerlendirme ve yürütme varsayımları

48 saat ısınmadan sonra UTC 55 saatlik ankrajlarla, 6 saat dolum bekleme ve dolumdan itibaren 48 saat çıkış takibi yapılır. 54 saatlik sonuç penceresi tamamen kapanmadan örnek yoktur. Isınma/sonuç penceresinde mum boşluğu varsa örnek atlanır. Bir kararın sonucu bir sonraki kararı kapsamaz. %60 eğitim, %20 aday seçimi, %20 ayrı test vardır; önceki bölümün sonucu sonraki özellik zamanını aşamaz. Politikalar sabittir; eğitim bölümü karşılaştırma kaydıdır, ayarlanmış parametre uydurulmaz. Aday seçimi yalnızca doğrulama bölümünün net alt sınırını kullanır; iki örnekten azsa ortalama betimleyici seçimdir. Teste bakarak kazanan seçilmez.

Limit, sonraki altı mumdan birinin low'una temas ederse sanal olarak tamamen dolmuş varsayılır; açılış limitten düşükse açılış fiyatı kullanılır. Queue/partial fill veya gerçek borsa dolumu doğrulanmaz. Limit sonrası aynı mum high'ının dolumdan önce mi sonra mı olduğu bilinmiyorsa iki sonuç yolu hesaplanır; kötümser yol yalnızca dolumdan sonraki close ile kanıtlanan yükselişi kullanır. Stop/target birlikte temasında stop alt, hedef üst getiri sınırıdır. Stop boşluğu açılış fiyatıyla gerçekleşir; hedefe olumlu gap iyileşmesi uydurulmaz. Dolmayan limitler ortalamada **sıfır** katkıdır; seçilip çıkarılmaz. Dolanların koşullu ortalamasıyla sahte avantaj üretilmez.

Tüm adaylar aynı geçmiş bağlamdaki 1,5 ATR stop (%10 tavan), 2 ATR hedef ve maliyet/minimum hareket tabanını kullanır. Hemen alış referansı sonraki açılışı kullanır. Varsayılan toplam komisyon 28 baz puan ve 5 baz puan çıkış slippage; hemen alışa ayrıca 5 baz puan giriş slippage vardır. Geçmiş spread/derinlik bilinmediği için gerçekte dolum, masraf ve sonuç değişebilir. Canlı spread risk tabanını değiştirirse tarihsel stratejiyle birebir eşitlik iddia edilmez. Ayrı stop araştırması önerileri görünür kalır; farklı giriş/stop tasarımına tarihsel destek aktarılmaz.

## Gereken tekrar ve kanıt durumu

Bağımsız alış ailesinin nominal alfa bütçesi %1'dir; tur `r` için `alpha / (r*(r+1))`, mevcut configured coin sayısı, sekiz aday ve net/iyileşme olmak üzere iki karşılaştırma için bölünür. Coin evreni değişirse tasarım kimliği değişir ve şarjda yeni araştırma gerekir. Piyasa bağımlılığı, değişen coin evreni ve tekrar kullanılan tarih nedeniyle bütün aileler/coinler için kesin birleşik hata garantisi verilmez.

Tekrar gereksinimi sabit 10 değildir. Varsayılan önceden belirlenmiş anlamlı fark 10 baz puan, güç %80 ve oran aralığı yarı genişliği 0,10'dur. Hesap:

`n_power = ceil((z_(1-alpha/2) + z_power)^2 * max(sd, delta/2)^2 / delta^2)`

`n_precision = ceil(z_(1-alpha/2)^2 * 0.25 / half_width^2)`

`required = max(n_power, n_precision)`

Varyans seçilen adayın ayrı testteki eşleşmiş kötümser getiri farkından tahmin edilir. Pozitif otokorelasyon etkin n'yi azaltır. [Student-t nicelikleri](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.t.html) ve etkin n, net getiri ve hemen alışa farkın aralıklarında; [normal nicelikler](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.norm.html) güç/hassasiyet planında; [Wilson yöntemi](https://www.statsmodels.org/stable/generated/statsmodels.stats.proportion.proportion_confint.html) etkin dolum örneklerinde kullanılır. Bunlar bağımlı piyasa verisinde yaklaşık aralıklar ve örneklem planlarıdır; kesin güç/yanılma garantisi değildir.

`HISTORICAL_SUPPORT`: yeterli etkin net/fark n, net alt sınırı >0 ve kötümser eşleşmiş fark alt sınırı >önceden belirlenmiş etki gerekir. Yeterli n ama destek yoksa `NOT_SUPPORTED`; veri azsa `UNDERPOWERED`. Yetersiz veya desteklenmeyen aday ön aday olarak etiketlenir. Gelecekte optimum veya kârlılık doğrulaması yapılmış sayılmaz. Yeni günlük geçmiş test, bağımsız ileriye dönük sonuç gibi gösterilmez.

## Sürekli güncelleme, kayıt ve kontrol

Araştırma yeni kapalı mum ve en az 24 saat geçtiğinde, telefon şarjdayken yeniden yapılır; aynı mumla taramalar örnek/tur üretmez. Tasarım/maliyet değişirse yeni tur açılır. Kısa puan işi tarihsel araştırma çalıştırmaz; kalıcı raporun kuralını güncel kapalı mum bağlamına uygular. Ekranda seçilen kural, dolum/net/fark, ham/etkin/gereken n, eğitim/doğrulama/test sayıları ve adayların sonuçları görünür. Şarj yokken veri toplama ve mevcut raporla plan hazırlama sürer.

Her araştırma turu state/public ve telefon SQLite `entry_research` günlüğüne girer; Drive ZIP yedeği araştırma verisini ve raporları kapsar. Kontrol düğmesi `ENTRY_RESEARCH_MISSING`, `ENTRY_RESEARCH_DUE` veya şarj bekleyen aşamayı gösterir ve eksik işi kurtarma kuyruğuna alır. Kuyruk kabulü araştırmanın tamamlandığı anlamına gelmez. Orijinal GitHub deposu değiştirilmez; canlı bulut dağıtımı yapılmadı.
