# Kurulum

## Yerel doğrulama

```bash
python -m pip install -e '.[test]'
python -m pytest -q
cd cloudflare
npm ci --ignore-scripts --no-audit --no-fund
npm test
npx wrangler d1 migrations apply oran-radar --local
npx wrangler deploy --dry-run --outdir ../dist/worker
```

Yerel API testi için proje kökünden `python scripts/check_local.py` çalıştırın. Test önce yerel workerd + D1 başlatır, gerçek kapsam raporunu aktarır, yetkilendirme/ayar/anahtar iptali/cron işlemlerini denetler. Görsel test ayrıca Playwright ve Chromium ister: `npm install --no-save playwright` ve `npx playwright install chromium`. `scripts/check_panel.cjs` sekiz sayfa ve iki ekran genişliğini kontrol eder. Test anahtarları `.dev.vars` içinde tutulur; pakete/depoya alınmaz.

Chromium yoksa `python scripts/check_local.py --skip-visual` yalnızca API entegrasyonunu denetler ve görsel kontrolün yapılmadığını rapora açıkça yazar. Bu teslimde Chromium indirilemedi; görsel tarayıcı doğrulaması tamamlanmadı.

## Cloudflare hesabına kurulum — henüz yapılmadı

Bir Cloudflare hesabı ve Workers Free planı gerekir. Hesap mevcut değilse kullanıcı tarafından açılmalıdır. R2 kullanmadan API + D1 + statik panel kurulabilir. Bu adımlar abonelik oluşturmaz:

```bash
cd cloudflare
npx wrangler login
npx wrangler d1 create oran-radar
```

Dönen gerçek `database_id` değerini `wrangler.jsonc` içine koyun. `REPLACE_WITH_CREATED_D1_ID` bir yer tutucudur. Ardından:

```bash
npx wrangler d1 migrations apply oran-radar --remote
npx wrangler secret put ORAN_ADMIN_TOKEN
npx wrangler secret put ORAN_INGEST_TOKEN
npm run deploy
```

Her anahtar için ayrı, en az 32 karakterlik kriptografik rastgele değer üretin. Değerleri komut satırı argümanına/depoya koymayın. Yönetici anahtarıyla `/v1/admin/tokens` üzerinden kullanıcıya özel, 30 gün geçerli anahtar oluşturun. APK ve günlük panel kullanımı bu sınırlı anahtarla yapılır. CORS varsayılan olarak yalnızca kendi kökenine izin verir.

Aktarım yapan kalıcı Python çalıştırıcısına `ORAN_API_URL` ve `ORAN_INGEST_TOKEN` verin. `scripts/update_data.py` kapsam raporunu D1'e aktarır; büyük ham veri D1'e aktarılmaz.

## R2 isteğe bağlı

R2 bir abonelik/checkout ve kullanım bazlı faturalandırma ürünüdür. Önceden kullanıcı onayı alınmadan açılmamalıdır. Hesapta Standard sınıfında bir bucket mevcutsa şu değişkenler kullanılabilir:

```text
ORAN_R2_APPROVED=true
ORAN_R2_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
ORAN_R2_BUCKET=YOUR_EXISTING_BUCKET
AWS_ACCESS_KEY_ID=provided_as_secret
AWS_SECRET_ACCESS_KEY=provided_as_secret
```

Bu dosyada gerçek değer yazılmaz. R2 nesneleriyle metadata SQLite yedeği aynı bucket'ta tutulur. S3 uyumlu bağdaştırıcı bucket/abonelik oluşturmaz. Güncelleme başında kontrol veritabanı indirilir; sonunda consistent SQLite backup, ETag karşılaştırmasıyla atomik olarak değiştirilir. Çakışmada aktarım durur, veri nesneleri korunur. Aynı bucket'a yalnızca tek araştırma yazıcısı bağlayın.

## GitHub Actions

Kaynakların hedefi `lolo9723/f`, dalı `oran-radar-cloud-4.0`dır. Kök `.github/workflows/oran-cloud-4.yml` Android APK ve Cloud testlerini birlikte çalıştırır. Bu klasördeki bağımsız workflow örnekleri kopyalanırken çalışma dizinleri proje konumuna göre uyarlanmalıdır.

CI normal push/PR'da çalışır. Veri güncellemesi, `ORAN_ENABLE_SCHEDULE=true` repository variable olmadan çalışmaz. Gerekli bağlantılar kurulduktan sonra etkinleştirilir. Özel depoların hesap genelindeki dakika ve depolama bütçelerini kontrol edin; harcama limitini ayrıca GitHub'da sıfır olarak ayarlayın. Bu paket hesap bütçesini değiştirmez.

- R2 varsa standart Linux runner + mevcut onaylı R2 kullanılabilir.
- R2 yoksa `ORAN_RUNNER=self-hosted`, `ORAN_PERSISTENT_RUNNER=true`, kalıcı mutlak `ORAN_DATA` gerekir. Standart geçici runner'da kalıcı depo olmadan veri işi başlamaz.
- Actions artifacts/cache kalıcı veri bankası değildir. Varsayılan iş akışı bunlara piyasa verisi kaydetmez.
- Standart günlük plan 02.17 UTC / 05.17 Türkiye saatidir; gecikebilir. Bu plan dakikalık tarama veya kesintisiz eğitim garantisi vermez. Daha sık güncelleme için kalıcı çalışan bir makine gerekir.
