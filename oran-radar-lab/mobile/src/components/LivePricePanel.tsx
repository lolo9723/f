import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { styles, RED } from '../../App';
import { priceAge, type LivePrices } from '../lib/livePrices';

export function LivePricePanel({ feed, enabled, foreground }: { feed: LivePrices; enabled: boolean; foreground: boolean }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 2000); return () => clearInterval(timer); }, []);
  return <View style={[styles.card, { padding: 14 }]}>
    <Text style={styles.section}>CANLI FİYAT TAKİBİ</Text>
    <Text style={styles.light}>{!enabled ? 'Telefon motorunu başlat.' : feed.state === 'LIVE' ? 'Binance TR canlı fiyat akışı bağlı.' : feed.state === 'REST' ? 'REST yedeği fiyatları güncelliyor.' : feed.state === 'PAUSED' ? 'Canlı akış duraklatılmış.' : 'Bağlantı bekleniyor; son fiyatlar korunuyor.'}</Text>
    <Text style={styles.small}>{Object.keys(feed.tickers).length} TRY fiyatı · {priceAge(feed.last_received_at, now)}</Text>
    <Text style={styles.small}>Fiyatlar puan ve araştırmadan bağımsız; ekran yaklaşık 2 saniyede yenilenir. {foreground ? 'Görünür telefon servisiyle arka planda akış sürer.' : 'Uygulama kapalıyken periyodik işler çalışabilir; hızlı akış için görünür servisi başlat.'}</Text>
    {!!feed.backoff_until && feed.backoff_until > now && <Text style={styles.small}>Borsa hız sınırı; REST isteği bekletiliyor.</Text>}
    {!!feed.error && <Text style={{ color: RED, fontSize: 12 }}>{feed.error}</Text>}
  </View>;
}
