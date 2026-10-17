"""Shared historical decision rules; descriptive adjustments, never calibrated odds.

The deployed Node runtime mirrors these pure functions. Policy selection is based
only on chronological validation; the held-out test does not select a policy.
"""
from math import sqrt, tanh, floor, isfinite
from statistics import fmean, pstdev

POLICIES = [
    {'id': 'atr-early', 'entry_atr': .15, 'stop_atr': 1.25, 'target_atr': 2},
    {'id': 'atr-base', 'entry_atr': .35, 'stop_atr': 1.5, 'target_atr': 2},
    {'id': 'atr-patient', 'entry_atr': .6, 'stop_atr': 1.8, 'target_atr': 2.5},
    {'id': 'pct-reference', 'discount': .003, 'stop_fraction': .02, 'target_fraction': .03},
]


def clip(value, lower, upper):
    return max(lower, min(upper, value))


def policy_plan(price, atr, policy):
    if not isfinite(price) or not isfinite(atr) or price <= 0 or atr < 0:
        raise ValueError('invalid price / ATR')
    discount = policy.get('discount', clip(policy.get('entry_atr', 0) * atr / price, .001, .025))
    entry = price * (1-discount)
    stop_fraction = policy.get('stop_fraction', clip(policy.get('stop_atr', 0)*atr/entry, .01, .08))
    target_fraction = policy.get('target_fraction', clip(policy.get('target_atr', 0)*atr/entry, .03, .15))
    return {'entry': entry, 'stop': entry*(1-stop_fraction), 'target': entry*(1+target_fraction), 'policy_id': policy['id']}


def historical_stats(values):
    valid = [x for x in values if x is not None and isfinite(x)]
    effective = len(valid)/9  # Six-hour samples still share up to 54-hour labels.
    if not valid:
        return {'n': 0, 'effective_n': 0, 'mean': None, 'se': None, 'interval': [None, None]}
    mean = fmean(valid)
    se = pstdev(valid)/sqrt(max(1, effective))
    return {'n': len(valid), 'effective_n': effective, 'mean': mean, 'se': se, 'interval': [mean-1.96*se, mean+1.96*se]}


def select_policy(validation):
    """validation is a mapping from predeclared policy id to historical returns."""
    candidates = [(name, historical_stats(values)) for name, values in validation.items()]
    candidates = [(name, metrics) for name, metrics in candidates if metrics['n'] >= 15]
    if not candidates:
        return None
    return max(candidates, key=lambda item: item[1]['mean']-.5*item[1]['se'])[0]


def historical_adjustment(base_score, returns, holdout):
    """Bound a non-probabilistic research contribution using held-out evidence.

    Small / uncertain historical evidence moves the index by at most four points.
    Strong evidence needs same-sign intervals in both the neighbors and holdout.
    It is still historical paper evidence, not verified forward profitability.
    """
    if holdout['effective_n'] < 8 or len(returns) < 20:
        return {'base_score': base_score, 'research_adjustment': 0, 'final_score': base_score, 'status': 'waiting'}
    mean = fmean(returns)
    effective = min(holdout['effective_n'], len(returns)/9)
    se = pstdev(returns)/sqrt(max(1, effective))
    interval = [mean-1.96*se, mean+1.96*se]
    strong = (interval[0] > 0 and holdout['interval'][0] > 0) or (interval[1] < 0 and holdout['interval'][1] < 0)
    cap = 12 if strong else 4
    points = floor(clip(12*effective/(effective+16)*tanh(mean/max(.01, 2*se)), -cap, cap)*10+.5)/10
    return {'base_score': base_score, 'research_adjustment': points, 'final_score': clip(floor((base_score+points)*10+.5)/10, 0, 100),
            'status': 'historical_support' if strong else 'limited_historical_evidence', 'historical_net': mean,
            'uncertainty': interval, 'effective_samples': effective, 'probability': None}


def apply_to_payload(payload, research):
    """Explicit adapter for the existing Python engine's Rally payload."""
    updated = dict(payload)
    base = payload.get('base_score', payload.get('score'))
    if base is None:
        return updated
    decision = historical_adjustment(base, research['neighbor_returns'], research['holdout'])
    updated.update(base_score=base, research_adjustment=decision['research_adjustment'], score=decision['final_score'], dynamic_research=decision)
    return updated


def optimal_opportunity(candidate, now):
    """Budget allocator priority mirrored by the live Node backend; no forecast claim."""
    r = candidate.get('research') or {}
    holdout = r.get('holdout') or {}
    if not r.get('model_id') or r.get('status') not in ('historical_support', 'limited_historical_evidence') or r.get('samples', 0) < 20 or holdout.get('effective_n', 0) < 8:
        return {'eligible': False, 'reason': 'RESEARCH_NOT_READY'}
    trained = r.get('trained_at')
    if trained is None or not isfinite(trained) or trained > now or now-trained > 86400000:
        return {'eligible': False, 'reason': 'RESEARCH_NOT_CURRENT'}
    spread = candidate.get('spread_pct', float('nan'))/100
    if not isfinite(spread) or spread < 0 or spread > .015:
        return {'eligible': False, 'reason': 'SPREAD_TOO_WIDE'}
    mean, test_mean, interval = r.get('historical_net'), holdout.get('mean'), r.get('uncertainty')
    if mean is None or test_mean is None or not isfinite(mean) or not isfinite(test_mean) or not interval or len(interval) != 2 or not all(isfinite(x) for x in interval):
        return {'eligible': False, 'reason': 'RESEARCH_NOT_READY'}
    se = max(0, (interval[1]-interval[0])/3.92)
    utility = min(mean, test_mean)-.5*se-spread
    loss = (candidate['entry']-candidate['stop'])/candidate['entry']+.003
    if not (utility > 0 and loss > 0 and isfinite(loss)):
        return {'eligible': False, 'reason': 'NO_POSITIVE_HISTORICAL_EDGE'}
    priority = utility/loss*max(0, candidate.get('final_score', candidate['score']))/100
    return {'eligible': True, 'reason': 'HISTORICAL_EDGE_AND_RISK', 'priority': priority, 'utility': utility, 'spread': spread, 'uncertainty_se': se}
