# API v1

API ve mobil istemci aynı JSON sözleşmesini kullanır. Kamuya açık tek uç nokta sağlık kontrolüdür. Diğerlerinde `Authorization: Bearer TOKEN` gerekir. User anahtarı admin/ingest anahtarından farklıdır. Süresi dolmuş/iptal edilmiş anahtar 401; yetersiz rol 403 döndürür.

| Yöntem | Yol | Rol | Sonuç |
|---|---|---|---|
| GET | `/v1/health` | Herkes | Sürüm ve gerçek işlem kapalı bilgisi |
| GET | `/v1/status`, `/v1/coverage` | User/admin/ingest | Kaynağı ve aktarım zamanı olan kapsam raporu |
| GET | `/v1/signals` | User/admin/ingest | Sinyaller; veri yoksa boş dizi |
| GET | `/v1/models` | User/admin/ingest | Modeller; yoksa `not_trained` |
| GET | `/v1/patterns`, `/v1/performance` | User/admin/ingest | Bağlı rapor; yoksa `not_evaluated` |
| GET, PUT | `/v1/settings` | User/admin | Kullanıcıya özel izleme listesi, 1–48 saat ufuk, bildirim ayarı |
| POST | `/v1/devices` | User/admin | Kullanıcıya bağlı native FCM cihaz anahtarı kaydı |
| POST | `/v1/admin/tokens` | Admin | `{user_id}` için 30 günlük sınırlı anahtar; yalnızca bir kez döner |
| POST | `/v1/admin/revoke` | Admin | `{user_id}` için tüm erişim anahtarlarını iptal eder |
| PUT | `/v1/ingest/snapshot` | Ingest/admin | En fazla 512 KB doğrulanmış kapsam raporu |
| GET | `/v1/jobs` | Ingest/admin | En eski 100 bekleyen iş |
| POST | `/v1/jobs/complete` | Ingest/admin | `{ids:[]}` tamamlanmış işleri işaretler |
| POST | `/v1/ingest/notify` | Ingest/admin | `{signal_id,device_id}`; geçerli TR sanal sinyali ve FCM bağlantısı gerekir |

Rapor alanları: `schema_version=coverage-v1`, `generated_at_ms`, `inventory`, `tasks`, `totals`, `intervals`, `gaps`, `signals`, `models`. Mum zamanları UTC milisaniyedir. `first_utc`/`last_utc` kapsamın uçlarını gösterir; aranın kesintisiz olduğu iddiası değildir. `all_history_complete=false` bütün evrenin eksiksiz alınmadığını gösterir. Eski snapshot yeni raporun üzerine yazılmaz.

Sinyal alanları: `signal_id`, `symbol_id`, `predicted_at`, `feature_as_of`, `model_id`, `entry`, `target`, `stop`, `expires_at`, `score`, `probability`, `expected_return`, `mode`. Olasılık hesaplanmadıysa null kalır; puan başarı oranı değildir. Global araştırma modeli TR üretim sinyali olarak kendiliğinden yayımlanmaz.

Hatalar `{error,request_id}` döndürür; gizli anahtar veya sunucu traceback'i dönmez. Panel, hata ve eski aktarım zamanını kullanıcıya gösterir. D1 snapshot aktarımı ham veri/model dosyalarının çevrimiçi olduğu anlamına gelmez.

Yerel model registry/sinyal defteri otoritedir; Cloudflare'e şu an özet snapshot yayımlanır. Panelden model terfisi, geri alma, dosya içe aktarma ve ayrıntılı örüntü sorgusu henüz API'ye bağlanmadı.
