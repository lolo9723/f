import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLivePrices, withLivePrice } from '../lib/livePrices';
import { useRouter } from 'expo-router';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { serverJSON } from '../lib/api';
import { deviceChart, deviceOpportunity } from '../lib/deviceRadar';
import { mergeServerPositions, usePaperTracker } from '../lib/paperTracker';
import { PHONE_API, usePhone } from '../lib/phone';
import { PaperAdd, PaperCard } from './PaperControls';
import { ScoreRefreshPanel } from './ScoreRefreshPanel';
import { ResearchCard } from './ResearchPanel';
import type { LabItem } from '../lib/lab';
import {
  BG, Chart, ChartData, Metric, Opportunity, Signal, TEAL, clock, money, pct, styles
} from '../../App';

export default function DetailScreen({ pair, signalId, paperId }: { pair: string; signalId?: string; paperId?: string }) {
  const router = useRouter();
  const phone = usePhone();
  const scorePairs = useRef([pair]);
  useEffect(() => { scorePairs.current = [pair]; }, [pair]);
  const [opportunity, setOpportunity] = useState<Opportunity | null>(null);
  const [signal, setSignal] = useState<Signal | null>(null);
  const [chart, setChart] = useState<ChartData | null>(null);
  const [error, setError] = useState('');
  const [deviceMode, setDeviceMode] = useState(false);
  const [server, setServer] = useState('');
  const [research, setResearch] = useState<LabItem | null>(null);
  const live = useLivePrices(server === PHONE_API);
  const visibleResearch = research ? withLivePrice(research, live) : null;
  const paper = usePaperTracker();
  const position = paper.positions.find(p => p.position_id === paperId);
  useEffect(() => {
    let alive = true;
    async function refresh() {
      try {
        const configured = (await AsyncStorage.getItem('radarApi') || '').replace(/\/$/, '');
        const base = phone.enabled || position?.server === PHONE_API || (phone.available && !configured) ? PHONE_API : configured;
        if (alive) { setServer(base); setDeviceMode(!base); }
        if (!base) {
          const candleData = await deviceChart(pair);
          if (alive) { setDeviceMode(true); setOpportunity(deviceOpportunity(pair)); setChart(candleData); setError(''); }
          return;
        }
        if (base === PHONE_API) await mergeServerPositions(base, await serverJSON(base, '/paper-positions'));
        const requests = [
          serverJSON(base, '/market?pair=' + encodeURIComponent(pair)),
          serverJSON(base, '/candles?pair=' + encodeURIComponent(pair)),
          signalId ? serverJSON(base, '/signals/' + signalId) : Promise.resolve(null)
        ];
        const [market, candleData, signalData] = await Promise.all(requests);
        if (alive) {
          setOpportunity(market.opportunity || null); setChart(candleData);
          setResearch(market.research || null);
          setSignal(signalData && signalData.signal_id ? signalData : null); setError('');
        }
      } catch (e: any) { if (alive) setError('Veri alınamadı: ' + String(e.message)); }
    }
    refresh();
    const interval = setInterval(refresh, 10000);
    return () => { alive = false; clearInterval(interval); };
  }, [pair, signalId, phone.enabled, phone.available, position?.server]);
  const scenario = signal ? null : opportunity?.scenario;
  const planMarks = research?.trade_plan?.entry != null ? { entry: research.trade_plan.entry, target: research.trade_plan.target!, stop: research.trade_plan.stop! } : null;
  const marks = position || signal || scenario || planMarks;
  return <SafeAreaView style={{ flex: 1, backgroundColor: BG }}>
    <ScrollView contentContainerStyle={styles.list}>
      <TouchableOpacity onPress={() => router.back()}>
        <Text style={{ color: TEAL, fontWeight: '800', marginVertical: 16 }}>‹ GERİ</Text>
      </TouchableOpacity>
      <Text style={styles.title}>{pair.replace('TRY', '/TRY')}</Text>
      <Text style={styles.sub}>Binance TR · {signal?.state || opportunity?.state || 'Veri bekleniyor'}</Text>
      {deviceMode && <Text style={styles.sub}>Cihaz radarı · Ön skor başarı olasılığı değildir. Limit giriş ve hedef modelinin çalışması için sunucu bağlantısı gerekir.</Text>}
      {!!error && <Text style={styles.muted}>{error}</Text>}
      {server === PHONE_API && <ScoreRefreshPanel status={phone} pairs={scorePairs} />}
      {position && <PaperCard position={position} />}
      {!position && opportunity && !research && <PaperAdd server={server} initialPair={pair} markets={[{
        pair, display: opportunity.display, price: opportunity.price,
        observed_at: opportunity.price_observed_at || opportunity.observed_at, trade_plan: opportunity.trade_plan
      }]} />}
      <View style={styles.card}><Chart data={chart} entry={marks?.entry}
        target={marks?.target} stop={marks?.stop ?? undefined} /></View>
      {research && <ResearchCard key={pair} item={visibleResearch!} offline={!!error} server={server} />}
      <View style={styles.card}>
        <Text style={styles.section}>FİYAT SEVİYELERİ</Text>
        <Metric label="Limit alış" value={'₺' + money(marks?.entry)} />
        <Metric label="48 saat hedefi" value={'₺' + money(marks?.target)} />
        <Metric label="Stop / geçersizlik" value={'₺' + money(marks?.stop)} />
        {scenario && <>
          <Metric label="İskonto" value={scenario.discount_pct.toFixed(2) + '%'} />
          <Metric label="Görülürse ort. süre" value={scenario.estimated_fill_hours_if_touched == null ?
            '—' : scenario.estimated_fill_hours_if_touched + ' saat'} />
          <Metric label="Giriş 2 / 6 / 12 / 24s" value={[2, 6, 12, 24].map(h =>
            pct(scenario.entry_probabilities[String(h)])).join(' · ')} />
          <Metric label="Hedef 6 / 12 / 24 / 48s" value={[6, 12, 24, 48].map(h =>
            pct(scenario.target_probabilities[String(h)])).join(' · ')} />
          <Metric label="Hedef stop öncesi" value={pct(scenario.target_before_stop)} />
          <Metric label="Stop hedef öncesi" value={pct(scenario.stop_before_target)} />
          <Metric label="Bağımsız tarihsel örnek" value={scenario.historical_trade_samples} />
          <Metric label="Tarihsel MAE / MFE" value={scenario.expected_mae_pct == null ?
            '—' : scenario.expected_mae_pct + '% / ' + scenario.expected_mfe_pct + '%'} />
          <Metric label="Ortalama sonuçlanma" value={scenario.avg_resolved_hours == null ?
            '—' : scenario.avg_resolved_hours + ' saat'} />
          <Metric label="Tahmini net beklenti" value={scenario.expected_net_pct == null ?
            '—' : scenario.expected_net_pct + '%'} />
        </>}
      </View>
      {(signal?.features || opportunity) && <View style={styles.card}>
        <Text style={styles.section}>RALLY HUNTER</Text>
        <Metric label="Rally Score" value={(signal?.rally_score ?? opportunity?.rally_score ?? '—') + ' / 100'} />
        <Metric label="Hacim oranı" value={(signal?.features || opportunity?.features)?.volume_ratio ?? '—'} />
        <Metric label="Alış defteri payı" value={pct((signal?.features || opportunity?.features)?.book_imbalance)} />
        <Metric label="Spread" value={((signal?.features || opportunity?.features)?.spread_pct ?? opportunity?.spread_pct ?? '—') + '%'} />
        <Metric label="Piyasa rejimi" value={(signal?.features || opportunity?.features)?.regime ?? '—'} />
        <Text style={styles.muted}>OI, funding ve on-chain veriler bu sürümde bağlı değildir.</Text>
      </View>}
      {signal && <View style={styles.card}>
        <Text style={styles.section}>SİNYAL VE SONUÇ</Text>
        <Metric label="ID" value={signal.signal_id.slice(0, 8)} />
        <Metric label="Giriş oranı" value={pct(signal.entry_probability)} />
        <Metric label="Hedef/stop oranı" value={pct(signal.target_probability)} />
        <Metric label="Sonuç" value={signal.outcome || signal.state} />
        <Metric label="Net sanal getiri" value={signal.net_return_pct == null ?
          '—' : signal.net_return_pct.toFixed(2) + '%'} />
        {(signal.events || []).map((e, i) =>
          <Text style={styles.muted} key={i}>{clock(e.occurred_at)} · {e.kind} · ₺{money(e.observed_price)}</Text>)}
      </View>}
      <Text style={styles.empty}>Ön kalibrasyon. Fiyat seviyesine dokunulması gerçek limit emrinin dolacağını kanıtlamaz.</Text>
    </ScrollView>
  </SafeAreaView>;
}
