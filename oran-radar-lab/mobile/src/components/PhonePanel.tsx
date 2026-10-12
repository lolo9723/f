import React, { useState } from 'react';
import { Text, TouchableOpacity, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import { clock, styles, TEAL } from '../../App';
import { backupPhone, selectDrive, startPhone, stopPhone, type PhoneStatus } from '../lib/phone';

export function PhonePanel({ status }: { status: PhoneStatus }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  if (!status.available) return null;
  async function action(task: () => Promise<unknown>, success: string) {
    setBusy(true); setMessage('');
    try { const result = await task(); setMessage(result === null ? 'Drive seçimi iptal edildi; mevcut hedef değişmedi.' : success); }
    catch (error) { setMessage(error instanceof Error ? error.message : String(error)); }
    finally { setBusy(false); }
  }
  return <View style={styles.card}>
    <Text style={styles.section}>TELEFON MOTORU · DRIVE YEDEĞİ</Text>
    <Text style={styles.muted}>{status.enabled
      ? status.foreground ? 'Hızlı takip etkin; uygulamayı ekrandan kapatabilirsin.' : status.periodicScheduled ? 'Periyodik arka plan çalışması etkin.' : 'Arka plan zamanlaması doğrulanmadı; kontrol düğmesine bas.'
      : 'Bulut sunucusu gerekmez. Araştırma ve stop takibi bu telefonda çalışır.'}</Text>
    <Text style={styles.small}>Son çalışma: {clock(status.lastCycle)} · {status.running ? 'İşleniyor' : status.charging ? 'Şarjda · eğitim yapılabilir' : 'Eğitim için şarj beklenir'}</Text>
    <Text style={styles.light}>Tüm coinler · Binance TR’de işlem gören bütün TRY pariteleri</Text>
    <Text style={styles.small}>Coin sayısı sınırlanmaz. Yeni listelenen coinler otomatik eklenir; tarama kaldığı sıradan devam eder.</Text>
    <Text style={styles.small}>{status.coverage?.total ?? 0} coin · puanlanan {status.coverage?.scored ?? 0} · güncel puan {status.coverage?.scores_current ?? 0} · araştırılan {status.coverage?.researched ?? 0}</Text>
    {!!status.coverage?.discovery_error && <Text style={styles.muted}>{status.coverage.discovery_error}</Text>}
    <TouchableOpacity accessibilityRole="button" disabled={busy} style={[styles.button, { opacity: busy ? .6 : 1 }]}
      onPress={() => action(async () => {
        await Notifications.requestPermissionsAsync();
        await startPhone();
      }, 'Tüm coinlerin telefon takibi etkin. Puanlar ve araştırma sırayla tamamlanır.')}>
      <Text style={styles.buttonText}>{busy ? 'İşlem sürüyor…' : status.enabled ? 'Tüm coinlerin hızlı takibini başlat' : 'Telefonda araştırmayı başlat'}</Text>
    </TouchableOpacity>
    {status.enabled && <TouchableOpacity disabled={busy} style={styles.buttonSecondary}
      onPress={() => action(stopPhone, 'Takip duraklatıldı; devam eden iş bitene kadar veri kaydı sürebilir.')}><Text style={styles.light}>Takibi duraklat</Text></TouchableOpacity>}
    <Text style={[styles.muted, { marginTop: 14 }]}>{status.driveSelected ? 'Seçilen Drive yedeği: ' + status.driveName : 'Drive yedekleme henüz seçilmedi.'}</Text>
    <TouchableOpacity accessibilityRole="button" disabled={busy} style={styles.buttonSecondary}
      onPress={() => action(selectDrive, 'Seçim tamamlandı. Seçilen dosya varsa düzenli yedekleme kullanılır.')}>
      <Text style={styles.light}>{status.driveSelected ? 'Drive hesabı / yedek konumunu değiştir' : 'Drive hesabı ve yedek konumunu seç'}</Text>
    </TouchableOpacity>
    <Text style={styles.small}>Dosya seçicisinin sol menüsünde Drive’ı aç, hesabını ve klasörünü seç, ZIP dosyasını oluştur. Drive uygulaması kurulu ve hesabın açık olmalı.</Text>
    {status.driveSelected && <>
      <Text style={styles.small}>Drive sağlayıcısına son teslim: {clock(status.backupHandoff)} · {(status.backupBytes / 1048576).toFixed(1)} MB</Text>
      <TouchableOpacity disabled={busy} style={styles.buttonSecondary}
        onPress={() => action(backupPhone, 'Yedek Drive sağlayıcısına teslim edildi. Buluta senkronizasyon durumunu Drive uygulamasından kontrol et.')}>
        <Text style={{ color: TEAL }}>Şimdi Drive’a yedekle</Text>
      </TouchableOpacity>
    </>}
    {!!status.engineError && <Text style={styles.muted}>{status.engineError}</Text>}
    {!!status.backupError && <Text style={styles.muted}>{status.backupError}</Text>}
    {!!status.serviceNote && <Text style={styles.muted}>{status.serviceNote}</Text>}
    {!!message && <Text accessibilityLiveRegion="polite" style={styles.muted}>{message}</Text>}
    <Text style={[styles.small, { marginTop: 12 }]}>Hızlı mod 5 dakika, periyodik mod en az 15 dakika aralık hedefler; Android geciktirebilir. Android 15+ hızlı servisi günde 6 saatle sınırlar. Telefon kapanınca veya Zorla durdur ile işler durur. Yedekler ağ uygunken en az 15 dakika arayla yenilenir; Drive sağlayıcısına teslim, bulutta senkronizasyon onayı değildir.</Text>
  </View>;
}
