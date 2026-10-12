import { useEffect, useState } from 'react';
import { AppState, Platform } from 'react-native';
import { requireOptionalNativeModule } from 'expo-modules-core';
import type { TradePlan } from './lab';
import { marketPlan } from './paperDraft';

export type LiveQuote = { price: number; change: number; quote_volume: number; observed_at: number; source: 'BINANCE_TR_WS' | 'BINANCE_TR_REST' };
export type LivePrices = { protocol?: string; state: 'PAUSED' | 'CONNECTING' | 'LIVE' | 'REST' | 'STALE'; tickers: Record<string, LiveQuote>; error?: string; last_received_at?: number; backoff_until?: number; ws_connected?: boolean; as_of?: number };
const native = Platform.OS === 'android' ? requireOptionalNativeModule<{ prices(visible: boolean): Promise<string> }>('RadarPhone') : null;
const empty: LivePrices = { state: 'PAUSED', tickers: {} };
let value = empty;
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;
let subscription: { remove(): void } | undefined;
let epoch = 0, inFlight = false;
export function validLiveQuote(quote?: LiveQuote, now = Date.now()) {
  return !!quote && Number.isFinite(quote.price) && quote.price > 0 && Number.isFinite(quote.observed_at) && quote.observed_at > 0 && quote.observed_at <= now && ['BINANCE_TR_WS','BINANCE_TR_REST'].includes(quote.source);
}
async function poll() {
  if (!native || inFlight || AppState.currentState !== 'active' || !listeners.size) return;
  const token = epoch; inFlight = true;
  try {
    const updated = JSON.parse(await native.prices(true));
    if (token === epoch && updated.protocol === 'native-binance-tr-prices-v1' && updated.tickers && typeof updated.tickers === 'object') {
      value = updated; listeners.forEach(listener => listener());
    }
  } catch {
    if (token === epoch) { value = { ...value, state: 'STALE', as_of: Date.now(), error: 'Fiyat ekranı güncellenemedi; son fiyatlar korunuyor.' }; listeners.forEach(listener => listener()); }
  }
  finally { if (token === epoch) inFlight = false; }
}
export function useLivePrices(enabled: boolean) {
  const [state, setState] = useState(value);
  useEffect(() => {
    if (!enabled || !native) return;
    const notify = () => setState(value);
    listeners.add(notify);
    if (listeners.size === 1) {
      epoch++; void poll(); timer = setInterval(() => { void poll(); }, 2000);
      subscription = AppState.addEventListener('change', activity => {
        epoch++; inFlight = false;
        if (activity === 'active') void poll();
        else void native.prices(false).catch(() => undefined);
      });
    }
    return () => {
      listeners.delete(notify);
      if (!listeners.size) { epoch++; inFlight = false; clearInterval(timer); subscription?.remove(); void native.prices(false).catch(() => undefined); }
    };
  }, [enabled]);
  return enabled ? state : empty;
}
export function withLivePrice<T extends { pair: string; price: number | null; price_observed_at?: number | null; observed_at?: number | null; trade_plan?: TradePlan | null; price_clock_at?: number }>(item: T, feed: LivePrices, now = Date.now()): T {
  const quote = feed.tickers[item.pair];
  const previous = item.price_observed_at ?? item.observed_at ?? 0;
  if (!validLiveQuote(quote, now) || quote.observed_at <= previous) return feed.as_of ? { ...item, price_clock_at: feed.as_of } : item;
  return { ...item, price: quote.price, price_observed_at: quote.observed_at, price_clock_at: feed.as_of,
    trade_plan: marketPlan(item.trade_plan, item.pair, quote.price) };
}
export function priceAge(at?: number | null, now = Date.now()) {
  if (!at || at > now) return 'Fiyat zamanı doğrulanmadı';
  const seconds = Math.floor((now-at)/1000);
  return seconds <= 30 ? 'Güncel fiyat · ' + seconds + ' sn önce' : 'Son fiyat · ' + (seconds < 120 ? seconds + ' sn' : Math.floor(seconds/60) + ' dk') + ' önce';
}
