import type { TradePlan } from './lab';

export function planLevels(plan: TradePlan | null | undefined, pair: string) {
  return !!plan && plan.pair === pair && Number.isFinite(plan.entry) && Number.isFinite(plan.target) && Number.isFinite(plan.stop) &&
    plan.entry! > 0 && plan.target! > plan.entry! && plan.stop! > 0 && plan.stop! < plan.entry!;
}
export function usablePlan(plan: TradePlan | null | undefined, pair: string, now = Date.now()) {
  return planLevels(plan, pair) && plan!.status === 'READY' && (plan!.as_of ?? Infinity) <= now && (plan!.expires_at ?? 0) >= now;
}
export function paperDraft(pair: string, plan?: TradePlan | null, now = Date.now(), verified = true) {
  if (verified && usablePlan(plan, pair, now)) return { entry: String(plan!.entry), target: String(plan!.target), stop: String(plan!.stop), suggested: true };
  // A quote is never an entry recommendation; missing research stays empty.
  return { entry: '', target: '', stop: '', suggested: false };
}

export function marketPlan(plan: TradePlan | null | undefined, pair: string, price: number | null) {
  if (!plan || plan.pair !== pair) return null;
  if (price == null || !Number.isFinite(price) || price <= 0) return { ...plan, status: 'STALE' as const, reason: 'Piyasa fiyatı doğrulanmadı; güncel analiz bekleniyor.' };
  if (plan.entry != null && price <= plan.entry) return { ...plan, status: 'STALE' as const, reason: 'Piyasa alış seviyesine ulaştı veya altına indi; yeni analiz bekleniyor.' };
  return plan;
}
