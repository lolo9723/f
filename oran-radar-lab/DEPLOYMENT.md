# Sunucusuz bulut kurulumu

AWS SAM paketi hazırdır; henüz canlı AWS kaynakları oluşturulmadı. GitHub hedefi verilmediği için çalışma ayrı yerel Git deposunda ve teslim ZIP'inde bulunur. Orijinal depoya remote tanımlanmadı.

## AWS kurulumu

1. AWS CLI/SAM CLI kurun; kullanmak istediğiniz hesabın profilini yapılandırın. Binance TR bu geliştirme ortamında HTTP 451 döndürdü; seçilen AWS bölgesinin kaynak erişimi küçük bir istekle ayrıca doğrulanmalıdır. Kaynak erişimi sağlanmıyorsa veriyi kullanma hakkına sahip olunan bir adaptör gerekir.
2. Repo kökünde:

```bash
sam validate --lint --template-file infra/template.yaml --region eu-central-1
sam build --template-file infra/template.yaml --parallel
sam deploy --guided --stack-name oran-radar-lab --region eu-central-1 --profile radar-lab
```

SAM sorularında `ApiToken` için en az 32 karakterlik özel rastgele anahtar, `Pairs` için TRY pariteleri, `LookbackDays` için geçmiş uzunluğu girin. Anahtarı kaynak koda/Git'e eklemeyin. Token `NoEcho` kullanır. `MinimumDetectableLift=0.10` (10 yüzde puan) **tekrar sayısı değildir**; araştırmadan önce seçilen hedef farktır. Profil/bölge adı örnektir; gerçek hesabınızı seçin.

Stop araştırması için `StopMinimumGainBps=10` önceden seçilmiş %0,10 eşleşmiş net-getiri farkı hedefi, `StopSlippageBps=5` sanal çıkış kayması varsayımıdır. Tekrar sayısı bunlardan, eğitim varyansından, güçten ve çoklu test bütçesinden hesaplanır; 10 sabit tekrar kullanılmaz. Yöntem [docs/STOP_LOSS.md](docs/STOP_LOSS.md). Güncelleme sonrası `sam build` ile 2.1 kaynaklarını tekrar paketleyin; bu teslimatta yerel SAM paketi yeniden oluşturuldu.

3. Stack çıktısındaki `ApiUrl` adresini ve anahtarı mobil ⚙ ayarlarına kaydedin. HTTP API `$default` stage kullanır; adrese `/prod` eklenmez.
4. 730 günlük geçmiş, 1000 mumluk kaynak sayfalarıyla yaklaşık 4 zamanlayıcı çalıştırmasında tamamlanır. `backfill_complete=false` ara durumu normaldir. Modelin istatistiksel eşiği geçmesi zorunlu değildir.
5. Uygulamayı kapatın, iki periyot sonra açın; worker/mum zamanları ilerlemeli. Düğmeyle denetleyin. `GET /v2/audit` `ok=true` operasyonel kontrollerin geçtiğini ifade eder; modelin kârlı veya anlamlı olduğunu değil.

## Dayanıklılık

EventBridge 5 dakikada bir yapılandırılmış pariteleri kuyruklar. SQS tekrar teslim edebilir; job kimliği, parite lease'i ve koşullu yayın eski worker'ın yeni veriyi ezmesini engeller. Worker 15 dakika, lease 950 saniye ile sınırlıdır. SQS görünürlük süresi Lambda için en az 6× timeout kuralını karşılar. Hata sonrası yeni zamanlanmış işler 5 dakikada tekrar dener; tek başarısız mesajın normal yeniden teslimi daha uzun sürebilir.

Son iyi snapshot korunur. Dead-letter mesajları zamanlayıcı tarafından tekrar kuyruklanır; ancak başarılı enqueue sonrası DLQ mesajı silinir. Kaynak/kimlik/yetki arızalarını düğme düzeltemez: arızayı açık tutar, gereken işleri başlatır, tamamlanmadan yeşile dönmez. Devre dışı EventBridge kuralını düğme yeniden etkinleştirmez; eksik analizleri kuyruklar ve zamanlayıcı arızasını göstermeye devam eder.

DynamoDB/S3 kalıcıdır, stack silinirken korunur. S3 public erişim kapalı/TLS zorunlu, DynamoDB point-in-time recovery açıktır. Immutable payload + atomik pointer kullanılır. Arşiv nesneleri otomatik silinmez; depolama kullanımını izleyin. Push bu sürümde yapılandırılmadı.

## Maliyet ve kapasite

Lambda, SQS, API Gateway, S3 ve DynamoDB ücretleri geçerlidir; ücretsiz çalışma garantisi yoktur. Her 5 dakikada parite başına iş, günlük model değerlendirmesi ve polling API vardır. Eğitim gerekmeyen işlerde yalnızca son özellik vektörü hesaplanır. Worker eşzamanlılığı 3'tür; parite sayısı büyütülürse kuyruk yaşı, timeout ve borsa rate limitleri ölçülerek kapasite ayarlanmalıdır. Varsayılan liste tüm piyasa değildir.

CloudWatch runtime hata, zamanlayıcı yokluğu ve kuyruk yaşı alarmları vardır. SQS kısmi kayıt hataları log/queue ve audit içinde ayrıca izlenir; her kayıt hatası Lambda Errors metriği oluşturmaz. SNS/e-posta alarm alıcıları tanımlı değildir. Manuel takip mevcut tek kullanıcı ölçeğine göre tüm kayıtları okur; büyük kullanımda pair/state indeksli sorgu ve ayrı takip kuyruğu gerekir. Parite başına işlem geçmişi istekleri sınırlandırılır; geride kalan kayıtlar eksik olarak gösterilip sonraki taramada devam eder.

## Ayrı GitHub deposu

Bu teslimatta kaynak depoya push yapılmadı. Yeni **özel** depo oluşturduktan sonra yalnızca yeni hedefi ekleyin:

```bash
git remote add origin https://github.com/HESABIN/Oran-Radar-Lab.git
git push -u origin main
```

ZIP için yeni klasörde `git init -b main`, `git add .`, `git commit` ile başlanabilir. Git geçmişi ayrıca `Oran-Radar-Lab.bundle` ile teslim edilir. Kaynak depo özeldir; kopyasını açık depoda yayımlamak bu teslimatın kapsamına dahil değildir.
