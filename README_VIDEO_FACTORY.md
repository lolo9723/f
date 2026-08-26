# Video Fabrikası Android

Telefon, Kaggle GPU üzerinde AI video üretimini başlatan kumanda panelidir.

## Güvenlik
- Kaggle token Android Keystore AES/GCM ile şifrelenir.
- Token kaynak koda gömülmez.
- Sadece HTTPS kullanılır.

## Üretim motoru
Kaggle scripti Lightricks LTX-Video 2B distilled image-to-video motorunu kullanır.
Beş sahne üretip FFmpeg ile `FINAL.mp4` oluşturur.
AI motoru dış nedenle çökerse teknik fallback MP4 üretir ve `status.json` içinde `ai_ok=false` yazar; bu çıktı AI final olarak kabul edilmez.

## CI kabul kapısı
- JVM unit tests
- Android lint
- debug APK build
- Android 35 emulator + Espresso UI smoke tests
- APK artifact upload

Gerçek Kaggle GPU uçtan uca testi, kullanıcının kendi Kaggle API tokenı ile uygulamanın içinden yapılır.
