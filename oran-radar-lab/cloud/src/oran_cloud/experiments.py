"""Optional candidate training with a final untouched chronological holdout."""
import io
import json
import statistics
from .ingestion import load_candles
from .research import features,evaluate,TradeSpec,compare_models,register_model,FEATURE_VERSION
from .db import identity,now_ms

FEATURE_NAMES=['momentum_1','momentum_window','volume_z','volatility','ma_distance','range_fraction','taker_buy_fraction']


def training_rows(candles,net_target=.03,stop_fraction=.02,entry_discount=.003,horizon_h=48):
    by_time={c.open_ms:i for i,c in enumerate(candles)};rows=[];excluded={}
    for feature in features(candles):
        if feature['taker_buy_fraction'] is None:continue
        i=by_time[feature['candle_open_ms']]; decision=feature['as_of']
        future=candles[i+1:i+1+3+horizon_h]
        if not future or future[-1].close_ms+1<decision+(3+horizon_h)*3600000:continue
        entry=float(candles[i].close)*(1-entry_discount)
        fee=.001;friction=.0005
        target=entry*(1+net_target)*(1+fee)*(1+friction)/((1-fee)*(1-friction))
        spec=TradeSpec(decision,decision+3*3600000,entry,target,entry*(1-stop_fraction),horizon_h)
        outcome=evaluate(spec,future,decision+(3+horizon_h)*3600000)
        if outcome['status'] not in ('target','stop','timeout'):
            excluded[outcome['status']]=excluded.get(outcome['status'],0)+1;continue
        rows.append({**feature,'label':int(outcome['status']=='target'),
                     'label_end':outcome['exit_time_upper_bound']+1,
                     'net_return':outcome['net_return']})
    return rows,excluded


def train_candidate(db,store,exchange,symbol):
    import numpy as np
    import joblib
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.linear_model import LogisticRegression
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.dummy import DummyClassifier
    from sklearn.metrics import brier_score_loss,log_loss
    import sklearn,platform
    candles,dataset_hash=load_candles(db,store,exchange,symbol,'1h')
    rows,excluded=training_rows(candles)
    if len(rows)<1000:raise ValueError('at least 1000 usable historical observations required')
    import pyarrow as pa
    import pyarrow.parquet as pq
    feature_rows=features(candles)
    feature_id=identity(dataset_hash,FEATURE_VERSION,24)
    feature_key=f'features/{feature_id}.parquet'
    feature_buffer=io.BytesIO();pq.write_table(pa.Table.from_pylist(feature_rows),feature_buffer,compression='zstd')
    store.put(feature_key,feature_buffer.getvalue())
    with db:db.execute('INSERT OR IGNORE INTO feature_sets VALUES(?,?,?,?,?,?)',
                       (feature_id,FEATURE_VERSION,dataset_hash,max(f['as_of'] for f in feature_rows),feature_key,json.dumps({'window_candles':24,'interval':'1h'})))
    split=int(len(rows)*.8);holdout=rows[split:];cutoff=holdout[0]['as_of']-48*3600000
    development=[r for r in rows[:split] if r['label_end']<cutoff]
    comparisons=compare_models(development,FEATURE_NAMES)
    means={m:statistics.fmean(x['brier'] for x in comparisons if x['model']==m) for m in {x['model'] for x in comparisons}}
    selected=min(means,key=means.get)
    models={'base_rate':DummyClassifier(strategy='prior'),
            'logistic':make_pipeline(StandardScaler(),LogisticRegression(random_state=42,max_iter=500)),
            'gradient_boosting':HistGradientBoostingClassifier(random_state=42,max_iter=100)}
    model=models[selected]
    x=np.array([[r[f] for f in FEATURE_NAMES] for r in development]);y=np.array([r['label'] for r in development])
    if len(set(y))<2:raise ValueError('training labels contain only one class')
    model.fit(x,y)
    xt=np.array([[r[f] for f in FEATURE_NAMES] for r in holdout]);yt=np.array([r['label'] for r in holdout])
    probabilities=model.predict_proba(xt)[:,1]
    model_id='research-'+identity(exchange,symbol,dataset_hash,FEATURE_VERSION,selected)[:20]
    buffer=io.BytesIO();joblib.dump(model,buffer);key=f'models/{model_id}.joblib';store.put(key,buffer.getvalue())
    metadata={'scope':'historical_candidate_only','exchange':exchange,'symbol':symbol,
              'training_n':len(development),'holdout_n':len(holdout),'dataset_hash':dataset_hash,
              'runtime':{'python':platform.python_version(),'numpy':np.__version__,'sklearn':sklearn.__version__},
              'feature_set_id':feature_id,
              'feature_version':FEATURE_VERSION,'features':FEATURE_NAMES,'selected':selected,
              'comparison':comparisons,'final_holdout_brier':float(brier_score_loss(yt,probabilities)),
              'final_holdout_log_loss':float(log_loss(yt,probabilities,labels=[0,1])),
              'excluded':excluded,'forward_trades':0,'calibrated':False,
              'probability_definition':'target before stop within 48h, conditional on estimated fill',
              'trade_policy':{'net_target':.03,'stop_fraction':.02,'entry_discount':.003,'entry_wait_hours':3,'fee_bps':10,'slippage_bps':5},
              'limitations':['Overlapping labels are purged across splits, not independent trials.',
                             'Holdout results may not be reused repeatedly for model selection.',
                             'Global data does not establish Binance TR execution performance.']}
    if not db.execute('SELECT 1 FROM model_versions WHERE model_id=?',(model_id,)).fetchone():
        register_model(db,model_id,key,metadata)
        with db:db.execute('INSERT INTO training_runs VALUES(?,?,?,?,?,?)',
          (identity(model_id,'training'),now_ms(),'research_complete',model_id,json.dumps(metadata),dataset_hash))
    return {'model_id':model_id,**metadata}
