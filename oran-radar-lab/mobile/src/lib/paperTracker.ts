import { useEffect, useState } from 'react';
import { AppState } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { requestJSON, serverJSON } from './api';
import { applyTrade, PaperPosition, Trade, validateLevels } from './paperCore';

const KEY = 'paperPositions.v1';
const REST = 'https://api.binance.me/api/v3/aggTrades';
const WS = 'wss://stream-cloud.binance.tr/ws';
type Snapshot = { positions: PaperPosition[]; error: string; syncing: boolean; ready: boolean };
let snapshot: Snapshot = { positions: [], error: '', syncing: false, ready: false };
const listeners = new Set<() => void>();
let loaded: Promise<void> | null = null;
let chain = Promise.resolve();
let socket: WebSocket | null = null;
let subscriptions = '';
let started = false;
let retryAt = 0;
const pending = new Map<string, Trade | null>();
const busy = new Set<string>();
const lastAttempt = new Map<string, number>();
function publish() { listeners.forEach(fn => fn()); }
function change(fn: () => void | Promise<void>) {
  const task = chain.then(fn);
  chain = task.catch(() => undefined);
  return task;
}
async function save() { await AsyncStorage.setItem(KEY, JSON.stringify(snapshot.positions)); publish(); }
async function load() {
  if (!loaded) loaded = (async () => {
    try {
      const raw = await AsyncStorage.getItem(KEY);
      if (raw) {
        const positions = JSON.parse(raw);
        if (!Array.isArray(positions)) throw new Error('Kayıt biçimi okunamadı.');
        snapshot = { ...snapshot, positions };
      }
      snapshot = { ...snapshot, ready: true }; publish();
    } catch {
      snapshot = { ...snapshot, error: 'Sanal alış kayıtları okunamadı; yeni kayıt açılmadı.' }; publish();
      throw new Error(snapshot.error);
    }
  })();
  return loaded;
}
async function trades(params: string) {
  if (Date.now() < retryAt) throw new Error('Binance TR hız sınırı; tekrar denenecek.');
  try {
    const raw = await requestJSON(REST + '?' + params + '&limit=1000');
    const list = Array.isArray(raw) ? raw : raw.data;
    if (!Array.isArray(list) || list.some(t => !Number.isSafeInteger(Number(t.a)) ||
        !Number.isFinite(Number(t.T)) || !(Number(t.p) > 0))) throw new Error('İşlem akışı doğrulanamadı.');
    return list.map(t => ({ a: Number(t.a), T: Number(t.T), p: String(t.p) })) as Trade[];
  } catch (error) { retryAt = Date.now() + 60000; throw error; }
}
function activeFor(pair: string) {
  return snapshot.positions.filter(p => p.origin === 'device' && p.pair === pair && p.state === 'TRACKING');
}
async function syncPair(pair: string, incoming: Trade | null) {
  let active = activeFor(pair);
  if (!active.length) return;
  snapshot = { ...snapshot, syncing: true }; publish();
  const ids = active.map(p => p.last_trade_id);
  let cursor = ids.every(id => id !== null) ? Math.min(...ids as number[]) : null;
  let start = Math.min(...active.map(p => p.created_at));
  const until = incoming?.T ?? Date.now();
  for (let page = 0; page < 30 && AppState.currentState === 'active'; page++) {
    const end = Math.min(until, start + 3599000);
    const initial = cursor === null;
    const batch = await trades(`symbol=${pair}&` + (cursor === null ?
      `startTime=${start}&endTime=${end}` : `fromId=${cursor + 1}`));
    if (!batch.length) {
      if (cursor === null && end < until) { start = end + 1; continue; }
      if (incoming && (cursor === null || cursor < incoming.a)) throw new Error('Eksik işlemler tamamlanamadı.');
      return;
    }
    for (const trade of batch) {
      if (cursor !== null && trade.a !== cursor + 1) throw new Error('İşlem sırası eksik; sonuç bekletiliyor.');
      if (cursor === null && trade.T < start) throw new Error('İşlem başlangıç zamanı doğrulanamadı.');
      cursor = trade.a;
      snapshot.positions = snapshot.positions.map(p => p.origin === 'device' && p.pair === pair ? applyTrade(p, trade) : p);
    }
    await save();
    active = activeFor(pair);
    if (!active.length || (incoming && cursor !== null && cursor >= incoming.a) ||
        (!incoming && batch.length < 1000 && (!initial || end >= until))) return;
  }
  throw new Error('Geçmiş işlem taraması sürüyor; takip doğrulanınca güncellenecek.');
}
function schedule(pair: string, trade: Trade | null = null) {
  if (!activeFor(pair).length || AppState.currentState !== 'active') return;
  const old = pending.get(pair);
  if (!old || (trade && trade.a > old.a)) pending.set(pair, trade);
  if (busy.has(pair) || Date.now() - (lastAttempt.get(pair) || 0) < 3000) return;
  busy.add(pair);
  lastAttempt.set(pair, Date.now());
  void change(async () => {
    const incoming = pending.get(pair) ?? null; pending.delete(pair);
    try {
      await syncPair(pair, incoming);
      snapshot.positions = snapshot.positions.map(p => p.origin === 'device' && p.pair === pair ? { ...p, feed_blocked: false } : p);
      await save(); snapshot = { ...snapshot, error: '' };
    }
    catch {
      snapshot.positions = snapshot.positions.map(p => p.origin === 'device' && p.pair === pair && p.state === 'TRACKING' ? { ...p, feed_blocked: true } : p);
      await save(); snapshot = { ...snapshot, error: 'Binance TR işlem geçmişi doğrulanamadı. Hedef sonucu bekletiliyor; bağlantı yeniden denenecek.' };
    }
    finally {
      busy.delete(pair); snapshot = { ...snapshot, syncing: busy.size > 0 }; publish();
      // The next polling cycle retries any gap; never infer a target from a ticker.
    }
  });
}
function connect() {
  if (AppState.currentState !== 'active') return;
  const pairs = [...new Set(snapshot.positions.filter(p => p.origin === 'device' && p.state === 'TRACKING').map(p => p.pair))].sort();
  const signature = pairs.join(',');
  if (signature === subscriptions && socket && socket.readyState < 2) return;
  subscriptions = signature; socket?.close(); socket = null;
  if (!pairs.length) return;
  const ws = new WebSocket(WS); socket = ws;
  ws.onopen = () => {
    if (socket !== ws) return;
    ws.send(JSON.stringify({ method: 'SUBSCRIBE', params: pairs.map(p => p.toLowerCase() + '@aggTrade'), id: 1 }));
    pairs.forEach(pair => schedule(pair));
  };
  ws.onmessage = event => {
    if (socket !== ws) return;
    try {
      const frame = JSON.parse(String(event.data)), t = frame.data ?? frame;
      if (t.e === 'aggTrade') schedule(t.s, { a: Number(t.a), T: Number(t.T), p: String(t.p) });
    } catch { /* Ignore subscription acknowledgements. */ }
  };
  ws.onclose = () => { if (socket === ws) socket = null; };
  ws.onerror = () => { snapshot = { ...snapshot, error: 'Binance TR takip bağlantısı bekleniyor.' }; publish(); };
}
function start() {
  if (started) return; started = true;
  void load().then(connect).catch(() => undefined);
  AppState.addEventListener('change', state => {
    if (state === 'active') { connect(); [...new Set(snapshot.positions.map(p => p.pair))].forEach(p => schedule(p)); }
    else { socket?.close(); socket = null; }
  });
  setInterval(() => {
    if (!snapshot.ready || AppState.currentState !== 'active') return;
    connect(); [...new Set(snapshot.positions.map(p => p.pair))].forEach(p => schedule(p));
  }, 15000);
}
export function usePaperTracker() {
  const [value, setValue] = useState(snapshot);
  useEffect(() => { const listener = () => setValue({ ...snapshot }); listeners.add(listener); start(); listener();
    return () => { listeners.delete(listener); }; }, []);
  return value;
}
export async function addPaperPosition(pair: string, entry: number, target: number, stop: number | null, server: string) {
  validateLevels(entry, target, stop);
  if (!/^[A-Z0-9]+TRY$/.test(pair)) throw new Error('Binance TR TRY paritesi seçin.');
  await load();
  const id = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const n = Math.floor(Math.random() * 16); return (c === 'x' ? n : (n & 3) | 8).toString(16);
  });
  const ts = Date.now();
  let position: PaperPosition = { position_id: id, pair, entry, target, stop,
    created_at: ts, deadline_at: ts + 48 * 3600000, state: 'TRACKING', closed_at: null,
    target_hit_at: null, observed_price: null, observed_at: null, last_trade_id: null,
    gross_return_pct: null, net_return_pct: null, fee_bps: 20, origin: 'device' };
  if (server) {
    // Reuse the UUID after a timeout to make uncertain responses idempotent.
    const body = JSON.stringify({ position_id: id, pair, entry, target, stop });
    let saved;
    try { saved = await serverJSON(server, '/paper-positions', { method: 'POST', body }); }
    catch { saved = await serverJSON(server, '/paper-positions', { method: 'POST', body }); }
    position = { ...saved, origin: 'server', server };
  }
  await change(async () => { snapshot.positions = [...snapshot.positions, position]; await save(); });
  start(); connect(); schedule(pair);
  return position;
}
export async function mergeServerPositions(server: string, positions: PaperPosition[]) {
  await load();
  await change(async () => {
    const ids = new Set(positions.map(p => p.position_id));
    snapshot.positions = [...snapshot.positions.filter(p => !ids.has(p.position_id)),
      ...positions.map(p => {
        const rawState: string = p.state;
        const state: PaperPosition['state'] = rawState === 'TARGET_HIT' ? 'SUCCESS' : rawState === 'TIMEOUT' ? 'TIME_EXPIRED' : p.state;
        return { ...p, state, origin: 'server' as const, server };
      })];
    await save();
  });
}
