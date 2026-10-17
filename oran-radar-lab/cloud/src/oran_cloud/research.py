"""Reproducible research primitives; a backtest is never a forward-performance claim."""
import json
import math
import statistics
from dataclasses import dataclass, asdict
from .candles import INTERVAL_MS
from .db import identity, now_ms

FEATURE_VERSION = 'causal-v1'


def features(candles, interval='1h', window=24):
    """Each row is available only AFTER its last candle closes. Gaps reset history."""
    ordered = sorted(candles, key=lambda c:c.open_ms)
    output, history = [], []
    for c in ordered:
        if history and c.open_ms != history[-1].open_ms + INTERVAL_MS[interval]:
            history = []
        history.append(c)
        if len(history) < window + 1:
            continue
        history = history[-window-1:]
        closes = [float(x.close) for x in history]
        volumes = [float(x.volume) for x in history]
        past_mean = statistics.fmean(volumes[:-1])
        past_sd = statistics.pstdev(volumes[:-1])
        returns = [math.log(b/a) for a,b in zip(closes[:-1],closes[1:])]
        output.append({'as_of':c.close_ms+1,'candle_open_ms':c.open_ms,'version':FEATURE_VERSION,
                       'momentum_1':closes[-1]/closes[-2]-1,
                       'momentum_window':closes[-1]/closes[0]-1,
                       'volume_z':(volumes[-1]-past_mean)/past_sd if past_sd else 0.0,
                       'volatility':statistics.pstdev(returns),
                       'ma_distance':closes[-1]/statistics.fmean(closes[1:])-1,
                       'range_fraction':float(c.high)/float(c.low)-1,
                       'taker_buy_fraction':float(c.taker_base)/float(c.volume) if float(c.volume) else None})
    return output


@dataclass(frozen=True)
class TradeSpec:
    predicted_at: int
    entry_expires_at: int
    entry: float
    target: float
    stop: float
    horizon_h: int = 48
    fee_bps: float = 10
    slippage_bps: float = 5
    spread_bps: float = 0

    def __post_init__(self):
        if not 0 < self.stop < self.entry < self.target or self.entry_expires_at <= self.predicted_at:
            raise ValueError('invalid trade levels or entry expiration')
        if self.horizon_h not in (1,3,6,12,24,48):
            raise ValueError('unsupported horizon')
        if any(not math.isfinite(x) or not 0<=x<10000 for x in [self.fee_bps,self.slippage_bps,self.spread_bps]) or self.slippage_bps+self.spread_bps/2>=10000:
            raise ValueError('invalid costs')


def evaluate(spec, candles, at, interval='1h'):
    """Conservative OHLC paper simulation. No claim that a real order filled.

    Limit fill requires trading BELOW entry, not a mere touch (queue unknown).
    Open within/below the limit allows an estimated opening fill. Unknown
    intra-bar order is reported as ambiguous; missing bars as unverifiable.
    """
    step = INTERVAL_MS[interval]
    if spec.predicted_at % step or spec.entry_expires_at % step:
        raise ValueError('paper decisions and entry expiration must align with the data interval')
    bars = sorted([c for c in candles if c.open_ms >= spec.predicted_at and c.close_ms < at],key=lambda c:c.open_ms)
    fill, fill_time, deadline, last, mfe, mae = None, None, None, None, 0.0, 0.0
    expected = spec.predicted_at
    for c in bars:
        if c.open_ms != expected:
            return {'status':'unverifiable','reason':'missing or duplicate candle','net_return':None}
        if fill is None and c.open_ms >= spec.entry_expires_at:
            break
        if deadline is not None and c.open_ms >= deadline:
            break
        expected = c.open_ms + step
        o,h,l,cl = map(float,[c.open,c.high,c.low,c.close])
        just_filled = False
        if fill is None:
            if o <= spec.entry or l < spec.entry:
                fill = min(o,spec.entry)
                fill_time = c.open_ms
                deadline = fill_time + spec.horizon_h*3_600_000
                just_filled = True
            else:
                continue
        stop_hit, target_hit = l <= spec.stop, h >= spec.target
        if (stop_hit and target_hit) or (just_filled and o>spec.entry and target_hit):
            return {'status':'ambiguous','reason':'unknown intrabar entry/target/stop order',
                    'fill_time':fill_time,'fill_estimated':True,'net_return':None}
        mfe=max(mfe,h/fill-1); mae=min(mae,l/fill-1)
        if stop_hit or target_hit:
            exit_price = min(o,spec.stop) if stop_hit else spec.target
            return _outcome(spec,fill,fill_time,exit_price,c.close_ms,
                            'stop' if stop_hit else 'target',mfe,mae)
        last = c
    if fill is None:
        if at < spec.entry_expires_at:
            return {'status':'pending','net_return':None}
        if expected < spec.entry_expires_at:
            return {'status':'unverifiable','reason':'entry observation incomplete','net_return':None}
        return {'status':'unfilled','net_return':None,'fill_estimated':True}
    if at < deadline:
        return {'status':'pending','fill_time':fill_time,'net_return':None}
    if last is None or last.close_ms + 1 < deadline:
        return {'status':'unverifiable','reason':'holding window incomplete','net_return':None}
    return _outcome(spec,fill,fill_time,float(last.close),last.close_ms,'timeout',mfe,mae)


def _outcome(spec, fill, fill_time, exit_price, exit_time, status, mfe, mae):
    fee=spec.fee_bps/10000; friction=(spec.slippage_bps+spec.spread_bps/2)/10000
    return {'status':status,'fill_time':fill_time,'exit_time_upper_bound':exit_time,
            'fill_estimated':True,'costs_estimated':True,'entry_price':fill,'exit_price':exit_price,
            'net_return':exit_price*(1-friction)*(1-fee)/(fill*(1+friction)*(1+fee))-1,
            'mfe':mfe,'mae':mae,'time_resolution':'candle; exact hit time unknown'}


def wilson(wins,n):
    if not n:
        return None
    p=wins/n; z=1.959963984540054
    center=(p+z*z/(2*n))/(1+z*z/n)
    half=z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/(1+z*z/n)
    return [center-half,center+half]


def performance(outcomes):
    valid=[o for o in outcomes if o.get('net_return') is not None]
    returns=[o['net_return'] for o in valid]
    wins=sum(x>0 for x in returns); losses=-sum(x for x in returns if x<0)
    equity,peak,mdd=1.0,1.0,0.0
    for r in returns:
        equity*=1+r; peak=max(peak,equity); mdd=min(mdd,equity/peak-1)
    return {'trades':len(valid),'win_rate':wins/len(valid) if valid else None,
            'win_rate_ci95':wilson(wins,len(valid)),
            'net_expectancy':statistics.fmean(returns) if returns else None,
            'profit_factor':sum(x for x in returns if x>0)/losses if losses else None,
            'max_drawdown_sequential_nonoverlapping_assumption':mdd if returns else None,
            'excluded':{s:sum(o['status']==s for o in outcomes) for s in ['pending','unfilled','ambiguous','unverifiable']},
            'real_trades':0}


def purged_walk_forward(rows, folds=3, min_train=100, embargo_ms=48*3_600_000):
    """Rows require as_of, label_end. Labels reaching the test window are purged."""
    rows=sorted(rows,key=lambda r:r['as_of']); n=len(rows)
    block=max(1,(n-min_train)//folds)
    for k in range(folds):
        start=min_train+k*block
        test=rows[start:min(n,start+block)]
        if not test:
            continue
        cutoff=test[0]['as_of']-embargo_ms
        train=[r for r in rows[:start] if r['label_end']<cutoff]
        if len(train)>=min_train:
            yield train,test


def compare_models(rows, feature_names, seed=42):
    """Candidate experiments only. No automatic promotion and no fabricated results."""
    import numpy as np
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.metrics import brier_score_loss, log_loss
    results=[]
    for fold,(train,test) in enumerate(purged_walk_forward(rows)):
        x=np.array([[r[f] for f in feature_names] for r in train]); y=np.array([r['label'] for r in train])
        xt=np.array([[r[f] for f in feature_names] for r in test]); yt=np.array([r['label'] for r in test])
        if len(set(y))<2 or not np.isfinite(x).all() or not np.isfinite(xt).all():
            continue
        candidates={'base_rate':None,
                    'logistic':make_pipeline(StandardScaler(),LogisticRegression(random_state=seed,max_iter=500)),
                    'gradient_boosting':HistGradientBoostingClassifier(random_state=seed,max_iter=100)}
        for name,model in candidates.items():
            if model is None:
                p=np.repeat(y.mean(),len(yt))
            else:
                model.fit(x,y); p=model.predict_proba(xt)[:,1]
            results.append({'fold':fold,'model':name,'train_n':len(train),'test_n':len(test),
                            'test_start':test[0]['as_of'],'brier':float(brier_score_loss(yt,p)),
                            'log_loss':float(log_loss(yt,p,labels=[0,1])),
                            'status':'research_only; forward validation and calibration still required'})
    if not results:
        raise ValueError('insufficient causally separated data for model comparison')
    return results


def similar_patterns(db, query, at, feature_names, min_samples=30):
    """Only observations and matured outcomes before query time; scale on that history."""
    events=[]
    for r in db.execute('''SELECT e.*,o.outcome_json,o.evaluated_at FROM pattern_events e
      JOIN pattern_outcomes o ON o.event_id=e.id AND o.horizon_h=48
      WHERE e.observed_at<? AND o.evaluated_at<=?''',(at,at)):
        v=json.loads(r['features_json']); outcome=json.loads(r['outcome_json'])
        if all(v.get(f) is not None for f in feature_names) and outcome.get('net_return') is not None:
            events.append((r,v,outcome))
    if len(events)<min_samples:
        return {'status':'insufficient_sample','n':len(events),'probability':None}
    scales={f:statistics.pstdev(v[f] for _,v,_ in events) or 1 for f in feature_names}
    ranked=sorted(events,key=lambda e:sum(((query[f]-e[1][f])/scales[f])**2 for f in feature_names))[:min_samples]
    wins=sum(o['net_return']>0 for _,_,o in ranked)
    return {'status':'historical_similarity_only','n':len(ranked),'win_rate':wins/len(ranked),
            'ci95':wilson(wins,len(ranked)), 'events':[r['id'] for r,_,_ in ranked]}


def register_model(db, model_id, artifact_key, metadata):
    with db:
        db.execute('INSERT INTO model_versions VALUES(?,?,?,?,?)',
                   (model_id,now_ms(),'challenger',artifact_key,json.dumps(metadata,sort_keys=True)))


def promote(db, model_id, evidence, min_forward_trades=100):
    required=['forward_trades','net_expectancy','brier','champion_brier','drawdown','max_drawdown','stable_folds']
    if any(k not in evidence for k in required):
        raise ValueError('missing promotion evidence')
    if evidence['forward_trades']<min_forward_trades or evidence['net_expectancy']<=0 or evidence['brier']>=evidence['champion_brier'] or abs(evidence['drawdown'])>evidence['max_drawdown'] or evidence['stable_folds']<3:
        raise ValueError('challenger failed promotion gate')
    observed=[]
    for row in db.execute('''SELECT o.payload_json FROM signal_outcomes o JOIN signals s USING(signal_id)
      WHERE s.model_id=? AND s.mode='paper_forward' AND s.symbol_id LIKE 'binance_tr:spot:%'
      AND o.horizon_h=48 AND o.status IN ('target','stop','timeout') AND o.evaluated_at<=?''',(model_id,now_ms())):
        r=json.loads(row['payload_json']).get('net_return')
        if isinstance(r,(int,float)) and math.isfinite(r):observed.append(r)
    if len(observed)<min_forward_trades or statistics.fmean(observed)<=0:
        raise ValueError('no sufficient positive forward TR evidence in the immutable signal ledger')
    with db:
        if not db.execute('SELECT 1 FROM model_versions WHERE model_id=?',(model_id,)).fetchone():
            raise ValueError('unknown model')
        db.execute("UPDATE model_versions SET status='archived' WHERE status='champion'")
        db.execute("UPDATE model_versions SET status='champion' WHERE model_id=?",(model_id,))


def rollback(db, model_id):
    with db:
        if not db.execute("SELECT 1 FROM model_versions WHERE model_id=? AND status='archived'",(model_id,)).fetchone():
            raise ValueError('rollback requires a retained prior model')
        db.execute("UPDATE model_versions SET status='challenger' WHERE status='champion'")
        db.execute("UPDATE model_versions SET status='champion' WHERE model_id=?",(model_id,))
