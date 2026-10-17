# Ücretsiz kota ve kaynak raporu

Kontrol tarihi: 17.10.2026. Resmî kaynaklara göre:

| Hizmet | Ücretsiz dahil kullanım | Bu projedeki karar |
|---|---|---|
| Workers Free | 100.000 istek/gün; HTTP çağrısı başına 10 ms CPU | API ve küçük zamanlama işleri; ağır Python çalıştırılmaz |
| D1 Free | 5 milyon okunan satır/gün; 100.000 yazılan satır/gün; toplam 5 GB | Ham mum yerine metaveri ve küçük özetler |
| R2 Standard | 10 GB-ay; 1 milyon Class A ve 10 milyon Class B/ay | Onaya kadar kapalı; proje için %80 sınırı |
| GitHub Free özel depo | 2.000 dakika/ay; 500 MB artifact; ayrı 10 GB cache | Günde en çok 18 dakikalık iş yaklaşık 558 dakika/31 gün; CI ve hesap genelindeki işler ayrıca eklenir |
| Firebase FCM | Ücretsiz mesajlaşma | Proje/Android hesabı ve gerçek cihaz kurulumu bekliyor |

R2, ücretsiz kullanım içerse de abonelik checkout'u ve kullanım bazlı faturalandırma gerektirir. Güncel resmî başlangıç sayfası bütün hesaplar için ödeme aracı koşulunu kesinleştirmiyor; checkout koşulları hesap üzerinde kontrol edilmelidir. Bu çalışma abonelik veya ödeme aracı eklemedi.

Yerel ölçülmüş veri boyutu `reports/coverage.json` içindedir. Bütün evrenin toplam GB değeri ölçülmeden kesin tahmin verilmez. Saatlik kapsam önce, 15/5/1 dakikalık genişleme kapasite kontrolünden sonra gelmelidir. R2 devre dışıyken çevrimiçi tarihsel dosya erişimi sağlanmış sayılmaz.

Üstteki 558 dakika yalnızca günlük işin teorik azami çalışma süresidir; sağlayıcının faturalama yuvarlamaları, diğer depolar ve CI toplamı ayrıca değerlendirilmelidir. Zaman aşımı ve uygulama sayaçları sağlayıcı hesabının kesin fatura tavanı değildir. R2'de başka uygulamalar da varsa hesap genelindeki tüketim ölçülmeden güvenli kalan kota bilinemez. Otomatik plan geçişi veya ödeme yetkisi yoktur.

Kaynaklar:

- https://developers.cloudflare.com/workers/platform/pricing/
- https://developers.cloudflare.com/d1/platform/pricing/
- https://developers.cloudflare.com/r2/pricing/
- https://developers.cloudflare.com/r2/get-started/
- https://docs.github.com/en/billing/concepts/product-billing/github-actions
- https://firebase.google.com/pricing

Veri kaynakları:

- https://github.com/binance/binance-public-data
- https://github.com/binance/binance-spot-api-docs
- https://data-api.binance.vision/api/v3/exchangeInfo
- https://data.binance.vision/
- https://www.binance.tr/apidocs/

Binance'in resmî açıklaması Spot arşiv zamanlarını 01.01.2025'ten itibaren mikrosaniye olarak tanımlar. TR belgelerinde parite envanteri `/open/v1/common/symbols`, mumlar `type=1` için `api.binance.me/api/v1/klines`, `type=3` için `cloudme-tr.2meta.app/api/v1/klines` olarak belirtilir. TR canlı erişimi bu ortamda HTTP 451 döndüğü için adaptör yalnızca belge + test fixture'ıyla doğrulandı; gerçek TR verisi toplanmış sayılmaz.
