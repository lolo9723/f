export type PaperPosition = {
  position_id: string; pair: string; entry: number; target: number; stop: number | null;
  created_at: number; deadline_at: number; state: 'TRACKING' | 'SUCCESS' | 'STOPPED' | 'TIME_EXPIRED';
  closed_at: number | null; target_hit_at: number | null;
  observed_price: number | null; observed_at: number | null; last_trade_id: number | null;
  gross_return_pct: number | null; net_return_pct: number | null; fee_bps: number;
  origin: 'device' | 'server'; server?: string; feed_blocked?: boolean;
  stop_distance_pct?: number | null; risk_reward?: number | null;
  mae_pct?: number; mfe_pct?: number; exit_price?: number | null; stop_slippage_pct?: number | null;
  followup_state?: 'WAITING_STOP' | 'NOT_NEEDED' | 'PENDING' | 'COMPLETED' | 'RECOVERED_TO_TARGET';
  recovered_at?: number | null; post_stop_mfe_pct?: number | null;
};
export type Trade = { a: number; T: number; p: string };
export function validateLevels(entry: number, target: number, stop: number | null) {
  if (!Number.isFinite(entry) || entry <= 0 || !Number.isFinite(target) || target <= entry ||
      (stop !== null && (!Number.isFinite(stop) || stop <= 0 || stop >= entry))) {
    throw new Error('Alış pozitif, hedef alıştan yüksek; stop varsa alıştan düşük olmalı.');
  }
}
// Only actual Binance TR aggregated trades are evidence. A mini ticker or a
// candle high cannot establish when a target was reached or which barrier came first.
export function applyTrade(position: PaperPosition, trade: Trade): PaperPosition {
  const price = Number(trade.p);
  if (position.state !== 'TRACKING' || trade.T < position.created_at ||
      !Number.isFinite(price) || price <= 0 || !Number.isSafeInteger(trade.a) ||
      !Number.isFinite(trade.T) || (position.last_trade_id !== null && trade.a <= position.last_trade_id)) return position;
  let state: PaperPosition['state'] = position.state;
  let exit: number | null = null;
  if (trade.T >= position.deadline_at) { state = 'TIME_EXPIRED'; exit = price; }
  else if (position.stop !== null && price <= position.stop) { state = 'STOPPED'; exit = price; }
  else if (price >= position.target) { state = 'SUCCESS'; exit = position.target; }
  const gross = exit === null ? null : (exit / position.entry - 1) * 100;
  return { ...position, state, observed_price: price, observed_at: trade.T, last_trade_id: trade.a,
    closed_at: exit === null ? null : trade.T, target_hit_at: state === 'SUCCESS' ? trade.T : null,
    gross_return_pct: gross, net_return_pct: gross === null ? null : gross - position.fee_bps / 100 };
}
