# Kanıtlı Literatür Android

Telefon üzerinden açık erişimli DOI kaynaklarını tarayan, PDF metninden kanıt seçen, her cümleyi 2–3 kaynakla destekleyen ve kanıt PDF'lerini sarı vurgulu biçimde ZIP'e koyan Android uygulaması.

## Çalışma akışı
1. Konuyu ve tarih aralığını yaz.
2. OpenAlex üzerinden yalnızca DOI'li ve açık erişimli kayıtları getir.
3. PDF'leri indir ve cihaz üzerinde metin çıkar.
4. Kullanıcının kendi OpenAI uyumlu API anahtarıyla yapılandırılmış literatür üret.
5. Alıntıları PDF metninde birebir doğrula; doğrulanmayan cümleyi çıkar.
6. Orijinal PDF, sarı vurgulu PDF, rapor, kaynakça, kanıt eşleme ve kalite kontrol dosyalarını tek ZIP'e aktar.

API anahtarı APK içine gömülmez. Kullanıcı isterse cihazın şifreli yerel deposunda saklanır.
