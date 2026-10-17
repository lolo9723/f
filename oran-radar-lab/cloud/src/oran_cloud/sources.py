import xml.etree.ElementTree as ET
import re
from .db import inventory, issue, identity, now_ms

GLOBAL_API = 'https://data-api.binance.vision'
TR_API = 'https://www.binance.tr'
ARCHIVE = 'https://data.binance.vision'
LISTING = 'https://s3-ap-northeast-1.amazonaws.com/data.binance.vision'


def global_symbols(http):
    data = http.json(GLOBAL_API + '/api/v3/exchangeInfo')
    return data['symbols']  # Spot endpoint also retains inactive/retired markets.


def tr_symbols(http):
    data = http.json(TR_API + '/open/v1/common/symbols')
    records = []
    for s in data['data']['list']:
        if s['quoteAsset'] != 'TRY':
            continue
        s = dict(s)
        s['status'] = s.get('status', 'enabled' if s.get('spotTradingEnable') == 1 else 'disabled')
        records.append(s)
    return records


def archive_symbols(http):
    """Public bucket listing includes retired symbols; never infer base/quote by slicing."""
    prefix = 'data/spot/monthly/klines/'
    marker, result = '', set()
    while True:
        params = {'prefix': prefix, 'delimiter': '/'}
        if marker:
            params['marker'] = marker
        root = ET.fromstring(http.get(LISTING, params).content)
        ns = {'s': 'http://s3.amazonaws.com/doc/2006-03-01/'}
        prefixes = [x.text for x in root.findall('s:CommonPrefixes/s:Prefix', ns)]
        result.update(p[len(prefix):].strip('/') for p in prefixes)
        if root.findtext('s:IsTruncated', 'false', ns) != 'true':
            break
        next_marker = root.findtext('s:NextMarker', '', ns) or (prefixes[-1] if prefixes else '')
        if not next_marker or next_marker == marker:
            raise ValueError('archive pagination did not advance')
        marker = next_marker
    return [{'symbol': s, 'status': 'archive_only'} for s in sorted(result)]


def archive_periods(http,symbol,interval):
    prefix=f'data/spot/monthly/klines/{symbol}/{interval}/'
    marker='';periods=[];ns={'s':'http://s3.amazonaws.com/doc/2006-03-01/'}
    pattern=re.compile(re.escape(prefix+symbol+'-'+interval+'-')+r'(\d{4}-\d{2})\.zip$')
    while True:
        params={'prefix':prefix}
        if marker:params['marker']=marker
        root=ET.fromstring(http.get(LISTING,params).content)
        keys=[n.text for n in root.findall('s:Contents/s:Key',ns)]
        for key in keys:
            m=pattern.fullmatch(key)
            if m:periods.append((m[1],ARCHIVE+'/'+key))
        if root.findtext('s:IsTruncated','false',ns)!='true':break
        next_marker=root.findtext('s:NextMarker','',ns) or (keys[-1] if keys else '')
        if not next_marker or next_marker==marker:raise ValueError('catalog pagination did not advance')
        marker=next_marker
    return sorted(set(periods))


def catalog_batch(db,http,max_scans=12,intervals=('1d','1h'),symbols=None):
    """Discover actual archive files BEFORE planning the universe backfill.

    Absence from the archive is unverified coverage, not evidence of non-trading.
    """
    records=symbols or [r['symbol'] for r in db.execute("SELECT symbol FROM symbols WHERE exchange='binance_global' ORDER BY symbol")]
    done=0
    for interval in intervals:
        for symbol in records:
            if done>=max_scans:return done
            old=db.execute('SELECT checked_at FROM archive_catalog_scans WHERE symbol=? AND interval=?',(symbol,interval)).fetchone()
            if old and old['checked_at']>now_ms()-7*86400_000:continue
            status,error='complete',None
            try:
                for period,url in archive_periods(http,symbol,interval):
                    with db:db.execute('INSERT INTO archive_catalog VALUES(?,?,?,?,?,?) ON CONFLICT(exchange,symbol,interval,period) DO UPDATE SET observed_at=excluded.observed_at',('binance_global',symbol,interval,period,url,now_ms()))
            except Exception as e:
                status,error='failed',str(e)
            with db:db.execute('INSERT INTO archive_catalog_scans VALUES(?,?,?,?,?) ON CONFLICT(symbol,interval) DO UPDATE SET checked_at=excluded.checked_at,status=excluded.status,error=excluded.error',(symbol,interval,now_ms(),status,error))
            done+=1
    return done


def discover(db, http, include_archive=True):
    results = {}
    for exchange, source, loader in [
        ('binance_global', GLOBAL_API, global_symbols),
        ('binance_tr', TR_API, tr_symbols),
        *([('binance_global', LISTING, archive_symbols)] if include_archive else []),
    ]:
        try:
            records = loader(http)
            inventory(db, exchange, records, source)
            results[source] = {'status': 'complete', 'count': len(records)}
        except Exception as e:
            with db:
                db.execute('INSERT INTO inventory_runs VALUES(?,?,?,?,?,?,?)',
                           (identity(source, now_ms()), exchange, source, now_ms(), 'failed', None, str(e)))
                issue(db, 'inventory:' + exchange, 'inventory_failure', str(e))
            results[source] = {'status': 'failed', 'error': str(e)}
    return results


def tr_kline_url(symbol_type):
    if symbol_type == 1:
        return 'https://api.binance.me/api/v1/klines'
    if symbol_type == 3:
        return 'https://cloudme-tr.2meta.app/api/v1/klines'
    raise ValueError(f'TR symbol type {symbol_type} has no verified REST kline route')


def rest_klines(http, exchange, symbol, interval, start_ms, end_ms, symbol_type=None):
    url = GLOBAL_API + '/api/v3/klines' if exchange == 'binance_global' else tr_kline_url(symbol_type)
    cursor = start_ms
    while cursor < end_ms:
        data = http.json(url, {'symbol': symbol.replace('_', '') if symbol_type == 1 else symbol,
                              'interval': interval, 'startTime': cursor, 'endTime': end_ms - 1, 'limit': 1000})
        rows = data['data'] if isinstance(data, dict) else data
        if not rows:
            break
        yield rows, url
        last = int(rows[-1][0])
        if last < cursor:
            raise ValueError('REST pagination did not advance')
        cursor = last + 1
