import { useEffect, useState } from 'react';
import { AppState } from 'react-native';
import type { ChartData, Opportunity } from '../../App';

// The device radar is a public-market observer, not an entry/target model.
// Orders, probabilities and permanent paper outcomes require the backend.
const REST = 'https://api.binance.me';
const SYMBOLS = 'https://www.binance.tr/open/v1/common/symbols';
const WS = 'wss://stream-cloud.binance.tr/ws/!miniTicker@arr';
const STABLE = new Set(['USDT', 'USDC', 'FDUSD', 'TUSD', 'DAI', 'EUR']);
type Ticker = { price: number; change: number; volume: number; time: number };
type Bar = { time: number; close: number; high: number; low: number; quote: number };
type Snapshot = {
  opportunities: Opportunity[]; pairs: number; connected: boolean;
  markets: { pair: string; display: string; price: number | null; observed_at: number | null }[];
  error: string; lastScan: number; scanning: boolean;
};
const tickers = new Map<string, Ticker>();
const symbols = new Map<string, { coin: string; display: string }>();
const scored = new Map<string, Opportunity>();
const charts = new Map<string, ChartData>();
const listeners = new Set<() => void>();
let snapshot: Snapshot = { opportunities: [], markets: [], pairs: 0, connected: false,
  error: '', lastScan: 0, scanning: false };
let socket: WebSocket | null = null;
let stopRadar: (() => void) | null = null;
let stopTimer: ReturnType<typeof setTimeout> | undefined;
let backoffUntil = 0;
let generation = 0;
const mean = (values: number[]) => values.reduce((a, b) => a + b, 0) / Math.max(1, values.length);
const clamp = (x: number) => Math.max(0, Math.min(1, x));

async function marketJSON(url: string) {
  if (Date.now() < backoffUntil) throw new Error('Borsa hız sınırı; yeniden bağlantı bekleniyor.');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if ([418, 429].includes(response.status)) {
      backoffUntil = Date.now() + Math.max(60, Number(response.headers.get('Retry-After')) || 60) * 1000;
    }
    if (!response.ok) throw new Error(`Binance TR bağlantısı: HTTP ${response.status}`);
    const body = await response.json();
    if (!Array.isArray(body) && body.code != null && body.code !== 0) {
      throw new Error('Binance TR piyasa verisi alınamadı.');
    }
    return body.data ?? body;
  } finally { clearTimeout(timer); }
}

function closedBars(raw: any[], observedTime: number): Bar[] {
  if (!Array.isArray(raw)) throw new Error('Mum verisi okunamadı.');
  return raw.filter(k => Number(k[6]) < observedTime).map(k => ({
    time: Number(k[0]), close: Number(k[4]), high: Number(k[2]),
    low: Number(k[3]), quote: Number(k[7]),
  })).filter(b => [b.close, b.high, b.low, b.quote].every(Number.isFinite) && b.close > 0);
}

function publish() {
  const liquid = [...tickers].filter(([pair, t]) => symbols.has(pair) && t.volume >= 1_000_000);
  const candidates = liquid.map(([pair, t]) => {
    const meta = symbols.get(pair)!;
    const analysis = scored.get(pair);
    const recent = analysis && Date.now() - analysis.observed_at < 15 * 60000 ? analysis : null;
    return { pair, coin: meta.coin, display: meta.display, price: t.price,
      rally_score: recent?.rally_score ?? null, state: recent?.state ?? 'WATCH',
      spread_pct: recent?.spread_pct ?? 0, scenario: null,
      features: { ...recent?.features, change_24h: t.change, quote_volume_24h: t.volume,
        device_only: true }, observed_at: recent?.observed_at ?? t.time,
      price_observed_at: t.time } satisfies Opportunity;
  });
  candidates.sort((a, b) => (b.rally_score ?? -1) - (a.rally_score ?? -1) ||
    b.features.quote_volume_24h - a.features.quote_volume_24h);
  snapshot = { ...snapshot, pairs: liquid.length, opportunities: candidates.slice(0, 10),
    markets: [...symbols].map(([pair, meta]) => ({ pair, display: meta.display,
      price: tickers.get(pair)?.price ?? null, observed_at: tickers.get(pair)?.time ?? null }))
      .sort((a, b) => a.pair.localeCompare(b.pair)) };
  listeners.forEach(listener => listener());
}

async function analyse(pair: string, token: number) {
  const ticker = tickers.get(pair), meta = symbols.get(pair);
  if (!ticker || !meta) return;
  const [hourRaw, minuteRaw, book] = await Promise.all([
    marketJSON(`${REST}/api/v1/klines?symbol=${pair}&interval=1h&limit=120`),
    marketJSON(`${REST}/api/v1/klines?symbol=${pair}&interval=1m&limit=121`),
    marketJSON(`${REST}/api/v3/depth?symbol=${pair}&limit=20`),
  ]);
  if (token !== generation) return;
  const hour = closedBars(hourRaw, ticker.time), minute = closedBars(minuteRaw, ticker.time);
  charts.set(pair, { minute, as_of: Date.now() });
  if (hour.length < 80 || minute.length < 60 || !book.bids?.length || !book.asks?.length) return;
  const close = hour.map(b => b.close), price = close.at(-1)!;
  const trend = clamp(.5 + (price / mean(close.slice(-24)) - 1) * 22);
  const early = clamp(.7 - Math.max(0, price / Math.max(...close.slice(-48)) - 1) * 12);
  const volumeRatio = mean(minute.slice(-5).map(b => b.quote)) /
    Math.max(1, mean(minute.slice(-60, -5).map(b => b.quote)));
  const volume = clamp((volumeRatio - .6) / 2.2);
  const ranges = hour.map(b => (b.high - b.low) / b.close);
  const compression = clamp(1 - mean(ranges.slice(-8)) / Math.max(1e-9, mean(ranges.slice(-72, -8))));
  const total = (rows: string[][]) => rows.slice(0, 10).reduce((n, [p, q]) => n + Number(p) * Number(q), 0);
  const bid = total(book.bids), ask = total(book.asks);
  const imbalance = bid / Math.max(1, bid + ask);
  const spread = (Number(book.asks[0][0]) - Number(book.bids[0][0])) / ticker.price * 100;
  const btcChange = tickers.get('BTCTRY')?.change ?? 0;
  const rallyScore = Math.round(100 * (.25 * trend + .17 * early + .21 * volume +
    .14 * compression + .16 * clamp(.5 + (imbalance - .5) * 1.6) +
    .07 * clamp(.5 + btcChange * 15)));
  scored.set(pair, { pair, coin: meta.coin, display: meta.display, price: ticker.price,
    rally_score: rallyScore, state: rallyScore >= 55 ? 'SCOUT' : 'WATCH',
    spread_pct: spread, scenario: null, observed_at: Date.now(), price_observed_at: ticker.time,
    features: { trend, volume_ratio: Number(volumeRatio.toFixed(3)), compression,
      book_imbalance: imbalance, spread_pct: Number(spread.toFixed(4)), btc_change: btcChange,
      regime: btcChange < -.02 ? 'RISK_OFF' : btcChange > .02 ? 'RISK_ON' : 'NEUTRAL',
      device_only: true, model: 'public-radar-v0.1' } });
  publish();
}

async function scan(token: number) {
  if (snapshot.scanning || token !== generation || AppState.currentState !== 'active') return;
  const selected = [...tickers].filter(([pair, t]) => symbols.has(pair) && t.volume >= 1_000_000 &&
    Date.now() - t.time < 30000).sort((a, b) => b[1].volume - a[1].volume).slice(0, 20);
  if (!selected.length) return;
  snapshot = { ...snapshot, scanning: true }; publish();
  let lastError = '';
  // Two symbols at a time; pause the scan immediately after a rate-limit response.
  for (let i = 0; i < selected.length && token === generation; i += 2) {
    const results = await Promise.allSettled(selected.slice(i, i + 2).map(([pair]) => analyse(pair, token)));
    for (const result of results) if (result.status === 'rejected') lastError = String(result.reason?.message || result.reason);
    if (Date.now() < backoffUntil) break;
  }
  if (token === generation) {
    snapshot = { ...snapshot, scanning: false, lastScan: Date.now(), error: lastError }; publish();
  }
}

function start() {
  const token = ++generation;
  let alive = true, retries = 0;
  let reconnect: ReturnType<typeof setTimeout> | undefined;
  let bootScan: ReturnType<typeof setTimeout> | undefined;
  let symbolRetry: ReturnType<typeof setTimeout> | undefined;
  async function loadSymbols() {
    try {
      const data = await marketJSON(SYMBOLS);
      if (!alive || token !== generation) return;
      for (const item of data.list || []) if (item.quoteAsset === 'TRY' && item.type === 1 &&
          item.spotTradingEnable === 1 && !STABLE.has(item.baseAsset)) {
        symbols.set(item.symbol.replace('_', ''), { coin: item.baseAsset,
          display: item.symbol.replace('_', '/') });
      }
      publish();
    } catch (e: any) {
      if (alive && token === generation) {
        snapshot = { ...snapshot, error: e.message }; publish();
        clearTimeout(symbolRetry); symbolRetry = setTimeout(loadSymbols, 60000);
      }
    }
  }
  function connect() {
    if (!alive || AppState.currentState !== 'active') return;
    loadSymbols();
    const ws = new WebSocket(WS); socket = ws;
    ws.onopen = () => {
      if (!alive || token !== generation) return;
      retries = 0; snapshot = { ...snapshot, connected: true, error: '' }; publish();
      bootScan = setTimeout(() => scan(token), 7000);
    };
    ws.onmessage = event => {
      if (!alive || token !== generation) return;
      try {
        const frame = JSON.parse(event.data), payload = frame.data ?? frame;
        if (!Array.isArray(payload)) return;
        for (const t of payload) {
          const price = Number(t.c), open = Number(t.o), volume = Number(t.q), time = Number(t.E);
          if (typeof t.s === 'string' && t.s.endsWith('TRY') && price > 0 && open > 0 &&
              Number.isFinite(volume) && Number.isFinite(time)) {
            tickers.set(t.s, { price, change: price / open - 1, volume, time });
          }
        }
      } catch { /* Ignore control frames; never fabricate a quote. */ }
    };
    ws.onerror = () => {
      if (!alive || token !== generation) return;
      snapshot = { ...snapshot, error: 'Binance TR fiyat bağlantısı kesildi.' }; publish();
    };
    ws.onclose = () => {
      if (!alive || token !== generation) return;
      snapshot = { ...snapshot, connected: false }; publish();
      if (alive && AppState.currentState === 'active') reconnect = setTimeout(connect, Math.min(60000, 2000 * 2 ** retries++));
    };
  }
  const appSubscription = AppState.addEventListener('change', state => {
    clearTimeout(reconnect); clearTimeout(bootScan);
    if (state === 'active') { if (!socket || socket.readyState > 1) connect(); }
    else socket?.close();
  });
  connect();
  const updates = setInterval(publish, 3000);
  const scans = setInterval(() => scan(token), 5 * 60000);
  stopRadar = () => {
    alive = false; generation++; clearInterval(updates); clearInterval(scans);
    clearTimeout(reconnect); clearTimeout(bootScan); clearTimeout(symbolRetry);
    appSubscription.remove(); socket?.close(); socket = null;
    snapshot = { ...snapshot, connected: false, scanning: false }; stopRadar = null;
  };
}

export function useDeviceRadar(enabled: boolean) {
  const [state, setState] = useState(snapshot);
  useEffect(() => {
    if (!enabled) return;
    clearTimeout(stopTimer);
    const listener = () => setState(snapshot);
    listeners.add(listener); if (!stopRadar) start(); listener();
    return () => {
      listeners.delete(listener);
      if (!listeners.size) stopTimer = setTimeout(() => stopRadar?.(), 1500);
    };
  }, [enabled]);
  return state;
}

export function deviceOpportunity(pair: string) {
  return scored.get(pair) || snapshot.opportunities.find(item => item.pair === pair) || null;
}
export async function deviceChart(pair: string): Promise<ChartData> {
  if (!/^[A-Z0-9]+TRY$/.test(pair) || !symbols.has(pair)) throw new Error('Desteklenmeyen Binance TR paritesi.');
  const cached = charts.get(pair);
  if (cached && Date.now() - (cached.as_of || 0) < 60000) return cached;
  const raw = await marketJSON(`${REST}/api/v1/klines?symbol=${pair}&interval=1m&limit=121`);
  const chart = { minute: closedBars(raw, tickers.get(pair)?.time || 0), as_of: Date.now() };
  charts.set(pair, chart); return chart;
}
