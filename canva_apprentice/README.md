# Canva Çırak Ajan — v0.1

Tek kullanıcı için güvenlik-öncelikli Android ajan prototipi. Ajan yalnızca **Canva (`com.canva.editor`)** ve **ChatGPT (`com.openai.chatgpt`)** erişilebilirlik olaylarını alır.

## V0.1 hedefleri

- Yeni tasarım oluşturma varsayılan olarak **kilitli**.
- Aktif görev ve tasarım ekranı parmak izi cihazda kalıcı saklanır.
- UI öğeleri erişilebilirlik ağacından okunur; rastgele koordinat tıklaması ana yöntem değildir.
- Şifre/CAPTCHA/doğrulama algılanınca ajan durur ve **DEVAM ET** erişilebilirlik katmanı gösterir.
- ChatGPT için sıkı `CAA1` öğretmen protokolü vardır; öğretmenin bir seferde yalnız bir eylem vermesi beklenir.
- Düşük güven veya yıkıcı işlem güvenlik kapısında engellenir.
- AccessibilityService ekran görüntüsü alıp uygulama önbelleğine yazabilir.

## Bilinçli sınır

Bu sürüm Canva'nın güncel Android erişilebilirlik ağacının gerçek cihaz üzerindeki yapısı görülmeden final değildir. UI değişirse ajan tahmin yürütmek yerine duracak şekilde tasarlanmıştır.
