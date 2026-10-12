import React, { useEffect, useState, type RefObject } from 'react';
import { AppState, Text, TouchableOpacity, View } from 'react-native';
import { clock, RED, styles } from '../../App';
import { refreshScores, type PhoneStatus } from '../lib/phone';

export function ScoreRefreshPanel({ status, pairs }: { status: PhoneStatus; pairs: RefObject<string[]> }) {
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  useEffect(() => {
    if (!status.enabled) return;
    const request = () => {
      if (AppState.currentState === 'active') void refreshScores(pairs.current).catch(() => undefined);
    };
    request();
    const timer = setInterval(request, 15000);
    const listener = AppState.addEventListener('change', state => {
      if (state === 'active') request();
      else void refreshScores([]).catch(() => undefined);
    });
    return () => { clearInterval(timer); listener.remove(); void refreshScores([]).catch(() => undefined); };
  }, [status.enabled, pairs]);
  return <View style={[styles.card, { padding: 14 }]}>
    <Text style={styles.section}>OTOMATİK PUAN YENİLEME</Text>
    <Text style={styles.muted}>{status.scoreRunning ? 'Puan taraması çalışıyor.' : status.enabled ? 'Puan taraması araştırmadan bağımsız.' : 'Otomatik puan yenileme için telefon motorunu başlat.'}</Text>
    <Text style={styles.small}>Son puan taraması: {clock(status.lastScoreCycle)}</Text>
    <Text style={styles.small}>30 saniyelik tarama aralığı hedeflenir. Görünen coinler ve tüm coin sırası birlikte işlenir; tam tur ağ hızına bağlıdır. Puan yeni hesapta aynı kalabilir.</Text>
    {!!status.scoreError && <Text style={{ color: RED, fontSize: 12 }}>{status.scoreError}</Text>}
    <TouchableOpacity accessibilityRole="button" accessibilityLabel="Puanları şimdi yenile" disabled={sending}
      style={[styles.button, { marginTop: 10, opacity: sending ? .5 : 1 }]} onPress={async () => {
        setSending(true); setMessage('');
        try {
          const result = await refreshScores(pairs.current);
          setMessage(result.status === 'PAUSED' ? 'Telefon motorunu üstteki karttan başlat.' :
            result.status === 'RUNNING' ? 'Puan taraması sürüyor; yeni sonuçlar otomatik alınacak.' :
            result.status === 'THROTTLED' ? 'Sonraki puan taraması bekleniyor; borsa hız sınırı korunuyor.' :
            'Puan taraması kuyruğa alındı; yeni sonuçlar otomatik alınacak.');
        } catch (error: any) { setMessage(error.message || 'Puan taraması başlatılamadı.'); }
        finally { setSending(false); }
      }}><Text style={styles.buttonText}>{sending ? 'İstek gönderiliyor…' : 'Puanları şimdi yenile'}</Text></TouchableOpacity>
    {!!message && <Text accessibilityLiveRegion="polite" style={styles.muted}>{message}</Text>}
  </View>;
}
