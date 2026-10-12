import React, { useEffect, useState } from 'react';
import { Text, TouchableOpacity, View } from 'react-native';
import { clock, Metric, money, pct, RED, styles, TEAL } from '../../App';
import { serverJSON } from '../lib/api';
import type { LabItem, LabSnapshot } from '../lib/lab';
import { PaperAdd } from './PaperControls';
import { priceAge } from '../lib/livePrices';
import { usablePlan } from '../lib/paperDraft';

const issueLabels: Record<string, string> = {
  WORKER_STALE: 'Analiz gecikmiş', CANDLES_BEHIND: 'Mum verisi geride', BACKFILL_INCOMPLETE: 'Geçmiş veri tamamlanıyor',
  LAST_JOB_FAILED: 'Son iş başarısız', EXPIRED_LEASE: 'Yarım kalan iş', RESEARCH_DUE: 'Model değerlendirmesi gerekli',
  SCHEDULER_STALE: 'Zamanlayıcı gecikmiş', PAPER_TRADE_GAP: 'Sanal işlem verisinde boşluk',
  ENTRY_RESEARCH_MISSING: 'Alış araştırması başlatılmalı', ENTRY_RESEARCH_DUE: 'Alış araştırması güncellenmeli',
  STOP_RESEARCH_MISSING: 'Stop araştırması başlatılmalı', STOP_RESEARCH_DUE: 'Stop araştırması güncellenmeli',
  PHONE_PAUSED: 'Telefon motoru duraklatılmış', PHONE_SCHEDULER_MISSING: 'Telefon zamanlaması doğrulanmadı',
  DRIVE_NOT_SELECTED: 'Drive yedek dosyası seçilmedi', DRIVE_BACKUP_FAILED: 'Drive yedek teslimi başarısız', DRIVE_BACKUP_STALE: 'Drive yedeği gecikmiş',
  DRIVE_BACKUP_PENDING: 'İlk Drive yedek teslimi bekleniyor',
  MARKET_DISCOVERY_PENDING: 'Tüm coinlerin listesi alınıyor', MARKET_DISCOVERY_FAILED: 'Coin listesi/fiyat bağlantısı başarısız; kayıtlı veriler korunuyor',
  PRICE_FEED_PENDING: 'İlk canlı fiyat bekleniyor', PRICE_FEED_STALE: 'Canlı fiyat akışı gecikmiş', PRICE_STREAM_PAUSED: 'Hızlı fiyat akışı duraklatılmış',
  RALLY_PENDING: 'Rally puanı için veri toplanıyor', RALLY_STALE: 'Rally puanı güncellenmeli', RALLY_FAILED: 'Rally puanı verisi alınamadı',
};
const stageLabels: Record<string, string> = {
  acquisition: 'Veri toplama', features: 'Gösterge hesaplama', labels: 'Sonuç ölçümü',
  research: 'Model değerlendirmesi', forward_monitor: 'Tahmin takibi', paper_tracking: 'Sanal işlem takibi', publication: 'Sonuçların hazırlanması',
  entry_research: 'Alış seviyesi araştırması', stop_research: 'Stop mesafesi araştırması', stop_forward_monitor: 'İleriye dönük stop takibi',
};
const featureLabels: Record<string, string> = {
  return_1: '1 saat getiri', return_3: '3 saat getiri', return_6: '6 saat getiri', return_24: '24 saat getiri',
  trend_12: '12 saat trend', trend_48: '48 saat trend', volatility_24: '24 saat oynaklık',
  range_compression: 'Fiyat aralığı sıkışması', volume_ratio: 'Hacim oranı', volume_z: 'Hacim sapması',
  taker_share: 'Agresif alış payı', body: 'Mum gövdesi', upper_wick: 'Üst fitil', lower_wick: 'Alt fitil',
  range_position: 'Kapanışın mumdaki konumu', drawdown_48: '48 saat zirveden düşüş', breakout_distance: 'Önceki zirveye uzaklık',
  return_autocorr: 'Getiri otokorelasyonu', volume_price_corr: 'Hacim/fiyat korelasyonu', return_skew: 'Getiri çarpıklığı',
  rsi_14: 'RSI 14', amihud_24: 'Hacme göre fiyat etkisi',
};
function issueLabel(code: string) {
  if (issueLabels[code]) return issueLabels[code];
  const [stage, status] = code.split(':');
  return `${stageLabels[stage] || 'İşlem'} · ${status === 'FAILED' ? 'yeniden değerlendirme gerekli' : 'tamamlanması bekleniyor'}`;
}
function featureLabel(value: string) {
  return value.replace(/([a-z0-9_]+)@lag(\d+)/g, (_, name: string, lag: string) =>
    `${featureLabels[name] || name} (${lag === '0' ? 'gözlem anı' : lag + ' saat önce'})`);
}
const statusLabel = (value: string) => ({
  SUPPORTED: 'Kanıt eşiğini geçti', UNDERPOWERED: 'Örnek sayısı yetersiz', NOT_SUPPORTED: 'Kanıt eşiğini geçmedi',
  BASELINE_ONLY: 'Tarihsel referans', MODEL_ESTIMATE: 'Model tahmini', DRIFT_PAUSED: 'Dağılım değişimi: referansa dönüldü',
  PROMOTED: 'Yeni model seçildi', RETAINED: 'Mevcut model korundu', INSUFFICIENT_DATA: 'Veri yetersiz', SINGLE_CLASS: 'Sonuç çeşitliliği yetersiz',
  OPTIMIZER_FAILED: 'Model eğitimi yeniden değerlendirilmeli',
  HISTORICAL_SUPPORT: 'Tarihsel kanıt eşiğini geçti', FORWARD_CAUTION: 'İleriye dönük ölçümde bozulma uyarısı',
}[value] || value);

export function BackendControl({ snapshot, base, offline, onConfigure, phoneAvailable = false }: {
  snapshot: LabSnapshot | null; base: string; offline: boolean; onConfigure: () => void; phoneAvailable?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15000);
    return () => clearInterval(timer);
  }, []);
  const audit = snapshot?.audit;
  const old = !audit || now - audit.checked_at > 15 * 60_000 || offline;
  const healthy = audit?.ok && !old;
  async function reconcile() {
    if (!base) {
      if (phoneAvailable) {
        setMessage('Telefon motorunu üstteki karttan başlat. Ardından bu düğme telefondaki işleri kontrol eder ve eksikleri kuyruğa alır.');
        return;
      }
      setMessage('Backend bağlı değil. İşlemleri kontrol etmek için yayınlanmış backend adresini ve erişim anahtarını gir.');
      onConfigure();
      return;
    }
    setBusy(true); setMessage('');
    try {
      const health = await serverJSON(base, '/health');
      if (!health.research_backend) {
        setMessage('Bağlı sunucu araştırma ve kurtarma özelliğini desteklemiyor. Yeni araştırma backend’ini bağla.');
        return;
      }
      const result = await serverJSON(base, '/v2/control/reconcile', { method: 'POST', body: '{}' });
      setMessage(result.message);
    } catch (error: unknown) { setMessage(error instanceof Error ? error.message : String(error)); }
    finally { setBusy(false); }
  }
  return <View style={styles.card}>
      <Text style={[styles.section, { color: healthy ? TEAL : RED }]}>
        {!base ? 'BACKEND BAĞLANTISI YOK' : healthy ? 'İŞLEMLER GÜNCEL' : old ? 'GÜNCEL DURUM DOĞRULANMADI' : 'EKSİK İŞLEMLER VAR'}
      </Text>
      <Text style={styles.muted}>{!base
        ? phoneAvailable ? 'Telefon motorunu üstteki karttan başlatabilir veya bulut backend’i bağlayabilirsin. Güncellik burada doğrulanır.' : 'Cihaz radarı uygulama açıkken çalışır. Uygulama kapalıyken veri toplama ve stop-loss araştırması için bulut backend’i bağlanmalı.'
        : !snapshot ? 'Backend’in güncel durumu henüz doğrulanmadı. Kontrol düğmesi bağlantıyı denetler ve destekleniyorsa eksik işleri başlatır.'
        : snapshot.runtime === 'PHONE'
        ? 'Telefon motoru, Android izin verdiğinde uygulama kapalıyken de araştırır. Ağ ve pil kısıtları nedeniyle güncellik bu kartta doğrulanır.'
        : snapshot.runtime === 'LOCAL'
        ? 'Yerel backend bilgisayar ve işlem açıkken çalışır. Sunucusuz süreklilik bulut dağıtımıyla etkinleşir.'
        : 'Uygulama kapalıyken bulut zamanlayıcısı veri toplar ve modeli değerlendirir.'}</Text>
      <Text style={styles.small}>Son kontrol: {clock(audit?.checked_at)}</Text>
      {(audit?.issues || []).slice(0, 8).map((issue, index) => <Text key={index} style={styles.muted}>
        {issue.pair ? issue.pair + ' · ' : ''}{issueLabel(issue.code)}
      </Text>)}
      <TouchableOpacity accessibilityRole="button" accessibilityLabel="Tüm işlemleri kontrol et ve eksikleri uyandır"
        disabled={busy} style={[styles.button, { marginTop: 12, opacity: busy ? .6 : 1 }]} onPress={reconcile}>
        <Text style={styles.buttonText}>{busy ? 'Kontrol ediliyor…' : 'Kontrol et ve eksikleri uyandır'}</Text>
      </TouchableOpacity>
      {!!message && <Text accessibilityLiveRegion="polite" style={styles.muted}>{message}</Text>}
      {base !== 'phone://local' && <TouchableOpacity accessibilityRole="button" onPress={onConfigure} style={{ marginTop: 12 }}>
        <Text style={{ color: TEAL, fontWeight: '700' }}>{phoneAvailable ? 'Bulut backend bağlantısını düzenle ›' : base ? 'Backend bağlantısını düzenle ›' : 'Backend’i bağla ›'}</Text>
      </TouchableOpacity>}
      {!!base && <Text style={styles.small}>Kuyruğa alınan işlerin sonucu sonraki güncellemede doğrulanır.</Text>}
  </View>;
}

function entryPolicyLabel(policy?: string) {
  if (!policy) return 'ATR ön adayı';
  const [kind, amount] = policy.split(':');
  return kind === 'pct' ? '%' + (Number(amount)*100).toFixed(1) + ' geri çekilme' :
    kind === 'atr' ? amount + ' × ATR geri çekilme' : kind === 'support' ? 'Son 24 saat desteği' : '24 saat hacim ağırlıklı fiyat';
}
const entryNet = (value: number | null | undefined) => value == null ? '—' : '%' + (value*100).toFixed(2);

const scorePoints = (value: number) => (value >= 0 ? '+' : '') + value.toFixed(1);
const scoreReasons: Record<string, string> = {
  MARKET_STALE: 'Piyasa verisi güncel değil', HISTORY_PENDING: 'Geçmiş verinin tamamlanması bekleniyor',
  DEMO_EVIDENCE: 'Demo verisi puana katılmaz', RESEARCH_CANDLES_BEHIND: 'Araştırmanın son mumu güncellenmeli',
  MODEL_EVIDENCE_PENDING: 'Yön modeli ve ileriye dönük kanıt bekleniyor', MODEL_RESEARCH_STALE: 'Yön araştırması yenilenmeli',
  MODEL_DRIFT: 'Model sonuçlarında bozulma; katkı durdu', MODEL_TRAINING_FAILED: 'Yön modeli yeniden değerlendirilmeli',
  HISTORICAL_MODEL_PENDING: 'Bu model sürümünün ayrı test kanıtı bekleniyor', FORECAST_STALE: 'Yön tahmini güncel değil',
  FORWARD_SAMPLES_PENDING: 'İleriye dönük örnek sayısı yetersiz', FORWARD_RESULTS_STALE: 'Yeni ileriye dönük sonuç bekleniyor',
  FORWARD_QUALITY_FAILED: 'İleriye dönük doğruluk/kalibrasyon yetersiz; katkı durdu',
  HISTORICAL_AND_FORWARD_SUPPORT: 'Ayrı test ve ileriye dönük sonuçlar yeterli',
  ENTRY_EVIDENCE_PENDING: 'Alış adayı için kanıt bekleniyor', ENTRY_NO_CLEAR_ADVANTAGE: 'Alış adayında belirgin üstünlük yok',
  ENTRY_PLAN_NOT_CURRENT: 'Alış planı güncel değil; katkı durdu', ENTRY_HISTORICAL_SUPPORT: 'Alış adayı ayrı testte desteklendi',
  ENTRY_HISTORICAL_LOSS: 'Alış adayı ayrı testte olumsuz sonuç verdi', STOP_EVIDENCE_PENDING: 'Stop için ayrı test ve ileriye dönük kanıt bekleniyor',
  STOP_FORWARD_GAP: 'Stop takibinde veri boşluğu; katkı durdu', STOP_HISTORICAL_AND_FORWARD_SUPPORT: 'Stop karşılaştırması ve ileriye dönük sonuçları yeterli',
};

export function ResearchCard({ item, offline, onOpen, server = '' }: { item: LabItem; offline: boolean; onOpen?: () => void; server?: string }) {
  const [expanded, setExpanded] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15000);
    return () => clearInterval(timer);
  }, []);
      const outdated = offline || item.stale || now - item.as_of > 15*60_000;
      const design = item.research.design;
      const plan = item.trade_plan;
      const entries = plan?.entry_research;
      const scoreAnalysis = item.rally?.analysis;
      const overlay = scoreAnalysis?.research_overlay;
      const planReady = !offline && usablePlan(plan, item.pair, now);
      return <View key={item.pair} style={styles.card}>
        <Text style={styles.small}>{item.source === 'SYNTHETIC_DEMO' ? 'SENTETİK DEMO · CANLI VERİ DEĞİL' : item.source}</Text>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel={item.display + ' analizini aç'} onPress={() => setExpanded(value => !value)}>
          <View style={styles.row}>
            <View style={{ flex: 1 }}><Text style={styles.pair}>{item.display}</Text><Text style={styles.muted}>₺{money(item.price)}</Text><Text style={styles.small}>{priceAge(item.price_observed_at ?? item.observed_at, Math.max(now, item.price_clock_at ?? 0))}</Text></View>
            <Text style={styles.score}>{item.rally_score ?? '—'}<Text style={styles.scoreSmall}> / 100</Text></Text>
          </View>
          <Text style={styles.small}>Rally Score · {offline || item.rally?.status === 'STALE' || (item.rally?.as_of && now - item.rally.as_of > 15*60_000) ? 'son puan · güncellik doğrulanmadı' : item.rally?.status === 'CURRENT' ? 'güncel puan' : 'puan için veri toplanıyor'} · {clock(item.rally?.as_of)}</Text>
          {overlay && <>
            <Text style={styles.light}>Temel puan {scoreAnalysis?.base_score ?? '—'} · araştırma etkisi {scorePoints(scoreAnalysis?.research_adjustment ?? 0)} puan</Text>
            <Text style={styles.small}>{offline || item.rally?.status !== 'CURRENT' ? 'Son araştırma katkısı · güncellik doğrulanmadı' : overlay.status === 'ACTIVE' ? 'Olgun araştırma puana katılıyor' : overlay.status === 'PAUSED' ? 'Araştırma katkısı duraklatıldı' : 'Araştırma olgunlaşıyor · temel puan korunuyor'}</Text>
          </>}
          <Text style={{ color: TEAL, marginTop: 8 }}>{expanded ? 'Analizi kapat ‹' : 'Araştırma ve stop-loss detayları ›'}</Text>
        </TouchableOpacity>
        <View style={{ marginTop: 12 }}>
          <Metric label="Önerilen alış" value={'₺' + money(plan?.entry)} />
          <Metric label="Önerilen hedef" value={'₺' + money(plan?.target)} />
          <Metric label="Önerilen stop-loss" value={'₺' + money(plan?.stop)} />
          <Text style={styles.small}>{plan?.entry == null ? plan?.reason || 'Fiyat planı için piyasa verisi bekleniyor.' :
            (planReady ? 'Güncel alış adayı' : plan?.reason || 'Son plan · güncelleme gerekli') + ' · ' + (plan?.stop_basis === 'RESEARCH_DISTANCE_ADAPTED' ? 'Stop araştırması mesafesi alışa uyarlandı' : 'ATR stop adayı') + ' · ' + clock(plan?.as_of)}</Text>
          {plan?.entry != null && <>
            <Text style={styles.light}>Alış seviyesi araştırması · {entryPolicyLabel(plan.entry_policy_id)}</Text>
            <Text style={styles.small}>Analiz fiyatı ₺{money(plan.anchor_price)} · {clock(plan.closed_at)}. Alış seviyesi yeni kapalı mum veya araştırma sonucunda değişir.</Text>
            <Text style={styles.small}>Güncel fiyata mesafe %{((plan.quote_distance_fraction ?? plan.discount_fraction ?? 0)*100).toFixed(2)} · stop %{((plan.stop_fraction ?? 0)*100).toFixed(2)} · kazanç/risk {plan.risk_reward?.toFixed(2)}</Text>
            {entries?.policy_id ? <>
              <Text style={styles.small}>{entries.candidates.length} alış adayı · {entries.status === 'HISTORICAL_SUPPORT' ? 'Tarihsel destek var; gelecek doğrulanmadı' : entries.status === 'NOT_SUPPORTED' ? 'Alış üstünlüğü desteklenmedi; ön aday' : 'Kanıt için veri toplanıyor; ön aday'}</Text>
              <Text style={styles.small}>Ayrı test: {entries.evaluation_samples} tekrar · etkin {entries.effective_samples.toFixed(0)} / gereken {entries.required_samples}. Seçim: {entries.validation_samples} önceki dönem.</Text>
              <Text style={styles.small}>Dolum {entryNet(entries.test.fill_rate)} · karar başına net {entryNet(entries.test.net.mean)} · hemen alışa fark {entryNet(entries.test.gain.mean)}</Text>
            </> : <Text style={styles.small}>Geçmiş alış karşılaştırması hazırlanıyor; ATR ön adayı optimum kabul edilmez.</Text>}
            <Text style={styles.small}>{plan.note || 'Ön plan; kârlılık doğrulanmadı.'}</Text>
          </>}
          <PaperAdd markets={[{ pair: item.pair, display: item.display, price: item.price, observed_at: item.price_observed_at ?? item.observed_at }]}
            server={server} initialPair={item.pair} initialPlan={plan} offline={offline} fixedPair />
        </View>
        {expanded && <>
        {entries?.policy_id && <View style={{ marginTop: 14 }}>
          <Text style={styles.section}>ALIŞ ADAYLARININ KARŞILAŞTIRMASI</Text>
          <Text style={styles.small}>6 saat dolum bekleme · dolumdan sonra 48 saat takip. Seçim önceki doğrulama dönemindeki kötümser net sonuca göre yapılır; ayrı test dönemine bakarak aday seçilmez.</Text>
          {entries.candidates.map(candidate => <View key={candidate.policy_id} style={{ marginVertical: 5 }}>
            <Text style={styles.light}>{candidate.policy_id === entries.policy_id ? '✓ ' : ''}{entryPolicyLabel(candidate.policy_id)}</Text>
            <Text style={styles.small}>Seçim dönemi net {entryNet(candidate.validation.net.mean)} · ayrı test net {entryNet(candidate.test.net.mean)} · dolum {entryNet(candidate.test.fill_rate)}</Text>
            <Text style={styles.small}>Test hedef {candidate.test.targets} · stop {candidate.test.stops} · belirsiz {candidate.test.ambiguous}</Text>
          </View>)}
          <Text style={styles.small}>Seçilen aday için net aralık {entryNet(entries.test.net.interval[0])} – {entryNet(entries.test.net.interval[1])} · son araştırma {clock(entries.trained_at)}</Text>
          <Text style={styles.muted}>{entries.warning}</Text>
        </View>}
        <Text style={styles.small}>Temel Rally Score trend/hacim/sıkışma/emir defteri/BTC hesabını korur. Yeterli kanıtı olan araştırmalar puanı en fazla ±20 değiştirir; bu puan başarı olasılığı değildir.</Text>
        {overlay && <View style={{ marginTop: 12 }}>
          <Text style={styles.section}>ARAŞTIRMANIN PUANA KATKISI</Text>
          {overlay.families.map(family => <View key={family.family} style={{ marginVertical: 6 }}>
            <Text style={styles.light}>{({ direction: 'Yön modeli', entry: 'Alış adayı', stop: 'Stop araştırması' })[family.family]} · {scorePoints(family.points)} puan</Text>
            <Text style={styles.small}>{scoreReasons[family.reason] || 'Kanıt durumu değerlendiriliyor'}</Text>
            {family.required_samples != null && <Text style={styles.small}>Etkin {family.effective_samples?.toFixed(0) ?? '0'} / gereken {family.required_samples}</Text>}
            {family.streams?.map(stream => <Text key={stream.horizon_hours + ':' + stream.direction} style={styles.small}>{stream.horizon_hours}s {stream.direction === 'up' ? 'yükseliş' : 'düşüş'} · etkin {stream.effective_samples.toFixed(0)} / {stream.required_samples} · {scoreReasons[stream.reason] || 'Kanıt bekleniyor'}</Text>)}
          </View>)}
          <Text style={styles.small}>Katkılar yuvarlanır; toplam puan 0–100 arasında kalır. Veri güncelliği veya kanıt bozulursa ilgili katkı kaldırılır.</Text>
          <Text style={styles.small}>{overlay.warning}</Text>
        </View>}
        {!!item.rally?.analysis.components && <View style={{ marginTop: 8 }}>{Object.entries(item.rally.analysis.components).map(([key, value]) =>
          <Metric key={key} label={({ trend: 'Trend', early: 'Erken hareket', volume: 'Hacim', compression: 'Sıkışma', flow: 'Emir defteri', breadth: 'Bitcoin hareketi' } as Record<string, string>)[key] || key} value={value.toFixed(1) + ' / 100'} />)}</View>}
        {!!item.rally?.error && <Text style={styles.muted}>{item.rally.error}</Text>}
        <Text style={[styles.small, { color: outdated ? RED : TEAL }]}>
          {outdated ? 'Son kayıt · güncellik doğrulanmadı' : 'Son kapalı saatlik mum'} · {clock(item.observed_at)}
        </Text>
        <Text style={styles.muted}>{item.bars} kapalı mum · {item.backfill_complete ? 'Geçmiş veri tamam' : 'Geçmiş veri toplanıyor'}</Text>
        {item.forecasts.map(forecast => <View key={forecast.id} style={{ marginTop: 10 }}>
          <Text style={styles.light}>{forecast.horizon_hours} saat · {forecast.direction === 'up' ? 'Yükseliş' : 'Düşüş'} {pct(forecast.probability)}</Text>
          <Text style={styles.small}>Hareket eşiği ±{(forecast.threshold*100).toFixed(2)}% · {statusLabel(forecast.status)}</Text>
        </View>)}
        {!item.forecasts.length && <Text style={styles.muted}>Tahmin için veri ve değerlendirme bekleniyor.</Text>}
        {item.stop_loss && <View style={{ marginTop: 16 }}>
          <Text style={styles.section}>STOP-LOSS MESAFESİ ARAŞTIRMASI</Text>
          <Text style={styles.small}>Yüzde ve ATR adayları; oynaklık/hacim rejimine göre aynı hedef ve sürede karşılaştırılır.</Text>
          {item.stop_loss.suggestions.map(stop => <View key={stop.horizon_hours} style={{ marginVertical: 8 }}>
            <Text style={styles.light}>{stop.horizon_hours}s · aday stop ₺{money(stop.stop_price)} · %{stop.distance_pct.toFixed(2)}</Text>
            <Text style={styles.small}>{stop.policy_id.startsWith('atr:') ? stop.policy_id.slice(4) + ' × ATR' : 'Sabit yüzde'} · {statusLabel(stop.status)}</Text>
            <Text style={styles.small}>Birlikte değerlendirilen hedef ₺{money(stop.target_price)} · kazanç/risk {stop.risk_reward.toFixed(2)}</Text>
            <Text style={styles.small}>Tarihsel etkin n {stop.effective_samples.toFixed(0)} · gereken {stop.required_samples}</Text>
            <Text style={styles.small}>İleriye dönük {stop.forward_samples} · etkin {stop.forward_effective_samples.toFixed(0)}</Text>
            {stop.historical_metrics.net_return_bounds && <Text style={styles.small}>Tarihsel sanal ortalama net %{(stop.historical_metrics.net_return_bounds[0] * 100).toFixed(2)}–%{(stop.historical_metrics.net_return_bounds[1] * 100).toFixed(2)}</Text>}
            <Text style={styles.small}>Belirsiz temas {stop.historical_metrics.ambiguous ?? 0} · stop sonrası hedefe dönen {stop.historical_metrics.recovered_after_stop ?? 0}. Karşılaştırmalı iyileşme kârlılık demek değildir.</Text>
          </View>)}
          {!item.stop_loss.suggestions.length && <Text style={styles.muted}>Stop adayı için ayrı eğitim ve değerlendirme verisi toplanıyor.</Text>}
          <Text style={styles.small}>Sonucu beklenen gölge deneme {item.stop_loss.forward.pending ?? 0} · veri boşluğu {item.stop_loss.forward.data_gaps ?? 0}</Text>
          <Text style={styles.small}>Manuel stop {item.stop_loss.manual.stopped ?? 0} · sonrasında hedefe dönen {item.stop_loss.manual.recovered_to_target ?? 0} · takipte {item.stop_loss.manual.followup_pending ?? 0}</Text>
          <Text style={styles.muted}>{item.stop_loss.warning}</Text>
        </View>}
        <Text style={[styles.section, { marginTop: 14 }]}>GRAFİĞİN İSTATİSTİKSEL YORUMU</Text>
        {(item.interpretation || []).map((line, index) => <Text key={index} style={styles.muted}>{line}</Text>)}
        {!!design && <View style={{ marginTop: 14 }}>
          <Text style={styles.light}>Tekrar sayısı bilimsel hedeflere göre belirlenir</Text>
          <Text style={styles.small}>Güç {pct(design.power)} · güven {pct(design.confidence)} · hedef fark {(design.minimum_detectable_lift*100).toFixed(0)} yüzde puan</Text>
          <Text style={styles.small}>Tek karşılaştırma için grup başına {design.unadjusted_required_per_group} bağımsız örnek; çoklu araştırmada gereken sayı daha yüksektir.</Text>
        </View>}
        <Text style={[styles.section, { marginTop: 16 }]}>ÖNCÜ BELİRTİ ARAŞTIRMASI</Text>
        {item.hypotheses.slice(0, 5).map((hypothesis, index) => <View key={index} style={{ marginBottom: 12 }}>
          <Text style={styles.light}>{featureLabel(hypothesis.feature)}</Text>
          <Text style={styles.small}>{hypothesis.horizon_hours}s {hypothesis.direction === 'up' ? 'yükseliş' : 'düşüş'} · {statusLabel(hypothesis.status)}</Text>
          <Text style={styles.small}>Tekrar {hypothesis.repeats} · etkin {hypothesis.effective_repeats.toFixed(0)} · gereken/grup {hypothesis.required_per_group}</Text>
          <Text style={styles.small}>Koşullu oran {pct(hypothesis.rate)} · kontrol {pct(hypothesis.control_rate)} · fark {(hypothesis.lift*100).toFixed(1)} puan</Text>
          <Text style={styles.small}>%95 aralık {pct(hypothesis.interval_95[0])}–{pct(hypothesis.interval_95[1])} · düzeltilmiş p {hypothesis.adjusted_p.toPrecision(3)}</Text>
        </View>)}
        <Text style={styles.small}>{item.hypothesis_count} hipotez · araştırma turu {item.research.round ?? '—'}. İlişki, nedensellik veya kesin kazanç değildir.</Text>
        {onOpen && <TouchableOpacity accessibilityRole="button" onPress={onOpen}><Text style={{ color: TEAL, marginTop: 12 }}>Grafik ve manuel takibi aç ›</Text></TouchableOpacity>}
        </>}
      </View>;
}

export function ResearchPanel({ snapshot, offline }: { snapshot: LabSnapshot | null; offline: boolean }) {
  return <View>
    {(snapshot?.items || []).map(item => <ResearchCard key={item.pair} item={item} offline={offline} server={snapshot?.runtime === 'PHONE' ? 'phone://local' : ''} />)}
    {!snapshot?.items.length && <Text style={styles.muted}>İlk veri toplama bekleniyor. Kontrol düğmesi eksik işleri başlatabilir.</Text>}
  </View>;
}

export function ResearchPerformance({ snapshot }: { snapshot: LabSnapshot | null }) {
  return <View><Text style={styles.sub}>Son kayıt: {clock(snapshot?.as_of)}</Text>{(snapshot?.items || []).map(item => <View key={item.pair} style={styles.card}>
    <Text style={styles.section}>{item.display} · İLERİYE DÖNÜK ÖLÇÜM</Text>
    <Metric label="Sonuçlanan tahmin" value={item.monitor.settled ?? 0} />
    <Metric label="Sonucu beklenen" value={item.monitor.pending ?? 0} />
    <Metric label="Dağılım değişimi uyarısı" value={item.monitor.drift ? 'Var' : 'Yok'} />
    {(item.monitor.streams || []).map(stream => <View key={`${stream.horizon_hours}:${stream.direction}`}>
      <Text style={styles.light}>{stream.horizon_hours}s · {stream.direction === 'up' ? 'Yükseliş' : 'Düşüş'} · n={stream.calibration.n}</Text>
      <Text style={styles.muted}>Brier {stream.calibration.brier?.toFixed(4)} · referans {stream.baseline_brier.toFixed(4)} · ECE {stream.calibration.ece?.toFixed(4)}</Text>
    </View>)}
    <Text style={[styles.section, { marginTop: 14 }]}>AYRI TARİHSEL TEST</Text>
    {(item.research.evaluations || []).map((result, index) => <View key={index} style={{ marginBottom: 8 }}>
      <Text style={styles.light}>{result.horizon_hours}s · {result.direction === 'down' ? 'Düşüş' : 'Yükseliş'} · {statusLabel(result.status)}</Text>
      <Text style={styles.small}>Test n={result.test_samples ?? 0} · etkin n={result.effective_test_samples?.toFixed(0) ?? '—'} · Brier {result.calibration?.brier?.toFixed(4) ?? '—'}</Text>
    </View>)}
    <Text style={styles.small}>Brier ve ECE küçüldükçe olasılık hatası azalır. Tarihsel test ve canlı tahmin sonuçları ayrı tutulur.</Text>
  </View>)}</View>;
}
