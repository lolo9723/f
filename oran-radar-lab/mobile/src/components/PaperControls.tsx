import React, { useEffect, useRef, useState } from 'react';
import { Modal, ScrollView, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { MUTED, RED, TEAL, clock, money, styles } from '../../App';
import { addPaperPosition } from '../lib/paperTracker';
import type { PaperPosition } from '../lib/paperCore';
import type { TradePlan } from '../lib/lab';
import { paperDraft, planLevels, usablePlan, marketPlan } from '../lib/paperDraft';
import { serverJSON } from '../lib/api';

export type PaperMarket = { pair: string; display?: string; price: number | null; observed_at: number | null; price_observed_at?: number | null; trade_plan?: TradePlan | null };
export function duration(start: number, end: number) {
  const minutes = Math.max(0, Math.floor((end - start) / 60000));
  return `${Math.floor(minutes / 60)} saat ${minutes % 60} dk`;
}
export function PaperCard({ position: p, onPress }: { position: PaperPosition; onPress?: () => void }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(timer); }, []);
  const label = { TRACKING: 'HEDEF TAKİPTE', SUCCESS: 'HEDEFE ULAŞTI', STOPPED: 'STOP GÖRÜLDÜ', TIME_EXPIRED: '48 SAAT DOLDU' }[p.state];
  return <TouchableOpacity disabled={!onPress} onPress={onPress} style={styles.card}>
    <View style={styles.row}><Text style={styles.pair}>{p.pair.replace(/TRY$/, '/TRY')}</Text>
      <Text style={{ color: p.state === 'STOPPED' ? RED : TEAL, fontSize: 10, fontWeight: '800' }}>{label}</Text></View>
    <Text style={[styles.muted, { marginTop: 10 }]}>Sanal alış ₺{money(p.entry)} · Hedef ₺{money(p.target)}</Text>
    <Text style={styles.muted}>Stop {p.stop === null ? 'Yok' : '₺' + money(p.stop)} · Kayıt {clock(p.created_at)}</Text>
    {p.target_hit_at !== null && <>
      <Text style={{ color: TEAL, marginTop: 8 }}>Hedef zamanı: {clock(p.target_hit_at)}</Text>
      <Text style={styles.light}>Alıştan hedefe: {duration(p.created_at, p.target_hit_at)}</Text>
    </>}
    {p.state === 'TRACKING' && <Text style={styles.muted}>
      {now >= p.deadline_at ? '48 saat geçti; doğrulanmış kapanış işlemi bekleniyor.' : 'Takip sonu: ' + clock(p.deadline_at)}
    </Text>}
    {p.closed_at && !p.target_hit_at && <Text style={styles.muted}>Kapanış: {clock(p.closed_at)} · {duration(p.created_at, p.closed_at)}</Text>}
    {p.stop_distance_pct != null && <Text style={styles.small}>Stop mesafesi %{p.stop_distance_pct.toFixed(2)} · kazanç/risk {p.risk_reward?.toFixed(2)}</Text>}
    {p.state === 'STOPPED' && p.followup_state && <Text style={styles.small}>
      {p.followup_state === 'RECOVERED_TO_TARGET' ? 'Stop sonrası hedefe döndü: ' + clock(p.recovered_at)
        : p.followup_state === 'PENDING' ? 'Stop sonrası toparlanma takip ediliyor.' : 'Stop sonrası takip tamamlandı.'}
      {' '}Stop sonucu değişmez.
    </Text>}
    <Text style={styles.small}>Son doğrulanmış işlem: {clock(p.observed_at)} · ₺{money(p.observed_price)}</Text>
    {p.net_return_pct !== null && <Text style={{ color: p.net_return_pct >= 0 ? TEAL : RED, fontWeight: '700', marginTop: 8 }}>
      Brüt {p.gross_return_pct?.toFixed(2)}% · Komisyon sonrası {p.net_return_pct.toFixed(2)}%
    </Text>}
    <Text style={[styles.small, { marginTop: 8 }]}>{p.origin === 'server' ? 'Sunucu kaydı' : 'Cihaz kaydı · uygulama açıkken takip'} · Kullanıcı seçimi</Text>
    {p.feed_blocked && <Text style={{ color: RED, fontSize: 12 }}>İşlem akışı eksik; sonuç doğrulaması bekleniyor.</Text>}
  </TouchableOpacity>;
}
const number = (value: string) => Number(value.trim().replace(',', '.'));
export function PaperAdd({ markets, server, initialPair, initialPlan, fixedPair = false, offline = false }: { markets: PaperMarket[]; server: string; initialPair?: string; initialPlan?: TradePlan | null; fixedPair?: boolean; offline?: boolean }) {
  const [visible, setVisible] = useState(false), [query, setQuery] = useState('');
  const [pair, setPair] = useState(''), [entry, setEntry] = useState(''), [target, setTarget] = useState(''), [stop, setStop] = useState('');
  const [error, setError] = useState(''), [saving, setSaving] = useState(false);
  const [savedMessage, setSavedMessage] = useState('');
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { if (!visible) return; const timer = setInterval(() => setNow(Date.now()), 15000); return () => clearInterval(timer); }, [visible]);
  const [proposal, setProposal] = useState<TradePlan | null>(null);
  const [quote, setQuote] = useState<PaperMarket | null>(null);
  const [verified, setVerified] = useState(false), [refreshing, setRefreshing] = useState(false);
  const session = useRef({ epoch: 0, pair: '', dirty: false });
  useEffect(() => () => { session.current.epoch++; }, []);
  const fromList = markets.find(m => m.pair === pair);
  const displayQuote = fromList && (fromList.observed_at ?? 0) > (quote?.observed_at ?? 0) ? fromList : quote;
  const current = verified && usablePlan(proposal, pair, now) && !!displayQuote?.price && displayQuote.price > proposal!.entry!;
  const levels = planLevels(proposal, pair);
  function applyDraft(plan: TradePlan | null, selectedPair: string, valid: boolean) {
    const draft = paperDraft(selectedPair, plan, Date.now(), valid);
    setEntry(draft.entry); setTarget(draft.target); setStop(draft.stop);
  }
  async function refreshProposal(m: PaperMarket) {
    if (!server) return;
    const epoch = ++session.current.epoch;
    setRefreshing(true);
    try {
      const response = await serverJSON(server, '/market?pair=' + encodeURIComponent(m.pair));
      const item = response?.research?.pair === m.pair ? response.research : response?.opportunity?.pair === m.pair ? response.opportunity : null;
      if (!item) throw new Error('Bu coinin analiz yanıtı doğrulanamadı.');
      if (session.current.epoch !== epoch || session.current.pair !== m.pair) return;
      const updated: TradePlan | null = item.trade_plan?.pair === m.pair ? item.trade_plan : null;
      const latest = { ...m, price: item.price ?? m.price, observed_at: item.price_observed_at ?? item.observed_at ?? m.observed_at };
      const checked = marketPlan(updated, m.pair, latest.price);
      setProposal(checked); setQuote(latest); setVerified(true); setError('');
      if (!session.current.dirty) applyDraft(checked, m.pair, true);
    } catch (e: any) {
      if (session.current.epoch !== epoch || session.current.pair !== m.pair) return;
      setVerified(false); setError('Öneri güncellenemedi: ' + String(e.message));
    } finally { if (session.current.epoch === epoch) setRefreshing(false); }
  }
  function choose(m: PaperMarket) {
    session.current = { epoch: session.current.epoch + 1, pair: m.pair, dirty: false };
    const candidate = initialPlan?.pair === m.pair ? initialPlan : m.trade_plan;
    const plan = marketPlan(candidate, m.pair, m.price);
    setPair(m.pair); setQuery(m.pair.replace(/TRY$/, '/TRY')); setQuote(m); setProposal(plan); setVerified(!offline);
    applyDraft(plan, m.pair, !offline); setError('');
    void refreshProposal(m);
  }
  function close() { session.current.epoch++; setRefreshing(false); setVisible(false); }
  function edit(set: (text: string) => void, text: string) { session.current.dirty = true; set(text); }
  return <>
    <TouchableOpacity accessibilityRole="button" accessibilityLabel={initialPair ? initialPair.replace(/TRY$/, '/TRY') + ' aldım gibi kaydet' : 'Aldım gibi kaydet'} style={[styles.button, { marginBottom: 14 }]} onPress={() => {
      session.current.epoch++;
      setSavedMessage('');
      setPair(''); setQuery(''); setEntry(''); setTarget(''); setStop(''); setError('');
      const item = markets.find(m => m.pair === initialPair); if (item) choose(item); setVisible(true);
    }}><Text style={styles.buttonText}>+ Aldım gibi kaydet · Sanal alış</Text></TouchableOpacity>
    {!!savedMessage && <Text accessibilityLiveRegion="polite" style={{ color: TEAL }}>{savedMessage}</Text>}
    <Modal visible={visible} transparent animationType="slide" onRequestClose={() => { if (!saving) close(); }}>
      <View style={styles.overlay}><View style={[styles.dialog, { maxHeight: '92%' }]}><ScrollView keyboardShouldPersistTaps="handled">
        <Text style={styles.section}>SANAL ALIŞ KAYDI</Text>
        <Text style={styles.sub}>Seçtiğin coini aldığını varsayarak şimdi başlayan 48 saatlik takip açılır. Gerçek emir verilmez.</Text>
        <Text style={styles.small}>Bu maliyetten zaten aldığını varsayar; limit alışın dolmasını bekleyen emir açmaz.</Text>
        {!fixedPair && <>
        <TextInput accessibilityLabel="Coin ara" style={styles.input} placeholder="Coin ara: SOL, AVAX…" placeholderTextColor={MUTED}
          autoCapitalize="characters" value={query} onChangeText={text => { session.current.epoch++; setQuery(text); setPair(''); setProposal(null); setQuote(null); setRefreshing(false); }} />
        {!pair && markets.filter(m => m.pair.includes(query.replace(/[^a-z0-9]/gi, '').toUpperCase())).slice(0, 15).map(m =>
          <TouchableOpacity key={m.pair} onPress={() => choose(m)} style={{ paddingVertical: 11, borderBottomWidth: 1, borderColor: '#26384B' }}>
            <Text style={styles.light}>{m.display || m.pair} · ₺{money(m.price)}</Text>
          </TouchableOpacity>)}
        </>}
        {!markets.length && <Text style={styles.muted}>Binance TR parite listesi bekleniyor. Bağlantı geldiğinde seçim açılır.</Text>}
        {!!pair && <>
          <Text style={styles.light}>{pair.replace(/TRY$/, '/TRY')}</Text>
          <Text style={styles.small}>Güncel piyasa fiyatı ₺{money(displayQuote?.price)} · {clock(displayQuote?.observed_at)}</Text>
          <Text style={styles.light}>Önerilen limit alış ₺{money(proposal?.entry)}</Text>
          <Text style={styles.light}>Önerilen hedef ₺{money(proposal?.target)}</Text>
          <Text style={styles.light}>Önerilen stop-loss ₺{money(proposal?.stop)}</Text>
          <Text style={styles.small}>{current ? 'Güncel analiz seviyeleri · ' + clock(proposal?.as_of) : proposal?.reason || (levels ? 'Son analiz seviyeleri; güncellik doğrulanmadı.' : 'Güncel alış/stop analizi bekleniyor. Anlık fiyat alış önerisi olarak doldurulmaz.')}</Text>
          <Text style={styles.small}>{proposal?.note}</Text>
          {!!server && <TouchableOpacity accessibilityRole="button" accessibilityLabel="Alış ve stop önerisini yenile" disabled={refreshing} onPress={() => { if (quote) void refreshProposal(quote); }}>
            <Text style={{ color: TEAL, marginVertical: 10 }}>{refreshing ? 'Alış ve stop önerisi kontrol ediliyor…' : 'Öneriyi yenile'}</Text>
          </TouchableOpacity>}
          {levels && <TouchableOpacity accessibilityRole="button" accessibilityLabel={current ? 'Güncel öneriyi alanlara uygula' : 'Son analiz seviyelerini manuel taslağa al'} onPress={() => {
            session.current.dirty = true;
            setEntry(String(proposal!.entry)); setTarget(String(proposal!.target)); setStop(String(proposal!.stop));
          }}><Text style={{ color: TEAL }}>{current ? 'Güncel öneriyi alanlara uygula' : 'Son analiz seviyelerini manuel taslağa al · güncel öneri değildir'}</Text></TouchableOpacity>}
          <Text style={[styles.muted, { marginTop: 12 }]}>Alış maliyetin (TL)</Text>
          <TextInput accessibilityLabel="Sanal alış fiyatı" style={styles.input} keyboardType="decimal-pad" value={entry} onChangeText={text => edit(setEntry, text)} />
          <Text style={styles.muted}>Hedef satış (TL)</Text>
          <TextInput accessibilityLabel="Hedef satış fiyatı" style={styles.input} keyboardType="decimal-pad" value={target} onChangeText={text => edit(setTarget, text)} />
          <Text style={styles.small}>{current ? 'Bu coinin güncel alış, hedef ve stop seviyeleri gösteriliyor. Manuel değerlerini güncelleme sırasında değiştirmeyiz.' : 'Alış/hedef/stop alanları manuel taslaktır. Güncel öneri yoksa fiyatları kendin gir veya son analizi açıkça seç.'}</Text>
          <Text style={[styles.muted, { marginTop: 12 }]}>Stop-loss fiyatın (TL, isteğe bağlı)</Text>
          <TextInput accessibilityLabel="İsteğe bağlı stop" style={styles.input} keyboardType="decimal-pad" value={stop} onChangeText={text => edit(setStop, text)} placeholder="Boş bırakılabilir" placeholderTextColor={MUTED} />
          <Text style={styles.sub}>{server === 'phone://local' ? 'Kayıt telefon motorunda tutulur. Android arka plan işlerine izin verdiğinde hedef, stop ve stop sonrası toparlanma takip edilir.' : server ? 'Kayıt sunucuda tutulur; telefon kapalıyken sunucu takip eder.' :
            'Kayıt telefonda saklanır. Uygulama kapalıyken canlı takip durur; açılınca işlem geçmişi doğrulanır. Veri bulunamazsa sonuç bekletilir.'}</Text>
          <Text style={styles.small}>Komisyon varsayımı: toplam %0,20. Spread ve kayma bu manuel kayıtta hesaplanmaz.</Text>
          <TouchableOpacity disabled={saving || !entry.trim() || !target.trim()} style={[styles.button, { marginTop: 12, opacity: saving || !entry.trim() || !target.trim() ? .5 : 1 }]} onPress={async () => {
            if (!entry.trim() || !target.trim()) { setError('Alış ve hedef fiyatını gir.'); return; }
            setSaving(true); setError('');
            try { await addPaperPosition(pair, number(entry), number(target), stop.trim() ? number(stop) : null, server); close(); setSavedMessage(pair.replace(/TRY$/, '/TRY') + ' kaydedildi · Aktif sekmesinde takip ediliyor.'); }
            catch (e: any) { setError(e.message); }
            finally { setSaving(false); }
          }}><Text style={styles.buttonText}>{saving ? 'Kaydediliyor…' : 'Sanal alışı kaydet'}</Text></TouchableOpacity>
        </>}
        {!!error && <Text style={{ color: RED, marginTop: 10 }}>{error}</Text>}
        <TouchableOpacity disabled={saving} onPress={close}><Text style={[styles.muted, { textAlign: 'center', marginTop: 18 }]}>Kapat</Text></TouchableOpacity>
      </ScrollView></View></View>
    </Modal>
  </>;
}
