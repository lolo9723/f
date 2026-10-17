# Oran Radar Cloud 4.0

Binance spot piyasa verileri için denetlenebilir tarihsel veri bankası, küçük partilerle devam eden backfill motoru, araştırma araçları, Cloudflare API ve Türkçe yönetim paneli.

Bu veri altyapısı `lolo9723/f` deposundaki Android projesine entegre edilmiştir. Ana uygulama `../mobile/`, yayımlanan rapor `../cloudflare-coverage.json` içindedir. Bulut hesabına dağıtım ayrıca gerekir. Bu klasördeki tarihsel doğrulama raporları ilk veri toplama çalışmasının koşullarını kaydeder.

Gerçek ölçümler: [reports/coverage.json](reports/coverage.json). Yapılanlar ve kalanlar: [docs/STATUS.md](docs/STATUS.md). Ham veri örnek değil, resmî arşivden SHA256 ile doğrulanmış gerçek veridir. Testlerin sentetik verileri yalnızca `tests/` içindedir.

## Hızlı başlangıç

Python 3.11+ gerekir. Aşağıdaki komutları proje kökünde çalıştırın:

```bash
python -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[test]'
python -m pytest -q
python -m oran_cloud.cli discover
python -m oran_cloud.cli plan --exchange binance_global --symbols BTCUSDT,ETHUSDT,BNBUSDT --intervals 1d,1h --start 2026-09 --end 2026-09
python -m oran_cloud.cli run --max-files 12 --max-seconds 240
python -m oran_cloud.cli report --out reports/coverage.json
```

`data/` kalıcı diskte olmalıdır. ZIP içindeki `pilot-data/` klasörü teslim edilen gerçek veri bankasıdır. Devam etmek için tamamını `data/` adıyla kopyalayın veya `--data pilot-data` kullanın. Aynı veri klasöründe tek yazıcı çalışır. CLI Linux/macOS `fcntl` kilidi kullanır; Windows'ta WSL kullanın.

Motorun davranışı:

- Global Spot API ve tarihsel bucket envanterleri birleştirilir. Arşivde olup güncel API'de bulunmayan semboller korunur; bilinmeyen baz/kotasyon kimliği tahmin edilmez.
- TR TRY pariteleri kendi API'sinden, `type=1` ve `type=3` yönlendirmeleriyle alınır. TR fiyatı Global'den doldurulmaz.
- Aylık/günlük ZIP + resmî CHECKSUM doğrulanır. Veriler kayıpsız ondalık metin alanlarıyla Zstandard Parquet'e dönüşür.
- Manifest, kontrol noktası ve iş sonucu aynı SQLite transaction'ında yazılır. Parquet nesnesi önce atomik olarak kalıcılaştırılır. Kesilme sonrası aynı iş tekrar güvenle çalışır.
- Tam doğrulanmış bölümler tekrar indirilmez. Eksik dönemler, çelişen mükerrer kayıtlar, kapatılmamış/erken kapanmış mumlar ve hatalı dosyalar ayrı kaydedilir. Geçersiz satır içeren bölüm bütünüyle karantinaya ayrılır.
- 403/418/451 erişim engelleri aşılmaya çalışılmaz. 429/5xx kontrollü tekrar denenir; uzun beklemeler bir sonraki işe ertelenir.
- Günlük bölümler tamamlanmış aylık bölümle değiştirildiğinde eski nesneler korunur, mantıksal veri sayımından çıkarılır.

## Geniş kapsamlı backfill

```bash
python -m pip install -e '.[r2]'
python scripts/update_data.py
```

Bu komut önce bütün erişilebilir sembolleri keşfeder; her çalışmada sınırlı sayıda arşiv kataloğunu tarar, gerçekten listelenen aylık dosyaları kuyruğa ekler ve sınırlı sayıda dosyayı işler. Sırayla 1 günlük, ardından 1 saatlik kapsam genişler. `ORAN_CATALOG_BATCH`, `ORAN_BATCH_FILES`, `ORAN_DATA` ayarlanabilir. Bütün evren tek seferde bittiği varsayılmaz. TR'nin ilk işlem tarihi bilinmediğinde REST geçmişinde boş/erişilemeyen dönemler açık hata olarak kalır.

Varsayılan kalıcı depo yerel Parquet'tir. R2 ancak önceden onaylanan abonelik ve kimlik bilgileriyle, `ORAN_R2_APPROVED=true` verilirse kullanılır. Paket hesap açmaz ve ödeme/abonelik başlatmaz. Ayrıntılar: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Araştırma

```bash
python -m pip install -e '.[research]'
python -m oran_cloud.cli research --exchange binance_global --symbol BTCUSDT --out reports/research-btc.json
```

Bu komut yalnızca tarihsel **aday** model üretir. Kapanan mumlardan nedensel özellikler hesaplar; boşluklarda pencereyi sıfırlar. Limit gerçekleşmesi, maliyetler, belirsiz mum sırası ve tamamlanmayan olaylar ayrılır. Baz oran, lojistik regresyon ve gradient boosting, purging + embargo içeren kronolojik katlarda karşılaştırılır. Son %20 ayrı tutulur. Aynı holdout tekrar tekrar model seçimi için kullanılmamalıdır.

Global deney sonucu, Binance TR işlem başarısı anlamına gelmez. Otomatik üretim modeli seçilmez. Champion geçişi için değiştirilemez sinyal defterinde yeterli, pozitif, ileriye dönük TR sanal işlem kanıtı da gerekir. `research.py` örüntü benzerliği, Wilson güven aralığı, ölçütler ve geri alma işlevlerini içerir. Rejim değişimi izleme, çoklu deneme düzeltmesi ve zaman serisi modeli karşılaştırmaları henüz tamamlanmadı.

## API, web paneli ve Android

[docs/SETUP.md](docs/SETUP.md) Cloudflare kurulumu ve yerel doğrulamayı açıklar. [docs/API.md](docs/API.md) API sözleşmesidir. [docs/ANDROID.md](docs/ANDROID.md) Expo bağdaştırıcısını ve APK'da yapılması gerekenleri açıklar.

`cloudflare/public/` gerçek API'yi okur; sahte sinyal veya başarı oranı göstermez. Kapsam boşsa/aktarım eskiyse bunu belirtir. Çevrimiçi dosya yükleme ve bazı araştırma sayfaları henüz bağlı değildir; yerel CSV/XLSX/Parquet doğrulaması kullanılabilir.

Gerçek para emir gönderme kodu yoktur. Borsa özel anahtarı istenmez.
