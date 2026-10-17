from .db import now_ms

LIMITS = {'workers_requests_day':100_000,'d1_rows_read_day':5_000_000,
          'd1_rows_written_day':100_000,'d1_storage_bytes':5_000_000_000,
          'r2_storage_bytes':10_000_000_000,'r2_class_a_month':1_000_000,
          'r2_class_b_month':10_000_000,'actions_minutes_month':2_000}


def reserve(db, period, resource, amount, soft_fraction=.8):
    """Atomic local reservation. Account-wide measured usage must also be supplied.

    This is a project budget, not a provider-side billing cap.
    """
    if resource not in LIMITS or amount<0:
        raise ValueError('invalid quota reservation')
    maximum=int(LIMITS[resource]*soft_fraction)
    with db:
        db.execute('''INSERT OR IGNORE INTO usage_metrics VALUES(?,?,0,?,?)''',
                   (period,resource,maximum,now_ms()))
        r=db.execute('''UPDATE usage_metrics SET used=used+?,observed_at=?
          WHERE period=? AND resource=? AND used+?<=max_allowed''',(amount,now_ms(),period,resource,amount))
        return r.rowcount==1
