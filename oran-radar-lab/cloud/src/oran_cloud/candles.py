import csv
import io
import math
import zipfile
from dataclasses import asdict, dataclass
from decimal import Decimal, InvalidOperation

INTERVAL_MS = {'1m': 60_000, '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '1d': 86_400_000}


def decimal_text(value):
    text=format(value,'f')
    return text.rstrip('0').rstrip('.') if '.' in text else text


def timestamp_ms(value):
    n = int(value)
    if 1_000_000_000_000 <= n < 100_000_000_000_000:
        return n, 'ms'
    if 1_000_000_000_000_000 <= n < 100_000_000_000_000_000:
        return n // 1000, 'us'
    raise ValueError(f'unsupported timestamp magnitude: {n}')


@dataclass(frozen=True)
class Candle:
    open_ms: int
    open: str
    high: str
    low: str
    close: str
    volume: str
    close_ms: int
    quote_volume: str
    trades: int
    taker_base: str
    taker_quote: str


def parse_rows(rows, interval, start_ms, end_ms):
    step, good, bad, units, seen, previous = INTERVAL_MS[interval], [], [], set(), {}, None
    for line, row in enumerate(rows, 1):
        if line == 1 and row and str(row[0]).lower() in ('open_time', 'opentime'):
            continue
        try:
            if len(row) < 11:
                raise ValueError('less than 11 kline columns')
            ts, unit = timestamp_ms(row[0]); close_ts, close_unit = timestamp_ms(row[6])
            if unit != close_unit or ts % step or close_ts != ts + step - 1:
                raise ValueError('timestamp unit, alignment or close time invalid')
            if not start_ms <= ts < end_ms:
                raise ValueError('row outside requested period')
            values = [Decimal(str(row[i])) for i in [1, 2, 3, 4, 5, 7, 9, 10]]
            if any(not x.is_finite() for x in values):
                raise ValueError('nonfinite numeric data')
            o, h, l, c, v, q, tb, tq = values
            if min(o, h, l, c) <= 0 or l > min(o, c) or h < max(o, c) or l > h:
                raise ValueError('invalid OHLC range')
            trade_decimal = Decimal(str(row[8]))
            if trade_decimal != trade_decimal.to_integral_value() or trade_decimal < 0:
                raise ValueError('invalid trade count')
            if min(v, q, tb, tq) < 0 or tb > v or tq > q:
                raise ValueError('invalid volume or taker volume')
            candle = Candle(ts, *(decimal_text(x) for x in [o, h, l, c, v]), close_ts,
                            decimal_text(q), int(trade_decimal), decimal_text(tb), decimal_text(tq))
            if ts in seen:
                if seen[ts] != candle:
                    raise ValueError('conflicting duplicate timestamp')
                continue
            if previous is not None and ts < previous:
                raise ValueError('nonmonotonic timestamp')
            previous = ts
            good.append(candle); seen[ts] = candle; units.add(unit)
        except (ValueError, InvalidOperation, IndexError, TypeError) as e:
            bad.append({'line': line, 'reason': str(e), 'row': list(row)})
    gaps, cursor = [], start_ms
    for candle in good:
        if candle.open_ms > cursor:
            gaps.append((cursor, candle.open_ms))
        cursor = candle.open_ms + step
    if cursor < end_ms:
        gaps.append((cursor, end_ms))
    return good, bad, gaps, ','.join(sorted(units))


def zip_rows(blob, max_uncompressed=128 * 1024 * 1024):
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        members = z.infolist()
        if len(members) != 1 or not members[0].filename.endswith('.csv') or members[0].file_size > max_uncompressed:
            raise ValueError('unexpected archive contents or oversized uncompressed data')
        with z.open(members[0]) as f:
            yield from csv.reader(io.TextIOWrapper(f, encoding='utf-8-sig'))


def parquet_bytes(candles):
    import pyarrow as pa
    import pyarrow.parquet as pq
    schema = pa.schema([(k, pa.int64() if k in ('open_ms', 'close_ms', 'trades') else pa.string())
                        for k in Candle.__dataclass_fields__])
    table = pa.Table.from_pylist([asdict(c) for c in candles], schema=schema)
    out = io.BytesIO(); pq.write_table(table, out, compression='zstd')
    return out.getvalue()


def read_parquet(blob):
    import pyarrow.parquet as pq
    rows=pq.read_table(io.BytesIO(blob)).to_pylist()
    for d in rows:
        for k,v in d.items():
            if isinstance(v,str):d[k]=decimal_text(Decimal(v))
    return [Candle(**d) for d in rows]
