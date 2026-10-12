# Analiz sunucusu durumu

Render hesabı bağlıdır. 3 Ekim 2026 tarihinde henüz hizmet oluşturulmamıştır; çalışan sunucu URL'si yoktur.

`render.yaml` ücretsiz prototip dağıtımı içindir. Kaynak deposu GitHub/GitLab/Bitbucket üzerinde yayımlanmadan Blueprint uygulanamaz. Görünen GitHub deposu farklı bir uygulamaya aittir; bu projeye kaynak deposu olarak atanmadı ve değiştirilmedi.

## Ücretsiz plan sınırı

Render Free, 15 dakika istek almadığında uyur. Yerel SQLite dosyası yeniden başlatma/uyuma/dağıtım sırasında kaybolabilir. Bu nedenle bu profil, merkezi kalıcı sinyal geçmişi şartını karşılamaz ve üretim kurulumu olarak sunulmamalıdır. Telefonun yerel sanal alış kayıtları sunucuya bağımlı değildir; uygulama silinmedikçe veya uygulama verisi temizlenmedikçe saklanır.

Ücretsiz sunucunun kullanılabilir hale gelmesi için kod deposuna ek olarak kalıcı veri deposu belirlenmelidir. Render Free Postgres 30 gün sonra sona erer; kalıcı çözüm sayılmaz. PostgreSQL uyarlaması bu sürümde yapılmadı. Kesintisizlik istenmese de merkezi geçmişin korunması ayrı bir gereksinimdir.

Kalıcı SQLite isteyen isteğe bağlı kurulum: ücretli 512 MB web hizmeti + 1 GB disk, `/var/data` mount, `RADAR_DB=/var/data/radar.sqlite3`. Resmi 3 Ekim 2026 fiyatlarıyla taban yaklaşık 7,25 USD/ay; vergi ve ek kullanım ayrıca. Ücretli kaynak oluşturulmadı.

## Yayınlamadan önce

1. Bu projeye ait Git deposunu bağla.
2. Kullanılacak kalıcı veri depolamasını belirle; ücretsiz profil için yukarıdaki kayıp sınırını gider.
3. API `PORT` ortam değişkenini ve `0.0.0.0` bind adresini destekler. `RADAR_API_TOKEN` doldurulunca health dışında API uçları Bearer anahtarı ister.
4. TLS Render'da sağlanır. Anahtarı APK'nin ayarlarındaki erişim anahtarı alanına gir; anahtarı kaynak koduna koyma.
5. Frankfurt hizmetinden Binance TR REST ve websocket erişimini doğrula. Test ortamındaki ABD proxy erişimi HTTP 451 verdi; bu bölgedeki erişim doğrulanmış değildir. Fiyat referansını başka borsaya değiştirme.
6. `/health`, `/pairs`, `/paper-positions` ve ilk gerçek işlem akışını doğrula. Boş/eskimiş veri canlı piyasa olarak etiketlenmemelidir.

Resmi kaynaklar: https://render.com/docs/free ve https://render.com/pricing.
