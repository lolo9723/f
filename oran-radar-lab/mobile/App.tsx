import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator, AppState, FlatList, Modal, ScrollView, StatusBar,
  StyleSheet, Text, TextInput, TouchableOpacity, View
} from 'react-native';
import Svg, { Line, Path } from 'react-native-svg';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLivePrices, withLivePrice } from './src/lib/livePrices';
import { LivePricePanel } from './src/components/LivePricePanel';
import { formatMoney } from './src/lib/money';
import { normalizeServer, requestJSON, serverJSON } from './src/lib/api';
import { useDeviceRadar } from './src/lib/deviceRadar';
import { mergeServerPositions, usePaperTracker } from './src/lib/paperTracker';
import { PaperAdd, PaperCard, PaperMarket } from './src/components/PaperControls';
import { BackendControl, ResearchCard, ResearchPerformance } from './src/components/ResearchPanel';
import { PhonePanel } from './src/components/PhonePanel';
import { ScoreRefreshPanel } from './src/components/ScoreRefreshPanel';
import { PHONE_API, usePhone } from './src/lib/phone';
import { cacheSnapshot, cachedSnapshot, fetchSnapshot, LabSnapshot, type LabItem, type TradePlan } from './src/lib/lab';
import type { PaperPosition } from './src/lib/paperCore';

export type Scenario = {
  entry: number; target: number; stop: number; discount_pct: number;
  entry_probabilities: Record<string, number | null>;
  target_probabilities: Record<string, number | null>;
  target_before_stop: number | null; stop_before_target: number | null;
  expected_net_pct: number | null; net_target_pct: number;
  historical_trade_samples: number; historical_entry_samples: number;
  estimated_fill_hours_if_touched: number | null;
  expected_mae_pct: number | null; expected_mfe_pct: number | null;
  avg_resolved_hours: number | null;
};
export type Opportunity = {
  pair: string; coin: string; display: string; price: number;
  price_observed_at?: number; rally_score: number | null; state: string;
  spread_pct: number; scenario: Scenario | null; trade_plan?: TradePlan | null;
  features: Record<string, any>; observed_at: number;
};
export type Signal = {
  signal_id: string; pair: string; state: string; outcome: string | null;
  entry: number; target: number; stop: number; created_at: number;
  filled_at: number | null; closed_at: number | null; entry_expires_at: number;
  entry_probability: number | null; target_probability: number | null;
  net_return_pct: number | null; rally_score: number;
  features?: Record<string, any>;
  events?: Array<{ kind: string; occurred_at: number; observed_price: number | null }>;
};
export type ChartData = { minute: Array<{ time: number; close: number }>; hour?: Array<{ time: number; close: number }>; as_of: number | null };
export type Tab = 'Radar' | 'Aktif' | 'Geçmiş' | 'Performans';

export const BG = '#09111D', PANEL = '#111D2C', MUTED = '#8DA0B5';
export const TEAL = '#5DE1BB', RED = '#FF7B88', WHITE = '#F2F6FB';
const DEFAULT_API = '';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false,
  }),
});
export function money(n?: number | null) { return formatMoney(n); }
export function pct(n?: number | null) {
  return n == null ? 'Veri yetersiz' : '%' + (100 * n).toFixed(0);
}
export function clock(ms?: number | null) {
  return ms ? new Date(ms).toLocaleString('tr-TR', { year: 'numeric', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';
}
function Badge({ label }: { label: string }) {
  const color = ['ENTRY', 'SUCCESS', 'TARGET ACTIVE'].includes(label) ? TEAL :
    label === 'STOPPED' ? RED : '#F6C579';
  return <View style={[styles.badge, { backgroundColor: color + '20' }]}>
    <Text style={{ color, fontSize: 11, fontWeight: '800' }}>{label}</Text>
  </View>;
}
function PairCard({ item, onPress }: { item: Opportunity; onPress: () => void }) {
  return <TouchableOpacity onPress={onPress} style={styles.card}>
    <View style={styles.row}>
      <View><Text style={styles.pair}>{item.display}</Text>
        <Text style={styles.muted}>₺{money(item.price)} · {Date.now() - (item.price_observed_at || item.observed_at) < 30000 ? 'Canlı fiyat' : 'Son gözlem'}</Text></View>
      <View style={{ alignItems: 'flex-end' }}><Text style={styles.score}>{item.rally_score ?? '—'}<Text style={styles.scoreSmall}> / 100</Text></Text>
        <Badge label={item.state} /></View>
    </View>
    <View style={styles.rule} />
    {item.features.device_only ? <View style={styles.row}>
      <Text style={styles.small}>24s değişim <Text style={{ color: item.features.change_24h >= 0 ? TEAL : RED }}>
        {(item.features.change_24h >= 0 ? '+' : '') + (item.features.change_24h * 100).toFixed(2)}%</Text></Text>
      <Text style={styles.small}>Hacim ₺{money(item.features.quote_volume_24h)}</Text>
    </View> : <><View style={styles.row}>
      <Text style={styles.small}>Limit alış <Text style={styles.light}>₺{money(item.scenario?.entry)}</Text></Text>
      <Text style={styles.small}>Hedef <Text style={styles.light}>₺{money(item.scenario?.target)}</Text></Text>
    </View></>}
    {!item.features.device_only && <View style={[styles.row, { marginTop: 8 }]}>
      <Text style={styles.small}>48sa hedef/stop: {pct(item.scenario?.target_before_stop)}</Text>
      <Text style={styles.small}>Net beklenti: {item.scenario?.expected_net_pct == null ? '—' : item.scenario.expected_net_pct.toFixed(2) + '%'}</Text>
    </View>}
  </TouchableOpacity>;
}
function SignalCard({ item, onPress }: { item: Signal; onPress: () => void }) {
  return <TouchableOpacity style={styles.card} onPress={onPress}>
    <View style={styles.row}><Text style={styles.pair}>{item.pair.replace('TRY', '/TRY')}</Text><Badge label={item.outcome || item.state} /></View>
    <Text style={[styles.muted, { marginTop: 12 }]}>Alış ₺{money(item.entry)} · Hedef ₺{money(item.target)} · Stop ₺{money(item.stop)}</Text>
    <View style={[styles.row, { marginTop: 10 }]}>
      <Text style={styles.small}>{item.outcome === 'ENTRY_NOT_FILLED' ? 'İşlem açılmadı' :
        item.filled_at ? 'Seviye görüldü ' + clock(item.filled_at) : 'Son geçerlilik ' + clock(item.entry_expires_at)}</Text>
      <Text style={{ color: item.net_return_pct != null && item.net_return_pct >= 0 ? TEAL : RED, fontWeight: '700' }}>
        {item.net_return_pct == null ? '' : (item.net_return_pct >= 0 ? '+' : '') + item.net_return_pct.toFixed(2) + '%'}
      </Text>
    </View>
  </TouchableOpacity>;
}
export function Chart({ data, entry, target, stop }: { data: ChartData | null; entry?: number; target?: number; stop?: number }) {
  const minute = data?.minute || [];
  const bars = minute.length >= 2 ? minute : data?.hour || [];
  if (bars.length < 2) return <Text style={styles.muted}>Kapalı mum verisi bekleniyor.</Text>;
  const values = bars.map(x => x.close);
  const levels = [entry, target, stop].filter((x): x is number => x !== undefined);
  const lo = Math.min(...values, ...levels) * .999, hi = Math.max(...values, ...levels) * 1.001;
  const x = (i: number) => 8 + i * 324 / Math.max(1, values.length - 1);
  const y = (v: number) => 164 - 154 * (v - lo) / (hi - lo);
  const path = values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ');
  return <View><Svg width="100%" height="174" viewBox="0 0 340 174">
    {entry !== undefined && <Line x1="8" x2="332" y1={y(entry)} y2={y(entry)} stroke="#F6C579" strokeDasharray="5,5" />}
    {target !== undefined && <Line x1="8" x2="332" y1={y(target)} y2={y(target)} stroke={TEAL} strokeDasharray="5,5" />}
    {stop !== undefined && <Line x1="8" x2="332" y1={y(stop)} y2={y(stop)} stroke={RED} strokeDasharray="5,5" />}
    <Path d={path} stroke={TEAL} strokeWidth="2.5" fill="none" />
  </Svg><Text style={styles.small}>{bars.length} kapalı {minute.length >= 2 ? '1 dk' : '1 saat'} mum · Alış sarı · Hedef yeşil · Stop kırmızı</Text>
    <Text style={styles.small}>Grafik güncellemesi: {clock(data?.as_of)}</Text></View>;
}
export function Metric({ label, value }: { label: string; value: string | number }) {
  return <View style={[styles.row, { paddingVertical: 7, flexWrap: 'wrap' }]}>
    <Text style={styles.muted}>{label}</Text><Text style={styles.light}>{value}</Text></View>;
}

export default function Dashboard({ tab }: { tab: Tab }) {
  const phone = usePhone();
  const router = useRouter();
  const [base, setBase] = useState(DEFAULT_API);
  const [ready, setReady] = useState(false);
  const [draft, setDraft] = useState('');
  const [tokenDraft, setTokenDraft] = useState('');
  const [settingError, setSettingError] = useState('');
  const [editing, setEditing] = useState(false);
  const [opps, setOpps] = useState<Opportunity[]>([]);
  const [active, setActive] = useState<Signal[]>([]);
  const [history, setHistory] = useState<Signal[]>([]);
  const [metrics, setMetrics] = useState<any>(null);
  const [health, setHealth] = useState<any>(null);
  const [lab, setLab] = useState<LabSnapshot | null>(null);
  const [labOffline, setLabOffline] = useState(true);
  const [researchMode, setResearchMode] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [pushStatus, setPushStatus] = useState('');
  const [markets, setMarkets] = useState<PaperMarket[]>([]);
  const [coinSearch, setCoinSearch] = useState('');
  const visibleScores = useRef<string[]>([]);
  const scoreViewability = useRef({ itemVisiblePercentThreshold: 20 });
  const scoreVisibilityChanged = useRef(({ viewableItems }: { viewableItems: { item: { pair: string }; isViewable: boolean }[] }) => {
    visibleScores.current = viewableItems.filter(row => row.isViewable).map(row => row.item.pair);
  });
  const api = phone.enabled || (phone.available && !base) ? PHONE_API : base.replace(/\/$/, '');
  const device = useDeviceRadar(ready && !api);
  const paper = usePaperTracker();
  const live = useLivePrices(api === PHONE_API);
  const paperMarkets = api === PHONE_API ? markets.map(item => { const quoted = withLivePrice(item, live); return { ...quoted, observed_at: quoted.price_observed_at ?? item.observed_at }; }) : api ? markets : device.markets;
  useEffect(() => {
    AsyncStorage.getItem('radarApi').then(saved => setBase(saved || '')).finally(() => setReady(true));
    AsyncStorage.getItem('radarApiToken').then(saved => setTokenDraft(saved || ''));
  }, []);
  useEffect(() => {
    if (!ready) return;
    if (!api) { setLab(null); setResearchMode(false); setActive([]); setHistory([]); setMetrics(null); setHealth(null); setError(''); setLoading(false); return; }
    let alive = true;
    let fetching = false;
    let receivedFresh = false;
    setLab(null); setResearchMode(false); setLabOffline(true);
    void cachedSnapshot(api).then(value => { if (alive && value && !receivedFresh) { setLab(value); setResearchMode(true); } });
    setLoading(true);
    async function refresh() {
      if (fetching) return;
      fetching = true;
      try {
        const status = await serverJSON(api, '/health');
        if (status.research_backend) {
          if (alive) { setResearchMode(true); setHealth(status); }
          const snapshot = await fetchSnapshot(api);
          if (!alive) return;
          receivedFresh = true;
          setResearchMode(true); setLab(snapshot); setLabOffline(false); setHealth(status); setError('');
          setActive([]); setHistory([]); setMetrics(null); setOpps([]);
          setMarkets(snapshot.items.map(item => ({ pair: item.pair, display: item.display, price: item.price, observed_at: item.price_observed_at ?? item.observed_at, trade_plan: item.trade_plan })));
          await mergeServerPositions(api, snapshot.paper_positions);
          await cacheSnapshot(api, snapshot).catch(() => undefined);
          return;
        }
        if (alive) { setResearchMode(false); setLab(null); }
        const paths = ['/opportunities', '/signals', '/history', '/metrics', '/health', '/pairs', '/paper-positions'];
        const results = await Promise.all(paths.map(path => serverJSON(api, path)));
        if (!alive) return;
        await mergeServerPositions(api, results[6]);
        if (alive) { setOpps(results[0]); setActive(results[1]); setHistory(results[2]);
          setMetrics(results[3]); setHealth(results[4]); setMarkets(results[5]); setError(''); }
      } catch (e: any) { if (alive) { setLabOffline(true); setError('Sunucu bağlantısı kurulamadı: ' + String(e.message)); } }
      finally { fetching = false; if (alive) setLoading(false); }
    }
    refresh();
    const interval = setInterval(() => { if (AppState.currentState === 'active') void refresh(); }, 15000);
    const subscription = AppState.addEventListener('change', state => { if (state === 'active') void refresh(); });
    return () => { alive = false; clearInterval(interval); subscription.remove(); };
  }, [api, ready]);
  function open(item: Opportunity | Signal) {
    router.push({ pathname: '/detail', params: {
      pair: item.pair, signalId: 'signal_id' in item ? item.signal_id : ''
    } });
  }
  function openPaper(item: PaperPosition) {
    router.push({ pathname: '/detail', params: { pair: item.pair, paperId: item.position_id } });
  }
  async function enablePush() {
    try {
      if (!api) throw new Error('Bildirimler için sürekli çalışan analiz sunucusunu bağlayın.');
      const projectId = Constants.easConfig?.projectId || Constants.expoConfig?.extra?.eas?.projectId;
      if (!projectId) throw new Error('Bu MVP’de uzaktan push yapılandırması henüz etkin değil.');
      if (!Device.isDevice) throw new Error('Fiziksel Android cihaz gerekiyor.');
      await Notifications.setNotificationChannelAsync('signals', {
        name: 'Sinyal takibi', importance: Notifications.AndroidImportance.HIGH
      });
      let permissions = await Notifications.getPermissionsAsync();
      if (!permissions.granted) permissions = await Notifications.requestPermissionsAsync();
      if (!permissions.granted) throw new Error('Bildirim izni verilmedi.');
      const token = (await Notifications.getExpoPushTokenAsync({ projectId })).data;
      await serverJSON(api, '/devices', {
        method: 'POST',
        body: JSON.stringify({ token }),
      });
      setPushStatus('Bu cihaz bildirimlere kaydedildi.');
    } catch (e: any) { setPushStatus(String(e.message)); }
  }
  const query = coinSearch.trim().toUpperCase().replace(/[\s/]/g, '');
  const radarRows: { pair: string; research?: LabItem; opportunity?: Opportunity }[] = researchMode
    ? [...(lab?.items || [])].filter(item => item.pair.includes(query))
      .sort((a, b) => Number(b.rally?.status === 'CURRENT') - Number(a.rally?.status === 'CURRENT') || (b.rally_score ?? -1) - (a.rally_score ?? -1) || a.pair.localeCompare(b.pair))
      .map(item => { const research = api === PHONE_API ? withLivePrice(item, live) : item; return { pair: research.pair, research }; })
    : (api ? opps : device.opportunities).filter(item => item.pair.includes(query)).map(opportunity => ({ pair: opportunity.pair, opportunity }));
  return <SafeAreaView style={styles.root}>
    <StatusBar barStyle="light-content" backgroundColor={BG} />
    <View style={styles.header}>
      <View><Text style={styles.eyebrow}>BINANCE TR · PAPER TRADING</Text>
        <Text style={styles.title}>ORAN RADAR<Text style={{ color: TEAL }}>.</Text></Text></View>
      <TouchableOpacity accessibilityLabel="Sunucu ve bildirim ayarları" onPress={() => {
        setDraft(base); setSettingError(''); setEditing(true);
      }} style={styles.settings}><Text style={{ color: WHITE }}>⚙</Text></TouchableOpacity>
    </View>
    <View style={styles.status}><View style={[styles.dot, { backgroundColor:
      (api ? error || loading || (researchMode && (labOffline || !lab?.audit.ok)) : device.error || !device.connected) ? RED : TEAL }]} />
      <Text style={[styles.small, { flex: 1 }]}>{api ? error || (researchMode ? `${lab?.items.length || 0} parite · ${api === PHONE_API ? 'Telefon motoru' : 'Bağımsız araştırma backend’i'}` : `${health?.selected_pairs || 0} parite · Paper sunucu bağlı`) :
        device.error || (device.connected ? `${device.pairs} likit TRY paritesi · Cihaz radarı${device.scanning ? ' · Analiz' : ''}` : 'Binance TR’ye bağlanıyor…')}</Text></View>
    <View style={styles.tabBar}>{(['Radar', 'Aktif', 'Geçmiş', 'Performans'] as Tab[]).map(t =>
      <TouchableOpacity key={t} onPress={() => router.replace(
        t === 'Radar' ? '/radar' : t === 'Aktif' ? '/active' :
        t === 'Geçmiş' ? '/history' : '/performance'
      )} style={[styles.tab, tab === t && styles.currentTab]}>
        <Text style={{ color: tab === t ? TEAL : MUTED, fontWeight: '700', fontSize: 12 }}>{t}</Text>
      </TouchableOpacity>)}</View>
    {loading && <ActivityIndicator size="large" color={TEAL} style={{ marginTop: 45 }} />}
    {tab === 'Radar' && <FlatList data={radarRows} keyExtractor={x => x.pair}
      viewabilityConfig={scoreViewability.current} onViewableItemsChanged={scoreVisibilityChanged.current}
      initialNumToRender={8} maxToRenderPerBatch={8} windowSize={7}
      contentContainerStyle={styles.list}
      ListHeaderComponent={<View><Text style={styles.section}>{researchMode ? 'İSTATİSTİK LABORATUVARI' : 'TOP OPPORTUNITIES'}</Text>
        <Text style={styles.sub}>{researchMode ? 'Zaman sıralı test · dinamik kanıt eşiği · sürekli değerlendirme' : 'Likiditesi yüksek TRY pariteleri · en güçlü 10 aday'}</Text>
        <PhonePanel status={phone} />
        {api === PHONE_API && <LivePricePanel feed={live} enabled={phone.enabled} foreground={phone.foreground} />}
        {api === PHONE_API && <ScoreRefreshPanel status={phone} pairs={visibleScores} />}
        <BackendControl snapshot={lab} base={api} offline={!!error || labOffline} phoneAvailable={phone.available}
          onConfigure={() => { setDraft(base); setSettingError(''); setEditing(true); }} />
        {researchMode && <Text style={styles.sub}>{lab?.expected_pairs.length ?? 0} coin · Rally Score / 100 · istatistik araştırması ve stop-loss</Text>}
        <TextInput accessibilityLabel="Coin ara" style={styles.input} value={coinSearch} onChangeText={setCoinSearch}
          autoCapitalize="characters" autoCorrect={false} placeholder="Coin ara: BTC, ETH, SOL…" placeholderTextColor={MUTED} />
        <PaperAdd markets={paperMarkets} server={api} offline={!!error || (researchMode && labOffline)} />
        {!api && <View style={[styles.card, { padding: 14 }]}><Text style={styles.light}>CANLI PİYASA RADARI</Text>
          <Text style={styles.muted}>Ön skor; başarı olasılığı değildir. Sanal alışlarını Aktif ekranında izle. Kesintisiz takip için sunucu bağlantısı gerekir.</Text>
          <TouchableOpacity onPress={() => { setDraft(base); setSettingError(''); setEditing(true); }}>
            <Text style={{ color: TEAL, marginTop: 8, fontWeight: '700' }}>Sunucuyu bağla ›</Text>
          </TouchableOpacity></View>}</View>}
      ListEmptyComponent={!loading ? <Text style={styles.empty}>{query ? 'Aramana uyan coin bulunamadı.' : 'Coin listesi ve ilk analiz bekleniyor.'}</Text> : null}
      renderItem={({ item }) => item.research
        ? <ResearchCard key={item.pair} item={item.research} offline={!!error || labOffline} server={api}
          onOpen={() => router.push({ pathname: '/detail', params: { pair: item.pair } })} />
        : <PairCard item={item.opportunity!} onPress={() => open(item.opportunity!)} />} />}
    {tab === 'Aktif' && <FlatList<PaperPosition | Signal> data={[...paper.positions.filter(p => p.state === 'TRACKING'), ...active]}
      keyExtractor={x => 'position_id' in x ? x.position_id : x.signal_id}
      contentContainerStyle={styles.list}
      ListHeaderComponent={<><Text style={styles.section}>AKTİF TAKİP</Text><Text style={styles.sub}>Sanal alışların ve model sinyalleri · Binance TR gerçekleşen işlemleri</Text>
        <PaperAdd markets={paperMarkets} server={api} offline={!!error || (researchMode && labOffline)} />
        {!!paper.error && <Text style={[styles.muted, { marginBottom: 12 }]}>{paper.error}</Text>}
        {paper.syncing && <Text style={styles.sub}>İşlem geçmişi doğrulanıyor…</Text>}
        <Text style={styles.small}>{phone.enabled ? 'Yeni kayıtlar telefon motorunda, arka plan izinleriyle takip edilir. Önceki cihaz kayıtları uygulama açıkken izlenir.' : 'Cihaz kayıtları uygulama açıkken takip edilir. Sunucu kayıtları bağlı sunucuda izlenir.'}</Text>
      </>}
      ListEmptyComponent={<Text style={styles.empty}>{api ? 'Aktif paper sinyal yok. Radar adayları izlemeye devam ediyor.' :
        'Henüz sanal alış yok. Yukarıdaki düğmeden coin ve hedef seç.'}</Text>}
      renderItem={({ item }) => 'position_id' in item ? <PaperCard position={item} onPress={() => openPaper(item)} /> :
        <SignalCard item={item} onPress={() => open(item)} />} />}
    {tab === 'Geçmiş' && <FlatList<PaperPosition | Signal> data={[...paper.positions.filter(p => p.state !== 'TRACKING'), ...history]}
      keyExtractor={x => 'position_id' in x ? x.position_id : x.signal_id}
      contentContainerStyle={styles.list}
      ListHeaderComponent={<><Text style={styles.section}>HISTORY</Text><Text style={styles.sub}>Kapanan sinyaller ve ölçülen sonuçlar</Text></>}
      ListEmptyComponent={<Text style={styles.empty}>{api ? 'Henüz sonuçlanan bir sinyal yok.' :
        'Sanal alışların hedef, stop ve 48 saat sonuçları burada saklanır.'}</Text>}
      renderItem={({ item }) => 'position_id' in item ? <PaperCard position={item} onPress={() => openPaper(item)} /> :
        <SignalCard item={item} onPress={() => open(item)} />} />}
    {tab === 'Performans' && <ScrollView contentContainerStyle={styles.list}>
      {researchMode ? <ResearchPerformance snapshot={lab} /> : <><Text style={styles.section}>FORWARD PERFORMANCE</Text>
      <Text style={styles.sub}>{api ? 'Gerçek emir verilmiyor. İşlem görmüş fiyat seviyeleri izleniyor.' :
        'Sunucu bağlı değil. Performans verisi henüz yok.'}</Text>
      <View style={styles.card}>
        <Metric label="Tamamlanan sanal işlem" value={metrics?.independent_forward_trades ?? '—'} />
        <Metric label="Giriş seviyesi görülme" value={pct(metrics?.entry_observed_rate)} />
        <Metric label="Giriş sonrası hedef" value={pct(metrics?.success_after_entry)} />
        <Metric label="Net ortalama" value={metrics?.mean_net_pct == null ? '—' : metrics.mean_net_pct + '%'} />
        <Metric label="Giriş görülmedi" value={metrics?.entry_not_filled ?? '—'} />
        <Metric label="Stop oranı" value={pct(metrics?.stop_rate)} />
      </View>
      <Text style={styles.section}>KALİBRASYON</Text>
      {(metrics?.calibration || []).filter((b: any) => b.n).map((b: any) =>
        <View key={b.range} style={[styles.card, { paddingVertical: 12 }]}>
          <Text style={styles.light}>Tahmin {b.range} · n={b.n}</Text>
          <Text style={styles.muted}>Ortalama tahmin {pct(b.predicted)} · gerçekleşen {pct(b.observed)}</Text>
        </View>)}
      <Text style={styles.empty}>Yüzlerce bağımsız ileriye dönük işlem ve ayrı test dönemi olmadan başarı iddiası kurulmaz.</Text></>}
    </ScrollView>}
    <Modal visible={editing} onRequestClose={() => setEditing(false)} transparent animationType="fade">
      <View style={styles.overlay}><View style={styles.dialog}>
        <Text style={styles.section}>SUNUCU VE BİLDİRİMLER</Text>
        <Text style={styles.sub}>Yayınlanan sunucu adresini ve erişim anahtarını girin. Sunucu bağlanmadan cihaz takibi kullanılabilir.</Text>
        <TextInput autoCapitalize="none" autoCorrect={false} keyboardType="url" value={draft} onChangeText={setDraft}
          style={styles.input} placeholder="https://sunucu-adresin" placeholderTextColor={MUTED} />
        <TextInput accessibilityLabel="Sunucu erişim anahtarı" secureTextEntry autoCapitalize="none" autoCorrect={false}
          value={tokenDraft} onChangeText={setTokenDraft} style={styles.input} placeholder="Sunucu erişim anahtarı" placeholderTextColor={MUTED} />
        <TouchableOpacity style={styles.button} onPress={async () => {
          try {
            const normalized = normalizeServer(draft);
            if (normalized) {
              const status = await requestJSON(normalized + '/health');
              if (status.mode !== 'PAPER_ONLY') throw new Error('Oran Radar paper sunucusu bulunamadı.');
              if (!status.paper_tracking) throw new Error('Sanal alış takibi için sunucu güncellenmeli.');
              await serverJSON(normalized, '/paper-positions', undefined, tokenDraft.trim());
            }
            await AsyncStorage.setItem('radarApiToken', tokenDraft.trim());
            await AsyncStorage.setItem('radarApi', normalized); setBase(normalized); setEditing(false);
          } catch (e: any) { setSettingError('Bağlantı doğrulanamadı: ' + e.message); }
        }}><Text style={styles.buttonText}>Kaydet</Text></TouchableOpacity>
        {!!settingError && <Text style={{ color: RED, fontSize: 12, marginTop: 6 }}>{settingError}</Text>}
        <TouchableOpacity style={styles.buttonSecondary} onPress={async () => {
          await AsyncStorage.removeItem('radarApi'); setBase(''); setEditing(false);
        }}><Text style={styles.light}>Cihaz radarını kullan</Text></TouchableOpacity>
        <TouchableOpacity style={styles.buttonSecondary} onPress={enablePush}>
          <Text style={styles.light}>Push bildirimlerini aç</Text></TouchableOpacity>
        {!!pushStatus && <Text style={styles.muted}>{pushStatus}</Text>}
        <Text style={[styles.small, { marginTop: 12 }]}>v3.7 Lab · Canlı fiyat akışı · Araştırma puanı</Text>
        <TouchableOpacity onPress={() => setEditing(false)}><Text style={[styles.muted, { textAlign: 'center', marginTop: 16 }]}>Kapat</Text></TouchableOpacity>
      </View></View>
    </Modal>
  </SafeAreaView>;
}
export const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: BG }, header: { paddingHorizontal: 20, paddingTop: 22,
    paddingBottom: 14, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  eyebrow: { color: TEAL, fontWeight: '800', fontSize: 10, letterSpacing: 2 },
  title: { color: WHITE, fontSize: 27, fontWeight: '900', letterSpacing: -.7, marginTop: 5 },
  settings: { backgroundColor: PANEL, padding: 12, borderRadius: 12 },
  status: { flexDirection: 'row', paddingHorizontal: 20, paddingBottom: 16, alignItems: 'center' },
  dot: { width: 7, height: 7, borderRadius: 4, marginRight: 8 },
  tabBar: { flexDirection: 'row', paddingHorizontal: 12, borderBottomWidth: 1, borderColor: '#26384B' },
  tab: { flex: 1, alignItems: 'center', paddingVertical: 12 },
  currentTab: { borderBottomColor: TEAL, borderBottomWidth: 2 },
  list: { padding: 18, paddingBottom: 55 },
  section: { color: WHITE, fontSize: 14, fontWeight: '900', letterSpacing: 1.2, marginBottom: 7 },
  sub: { color: MUTED, fontSize: 12, marginBottom: 18, lineHeight: 18 },
  card: { backgroundColor: PANEL, padding: 18, borderRadius: 17, marginBottom: 11, borderWidth: 1, borderColor: '#1D3043' },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 },
  pair: { color: WHITE, fontWeight: '800', fontSize: 17 },
  muted: { color: MUTED, fontSize: 12, lineHeight: 20 },
  small: { color: MUTED, fontSize: 10, lineHeight: 16 },
  light: { color: WHITE, fontSize: 12, fontWeight: '600' },
  score: { color: TEAL, fontWeight: '900', fontSize: 25 },
  scoreSmall: { color: MUTED, fontSize: 12 },
  badge: { paddingHorizontal: 9, paddingVertical: 5, borderRadius: 7, alignSelf: 'flex-end', marginTop: 4 },
  rule: { height: 1, backgroundColor: '#273A4B', marginVertical: 13 },
  empty: { color: MUTED, fontSize: 13, lineHeight: 21, textAlign: 'center', marginTop: 28, paddingHorizontal: 18 },
  overlay: { flex: 1, backgroundColor: '#000B', justifyContent: 'center', padding: 20 },
  dialog: { backgroundColor: PANEL, borderRadius: 20, padding: 20 },
  input: { color: WHITE, backgroundColor: BG, padding: 14, marginBottom: 12, borderRadius: 10 },
  button: { backgroundColor: TEAL, padding: 14, alignItems: 'center', borderRadius: 10 },
  buttonText: { color: BG, fontWeight: '900' },
  buttonSecondary: { borderColor: '#3E6475', borderWidth: 1, padding: 14, alignItems: 'center',
    borderRadius: 10, marginTop: 10 },
});
