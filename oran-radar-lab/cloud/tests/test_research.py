import json
from dataclasses import replace
import pytest
from oran_cloud.candles import Candle
from oran_cloud.research import TradeSpec,evaluate,features,performance,purged_walk_forward,register_model,promote,rollback
from oran_cloud.db import connect
from oran_cloud.imports import import_dataset
from oran_cloud.store import LocalStore

START=1735689600000
HOUR=3600000


def bar(i,o=100,h=102,l=98,c=100):
    return Candle(START+i*HOUR,str(o),str(h),str(l),str(c),'10',START+(i+1)*HOUR-1,'1000',10,'5','500')


def spec(**kwargs):
    return TradeSpec(**{'predicted_at':START,'entry_expires_at':START+2*HOUR,'entry':100,'target':110,'stop':90,'horizon_h':1,**kwargs})


def test_features_are_prefix_invariant():
    prefix=[bar(i,c=100+i) for i in range(30)]
    a=features(prefix)
    b=features(prefix+[bar(30,c=1000)])
    assert a==b[:len(a)]
    assert a[-1]['as_of']==prefix[-1].close_ms+1


def test_feature_windows_reset_on_missing_bars():
    assert features([bar(i) for i in range(12)]+[bar(i) for i in range(13,26)])==[]


def test_pending_not_failure():
    assert evaluate(spec(),[bar(0)],START+HOUR//2)['status']=='pending'


def test_ambiguous_stop_target_not_profit():
    result=evaluate(spec(),[bar(0,h=112,l=88)],START+HOUR)
    assert result['status']=='ambiguous' and result['net_return'] is None


def test_entry_candle_target_before_fill_ambiguous():
    assert evaluate(spec(),[bar(0,o=105,h=112,l=99)],START+HOUR)['status']=='ambiguous'


def test_limit_touch_is_not_confirmed_fill():
    r=evaluate(spec(),[bar(0,o=105,h=106,l=100),bar(1,o=105,h=106,l=100)],START+2*HOUR)
    assert r['status']=='unfilled'


def test_costs_reduce_profit():
    r=evaluate(spec(),[bar(0,h=111,l=99)],START+HOUR)
    assert r['status']=='target' and 0<r['net_return']<.1 and r['fill_estimated']


def test_missing_bar_unverifiable():
    assert evaluate(spec(horizon_h=3),[bar(0),bar(2)],START+3*HOUR)['status']=='unverifiable'


def test_timeout_after_full_holding_period():
    assert evaluate(spec(),[bar(0)],START+HOUR)['status']=='timeout'


def test_no_profit_on_unseen_future():
    assert evaluate(spec(),[bar(0,h=120)],START+HOUR-1)['status']=='pending'


def test_purging_and_embargo():
    rows=[{'as_of':i*HOUR,'label_end':(i+48)*HOUR} for i in range(700)]
    folds=list(purged_walk_forward(rows))
    assert folds
    for train,test in folds:assert max(x['label_end'] for x in train)<min(x['as_of'] for x in test)-48*HOUR


def test_performance_counts_unresolved_separately():
    r=performance([{'status':'pending','net_return':None},{'status':'target','net_return':.05}])
    assert r['trades']==1 and r['excluded']['pending']==1 and r['real_trades']==0


def test_model_gate_and_rollback(tmp_path):
    db=connect(tmp_path/'db')
    for m in ['v1','v2']:register_model(db,m,'models/'+m,{})
    with pytest.raises(ValueError):promote(db,'v2',{})
    evidence={'forward_trades':200,'net_expectancy':.01,'brier':.1,'champion_brier':.2,'drawdown':-.05,'max_drawdown':.1,'stable_folds':3}
    with pytest.raises(ValueError,match='ledger'):promote(db,'v1',evidence)
    for model in ['v1','v2']:
        for i in range(100):
            sid=model+str(i)
            db.execute("INSERT INTO signals VALUES(?,?,1,?,1,100,110,90,2,NULL,NULL,NULL,'paper_forward','{}')",(sid,'binance_tr:spot:TEST_TRY',model))
            db.execute("INSERT INTO signal_outcomes VALUES(?,48,2,'target',?)",(sid,json.dumps({'net_return':.01})))
    db.commit()
    promote(db,'v1',evidence);promote(db,'v2',evidence);rollback(db,'v1')
    assert db.execute("SELECT model_id FROM model_versions WHERE status='champion'").fetchone()[0]=='v1'


def test_import_cannot_automatically_enter_training(tmp_path):
    db=connect(tmp_path/'db');store=LocalStore(tmp_path/'objects');p=tmp_path/'data.csv'
    p.write_text('known,event,x\n100,200,1\n')
    meta={'source':'user-test-fixture','methodology':'test','created_at':200,'as_of':200,'version':'v1'}
    result=import_dataset(db,store,p,meta,{'as_of':'known','event_time':'event','x':'x'})
    assert result['training_eligible'] is False
    p.write_text('known,event,x\n300,200,1\n')
    with pytest.raises(ValueError):import_dataset(db,store,p,meta,{'as_of':'known','event_time':'event','x':'x'})
