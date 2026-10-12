import AsyncStorage from '@react-native-async-storage/async-storage';
import { serverJSON } from './api';

export type Forecast = {
  id: string; horizon_hours: number; direction: 'up' | 'down'; threshold: number;
  probability: number; status: string; issued_at: number; model_version: string;
};
export type Hypothesis = {
  feature: string; horizon_hours: number; direction: string; repeats: number;
  effective_repeats: number; required_per_group: number; rate: number;
  control_rate: number; lift: number; interval_95: number[]; adjusted_p: number; status: string;
};
export type Calibration = { n: number; brier: number | null; ece: number | null };
export type EntryMetrics = {
  n: number; effective_n: number; filled: number; fill_rate: number | null;
  targets: number; stops: number; ambiguous: number;
  net: { mean: number | null; interval: (number | null)[] };
  gain: { mean: number | null; interval: (number | null)[] };
};
export type EntryResearch = {
  status: string; policy_id: string; round: number; trained_at: number;
  required_samples: number; effective_samples: number;
  training_samples: number; validation_samples: number; evaluation_samples: number;
  test: EntryMetrics; warning: string;
  candidates: { policy_id: string; validation: EntryMetrics; test: EntryMetrics }[];
};
export type TradePlan = {
  id?: string; pair: string; status: 'READY' | 'STALE' | 'WAITING'; reason?: string;
  entry: number | null; target: number | null; stop: number | null;
  as_of?: number; expires_at?: number; price_at?: number; reference_price?: number;
  anchor_price?: number; closed_at?: number; entry_policy_id?: string;
  entry_basis?: string; entry_research?: EntryResearch; quote_distance_fraction?: number;
  discount_fraction?: number; stop_fraction?: number; risk_reward?: number;
  training_samples?: number; evaluation_samples?: number; effective_samples?: number; required_samples?: number;
  evidence_status?: string; stop_basis?: 'ATR_BASELINE' | 'RESEARCH_DISTANCE_ADAPTED'; note?: string;
};
export type StopLoss = {
  trained_at?: number; warning: string;
  suggestions: {
    horizon_hours: number; regime: string; policy_id: string; status: string;
    distance_pct: number; stop_price: number; target_price: number; risk_reward: number;
    required_samples: number; effective_samples: number; forward_samples: number;
    forward_effective_samples: number; automatic_apply: false;
    historical_metrics: { n: number; net_return_bounds?: number[]; ambiguous?: number; recovered_after_stop?: number };
  }[];
  forward: { pending?: number; data_gaps?: number; lifetime?: {
    source: string; n: number; stopped: number; recovered_after_stop: number;
  }[] };
  manual: { with_stop?: number; stopped?: number; recovered_to_target?: number; followup_pending?: number };
};
export type ResearchOverlay = {
  status: 'ACTIVE' | 'WAITING' | 'PAUSED'; maximum_points: number; warning: string;
  families: { family: 'direction' | 'entry' | 'stop'; status: string; reason: string; points: number;
    effective_samples?: number; required_samples?: number;
    streams?: { horizon_hours: number; direction: string; status: string; reason: string; effective_samples: number; required_samples: number }[];
  }[];
};
export type LabItem = {
  pair: string; display: string; source: string; price: number | null; as_of: number; observed_at: number | null;
  bars: number; stale: boolean; backfill_complete: boolean; issues: string[];
  forecasts: Forecast[]; hypotheses: Hypothesis[]; hypothesis_count: number; interpretation: string[];
  stop_loss?: StopLoss;
  trade_plan?: TradePlan | null;
  rally_score?: number | null;
  rally?: { status: 'CURRENT' | 'STALE' | 'WAITING'; as_of?: number; error?: string;
    analysis: { base_score?: number; research_adjustment?: number; research_overlay?: ResearchOverlay; components?: Record<string, number>; weights?: Record<string, number>; method?: string } };
  price_observed_at?: number | null; price_clock_at?: number;
  monitor: { drift?: boolean; settled?: number; pending?: number; streams?: {
    horizon_hours: number; direction: string; calibration: Calibration; baseline_brier: number; drift: boolean;
  }[] };
  research: { round?: number; round_alpha?: number; design?: {
    power: number; minimum_detectable_lift: number; confidence: number;
    unadjusted_required_per_group: number;
  }; evaluations?: {
    horizon_hours: number; direction?: string; status: string; test_samples?: number;
    effective_test_samples?: number; calibration?: Calibration;
  }[] };
};
export type LabSnapshot = {
  runtime: 'LOCAL' | 'AWS_SERVERLESS' | 'PHONE';
  schema_version: 2; as_of: number; expected_pairs: string[]; items: LabItem[];
  paper_positions: import('./paperCore').PaperPosition[];
  coverage?: import('./phone').PhoneCoverage;
  audit: { ok: boolean; checked_at: number; issues: { pair: string | null; code: string }[]; pairs: unknown[] };
};

const cacheKey = (base: string) => 'labSnapshot.v2:' + base;
export async function cachedSnapshot(base: string): Promise<LabSnapshot | null> {
  try {
    const raw = await AsyncStorage.getItem(cacheKey(base));
    const value = raw ? JSON.parse(raw) : null;
    return value?.schema_version === 2 && Array.isArray(value.items) ? value : null;
  } catch { return null; }
}
export async function fetchSnapshot(base: string): Promise<LabSnapshot> {
  const value = await serverJSON(base, '/v2/snapshot');
  if (value?.schema_version !== 2 || !Array.isArray(value.items) || !value.audit) {
    throw new Error('Backend veri sürümü doğrulanamadı.');
  }
  return value;
}
export async function cacheSnapshot(base: string, snapshot: LabSnapshot) {
  await AsyncStorage.setItem(cacheKey(base), JSON.stringify(snapshot));
}
