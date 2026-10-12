# İstatistiksel tasarım

## Tekrar sayısı

“10 tekrar” anlamlılık için yeterli ölçüt değildir. Araştırma hedefleri veriye bakmadan seçilir: nominal alpha=0.05, güç=0.80, yakalanmak istenen olasılık farkı δ=0.10, %95 güven aralığı yarı genişliği hedefi 0.10. Bunlar değiştirilebilir tasarım tercihleridir, evrensel eşikler değil. `STAT_POWER`, `MIN_DETECTABLE_LIFT`, `CI_HALF_WIDTH` araştırmadan önce seçilmelidir.

Eşit büyüklükte iki oran grubunda en yüksek Bernoulli varyansı p(1−p)=0.25 ile konservatif normal yaklaşımı:

`n/grup = ceil(2 × 0.25 × (z_(1−alpha/2) + z_power)^2 / δ^2)`

Tek karşılaştırmada bu tasarım **grup başına 393** örnek verir. Çoklu arama/tekrar değerlendirme düzeltmeleri gereken sayıyı artırır. Koşullu ve kontrol grubu için gereken n, ham tekrar, başarı ve etkin n birlikte saklanır. δ gözlenen farktan hesaplanmaz; yüksek gözlenen fark gereken n'yi yapay olarak düşürmez. Bu yaklaşık örneklem planlamasıdır, post-hoc observed power değil. Dengesiz gruplarda her iki grubun da alt sınırı geçmesi istenir.

[NIST oran testleri](https://www.itl.nist.gov/div898/handbook/prc/section2/prc24.htm) ve [statsmodels iki örneklem güç analizi](https://www.statsmodels.org/stable/generated/statsmodels.stats.power.NormalIndPower.solve_power.html) alpha, güç, etki ve n parametrelerini açıklar. 393 bu projenin konservatif formülünün sonucudur; tüm finansal sinyaller için önerilen sabit sayı değildir.

## Etiket ve zaman

Yalnızca kapalı, doğrulanmış, kesintisiz saatlik mumlar. t özellikleri t ve öncesinden; lag1/3/6 önceki belirtileri temsil eder. 1/6/24 saatlik gelecek kapanış getirisi `max(0.5%, geçmiş 24 saat oynaklığı × √h, 28 bps maliyet varsayımı)` eşiğini geçerse yükseliş, negatif eşik altına inerse düşüş; diğerleri nötr. Maliyet hesaptan ölçülmez.

Her ufukta UTC'ye sabitlenmiş, örtüşmeyen sonuç pencereleri vardır. Örtüşmemek bağımsızlık garantisi değildir. Pozitif otokorelasyondan `n_eff = n/(1+2Σrho)` ile etkin n azaltılır; ham n'yi aşmaz. Pozitif dizi yaklaşık 24 gecikmeyle sınırlıdır. Alt gruplarda da bağımlılık kontrolü yapılır. Bu yaklaşım finansal veride tam teorik garanti değildir.

## Araştırma

Zaman sıralı %60 eğitim / %20 seçim / %20 test. Etiket sonu bir sonraki grubun ilk özellik zamanına ulaşırsa satır dışarıda bırakılır. Standartlaştırma, en ilişkili dört özellik, altı ikili birleşim ve eşikler yalnızca eğitimde belirlenir. Ridge cezası seçim döneminde seçilir. Testte eşik/özellik yeniden seçilmez. [Zaman sıralı değerlendirme](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.TimeSeriesSplit.html) ilkesi kullanılır; sklearn çalışma bağımlılığı değildir.

Her özellik/birleşimde eğitimdeki %75 quantile üstü gözlemlerin gelecek yön oranı geri kalan kontrol grubuyla karşılaştırılır. [Fisher iki taraflı testi](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.fisher_exact.html) ile konservatif etkin n normal yaklaşımının daha büyük p değeri alınır. IID varsayımı piyasalarda doğrudan garanti değildir.

Özellik/yön/ufukların tamamı turdaki [Holm ailesine](https://www.statsmodels.org/stable/generated/statsmodels.stats.multitest.multipletests.html) girer. Pariteler arasında bütçe bölünür; toplam nominal alpha'nın yarısı keşif, yarısı model seçimine ayrılır. Paritenin r'inci tur keşif bütçesi `0.05/(2 × parite_sayısı × r × (r+1))`; sonsuz tur toplamı sonludur. Model seçimi de kendi yarı bütçesini ufuk/yönlere böler. Evren/tasarım değişirse araştırma protokolünü sürümleyin; bütçeyi sıfırlayarak aynı sonuçları sürekli tekrar test etmeyin.

`SUPPORTED`: Holm p eşik altında, her iki grupta planlanan etkin n var, aralık yeterince dar. `UNDERPOWERED`: n yetersiz. `NOT_SUPPORTED`: kanıt eşiği geçilmemiş. Etkin n ve normal yaklaşımı piyasa bağımlılığında kesin FWER garantisi oluşturmaz. Blok-bootstrap, rejim ayrımı ve bağımsız ileriye dönük teyit daha ileri doğrulama alanlarıdır. Bu yaklaşık araştırma kanıtı nedensellik/kâr garantisi değildir.

## Model ve belirsizlik

Lojistik model son veriye artan ağırlık verir (180 gün üstel zaman sabiti), üç ridge cezası dener. Başarısız aday kullanılmaz; başka aday veya önceki model seçilir ve hata açıkça raporlanır. Günlük güncelleme yeni veri gerektirir.

Aday, ayrı testte eski model/referansla eşleşmiş Brier farkının tek taraflı alt sınırını geçerse ve etkin test n hassasiyet eşiğine ulaşırsa devreye alınır. Alt sınır normal yaklaşımdır. Yön tahminleri toplamı 1'i aşarsa olasılık bölgesine projekte edilir; ileriye dönük skor yayınlanan değeri ölçer. Tarihsel değerlendirme ayrı ikili aday modellerin testidir.

Koşullu oran belirsizliği [Wilson aralığı](https://www.statsmodels.org/stable/generated/statsmodels.stats.proportion.proportion_confint.html) ile raporlanır. Tarihsel model ve ilk gerçek zaman tahminlerinin Brier/ECE'si ayrıdır. İlk yayınlar sonradan değiştirilemez; geçmişten sahte canlı tahmin üretilmez. Kapanıştan 10 dakikadan fazla geciken yayınlar forward örnek sayısına katılmaz. Kalibrasyon ölçümü, tahminlerin tam kalibre olduğu iddiası değildir.

Son 200 sonuçta referansa göre Brier kaybı yeterli etkin n ve 2.58 standart hata eşiğiyle arttığında dağılım değişimi uyarısı verilir; tahmin durdurulur, referans kullanılır, yeniden değerlendirme gerekir. Bu operasyonel ölçüttür; ayrı resmi anlamlılık testi değildir.

Yeni veri otomatik işlenir; model her gün daha iyi olmak zorunda değildir. Anlamlı sinyal bulunmaması geçerli sonuçtur. Hedef/stop temasları ve manuel sanal işlemler kapanış yön etiketlerine karıştırılmaz.

## Ayrı stop-loss ailesi

Backend 2.1 ile stop mesafelerinin eşleşmiş getirisi, rejimler, maliyet varsayımları, ayrı %1 nominal bütçe ve ileriye dönük denemeler eklendi. Ayrıntılar [STOP_LOSS.md](STOP_LOSS.md). Yukarıdaki %5 keşif/model bütçesi yön ailesine aittir; stop iddialarıyla birleşik %5 hata garantisi verilmez. Tarihsel destek, ileriye dönük betimleyici kayıt ve manuel stop sonuçları ayrı tutulur.

## Telefon sayısal motoru

Android paketinde NumPy 1.26.2 ile `portable-lbfgs-v1` kullanılır. Normal/Student-t/Fisher ve ridge çözüm referans SciPy hesaplarıyla test edilir; bilimsel örnek sayısı, purged bölme ve çoklu karşılaştırma bütçeleri korunur. Sayısal motor bilgisi telefon kayıtlarında saklanır. Android çalışma gecikmeleri ve şarj beklemesi yeni örnek yaratmaz; geç yayımlanan tahminler geçmişten başarı kazandırmaz. [Telefon mimarisi](PHONE_BACKEND.md).

## Rally Score ve tüm coin taraması

Lab 3.1 orijinal 0–100 Rally Score formülünü ayrıca korur. Puan, altı piyasa bileşeninin sabit ağırlıklı betimleyici endeksidir; p-değeri, model olasılığı veya kanıt sertifikası değildir. Puan bileşenleri ve hesap zamanı SQLite geçmişine girer. Model olasılıkları ve istatistiksel kanıt aynı puana dönüştürülmez. Tüm aktif TRY coinlerini keşfetmek yeni bağımsız örnek yaratmaz; coin başına mevcut araştırma bütçeleri korunur, tüm coinler için birleşik %5 hata garantisi iddia edilmez.

## Önceki alış/hedef/stop ön planı — Lab 3.2 (3.4 ile değiştirildi)

`causal-entry-atr-v1`, yön/stop araştırmasının kanıt statüsünden ayrı bir fiyat planıdır. UTC'ye sabitlenmiş ve tamamlanmış 6 saatlik blokta geri çekilme `max(0, 1 − sonraki 6 mumun en düşük low / başlangıç close)` olarak ölçülür. İlk %60 blok medyanı alış iskontosunu belirler; sonraki %40 bu sabit mesafeye temas oranını ölçer. Açık ve gelecekteki mumlar dışlanır. İskonto maliyet tabanının altında ve stop risk sınırının üzerinde olamaz. Kısa taramada az blok bulunduğu için sonuç genellikle `UNDERPOWERED` olur.

Temas oranı, Wilson aralığı, ham/etkin n ve hassasiyet için gereken n birlikte saklanır. %95 aralık yarı genişliği 0,10 tercihi konservatif Bernoulli varyansıyla `ceil(z² × 0,25 / 0,10²) = 97` etkin gözlem ister. Eşik geçilince dahi `BASELINE_ONLY` kullanılır; tekrar taramalardan anlamlılık/kâr iddiası çıkarılmaz. Temas, gerçek limit dolumu değildir; maliyetli giriş sonrası getiri bu plan için bağımsız doğrulanmadı.

Son 14 kapalı saatlik mumun true range ortalaması güncel kapanışa bölünerek ATR oranı bulunur. Stop varsayılan 1,5 ATR, en fazla %10 mesafedir; hedef 2 ATR, minimum hareket ve maliyet tabanı kullanır ve güncel referans fiyatının üzerine yerleştirilir. Araştırmanın yalnızca 48 saat `HISTORICAL_SUPPORT` mesafesi uyarlanır; yetersiz, desteklenmemiş veya forward uyarılı politikalar uygulanmaz. Mesafeyi uyarlamak, araştırmanın farklı giriş/çıkış tasarımındaki kanıtını bu plana aktarmak değildir. Stop/target fiyatları varsayımdır, gerçekleşmiş fiyat değildir.

Plan fiyat zamanı 15 dakikadan eskiyse, gelecekteyse veya son kapanmış saat yoksa `STALE` olur. Önceki planın fiyat/kanıt kaydı değişmez. Manuel sanal alış kaydı planı bağlam olarak korur; önerilerin güncellenmesi mevcut kaydın seviyelerini değiştirmez.

## Alış adayı araştırması — Lab 3.4

`closed-limit-entry-v2`, eski geri çekilme medyanı ve canlı fiyatla yeniden ölçeklemeyi kaldırır. Sekiz sabit politika, masraflı limit dolumu/çıkış simülasyonu, dolmayan kararlar, kronolojik aday seçimi ve ayrı test kullanır. Seviyeler kapalı mumda sabitlenir. Ayrı %1 nominal alış bütçesi ve tur/coin/adaya düzeltmeler yön/stop ailelerinin birleşik hata garantisi değildir; aralıklar yaklaşık yöntemlerdir. Ayrıntılar ve istatistik kaynakları [ENTRY_RESEARCH.md](ENTRY_RESEARCH.md).


## Olgun araştırma puanı — Lab 3.5

Temel Rally endeksi aynen korunur; olgun araştırma katkısı ayrı ve sınırlı bir endeks dönüşümüdür. Yön modeli için aktif sürüme ait ayrı test kabul belgesi, sürüme ayrılmış ileriye dönük Brier üstünlüğü ve kalibrasyon aranır. Yeni model ve eski model örnekleri birleştirilmez; baseline yayınları olgunluk örneğine girmez. Hipotezler 88 özellik üzerinden modele katkı sağlar, ham destekli hipotez sayısı ayrı bonus değildir.

Turun model bütçesi artık altı promotion ve altı aktif-model kabul kontrolü arasında bölünür: karşılaştırma başına `round_model_alpha / (4 * horizon_count)`. Korunan eski model ayrı testte baseline'a karşı yeniden kontrol edilir; kendisini yenemediği için sonsuza kadar katkı dışı kalmaz. Yetersiz veya başarısız yeni kabul kontrolü belgeyi temizler. İleriye dönük kapı, son 200 sürüm/ufuk/yön sonucundaki eşleşmiş Brier farkının yaklaşık Student-t alt sınırını `0.05/6` ile, etkin n ve ECE sınırını birlikte kullanır; bu operasyonel olgunluk filtresidir, sürekli izlemeye dair yeni resmi hata garantisi değildir.

Alış katkısı kendi düzeltilmiş tarihsel net/fark ve gerekli n kontrolünü; stop katkısı mevcut rejim/politika/tasarımda tarihsel ve ileriye dönük eşleşmiş kanıtı kullanır. Katkı ölçeği, puan sınırı ve örnek güven ağırlığı istatistiksel olarak öğrenilmiş olasılıklar değildir. Tam yöntem ve sınırlamalar [RESEARCH_SCORE.md](RESEARCH_SCORE.md).
