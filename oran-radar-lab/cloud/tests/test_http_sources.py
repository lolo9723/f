import hashlib
import io
import zipfile
import requests
import pytest
from oran_cloud.http import Http,AccessBlocked
from oran_cloud.sources import tr_symbols,archive_periods
from oran_cloud.ingestion import fetch_task
from oran_cloud.candles import zip_rows


class Response:
    def __init__(self,status=200,headers=None,data=None,content=b''):
        self.status_code=status;self.headers=headers or {};self.data=data;self.content=content;self.text=content.decode(errors='replace')
    def json(self):return self.data
    def raise_for_status(self):
        if self.status_code>=400:raise requests.HTTPError(str(self.status_code))


class Session:
    def __init__(self,responses):self.responses=iter(responses);self.calls=0
    def get(self,*args,**kwargs):self.calls+=1;return next(self.responses)


@pytest.mark.parametrize('code',[403,418,451])
def test_access_blocks_stop_without_bypass(code):
    session=Session([Response(code)]);h=Http(session,sleep=lambda _:None,min_interval=0)
    with pytest.raises(AccessBlocked):h.get('https://www.binance.tr/test')
    assert session.calls==1


def test_429_retry_after_respected():
    sleeps=[];h=Http(Session([Response(429,{'Retry-After':'3'}),Response()]),sleep=sleeps.append,min_interval=0)
    h.get('https://data.binance.vision/test');assert sleeps==[3]


def test_long_retry_is_deferred_not_blocked():
    h=Http(Session([Response(429,{'Retry-After':'3600'})]),sleep=lambda _:None,min_interval=0)
    with pytest.raises(RuntimeError,match='defer'):h.get('https://data.binance.vision/test')


def test_untrusted_url_cannot_be_fetched():
    with pytest.raises(ValueError):Http().get('https://attacker.example/data')


def test_tr_inventory_retains_native_symbol_type():
    payload={'code':0,'data':{'list':[{'symbol':'BTC_TRY','baseAsset':'BTC','quoteAsset':'TRY','type':3,'spotTradingEnable':1},{'symbol':'BTC_USDT','quoteAsset':'USDT'}]}}
    h=Http(Session([Response(data=payload)]),min_interval=0)
    s=tr_symbols(h);assert len(s)==1 and s[0]['type']==3 and s[0]['symbol']=='BTC_TRY'


def test_checksum_mismatch_prevents_parsing():
    h=Http(Session([Response(content=b'bad archive'),Response(content=b'0'*64)]),sleep=lambda _:None,min_interval=0)
    t={'source':'archive','period':'2025-01','symbol':'BTCUSDT','interval':'1h'}
    with pytest.raises(ValueError,match='checksum mismatch'):fetch_task(None,h,t)


def test_archive_shape_and_size_checked():
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:z.writestr('a.csv','1,2,3\n');z.writestr('b.csv','1,2,3\n')
    with pytest.raises(ValueError):list(zip_rows(out.getvalue()))


def test_catalog_only_plans_real_zip_files():
    text=b'''<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated><Contents><Key>data/spot/monthly/klines/BTCUSDT/1d/BTCUSDT-1d-2025-01.zip</Key></Contents><Contents><Key>data/spot/monthly/klines/BTCUSDT/1d/BTCUSDT-1d-2025-01.zip.CHECKSUM</Key></Contents></ListBucketResult>'''
    h=Http(Session([Response(content=text)]),min_interval=0)
    periods=archive_periods(h,'BTCUSDT','1d')
    assert len(periods)==1 and periods[0][0]=='2025-01'
