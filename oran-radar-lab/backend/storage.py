import json
import math
import sqlite3
import threading
import time
import uuid

FINAL = {"SUCCESS", "STOPPED", "ENTRY_NOT_FILLED", "TIME_EXPIRED", "CANCELLED"}


def now_ms():
    return int(time.time() * 1000)


class Store:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        with self.connect() as db:
            db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS opportunities(
                pair TEXT PRIMARY KEY, coin TEXT NOT NULL, display TEXT NOT NULL,
                price REAL NOT NULL, rally_score INTEGER NOT NULL, state TEXT NOT NULL,
                spread_pct REAL, scenario_json TEXT NOT NULL, features_json TEXT NOT NULL,
                observed_at INTEGER NOT NULL, model_version TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS signals(
                signal_id TEXT PRIMARY KEY, pair TEXT NOT NULL, coin TEXT NOT NULL,
                created_at INTEGER NOT NULL, initial_price REAL NOT NULL,
                entry REAL NOT NULL, entry_expires_at INTEGER NOT NULL,
                target REAL NOT NULL, stop REAL NOT NULL, rally_score INTEGER NOT NULL,
                entry_probability REAL, target_probability REAL, expected_net_pct REAL,
                features_json TEXT NOT NULL, regime TEXT NOT NULL, model_version TEXT NOT NULL,
                state TEXT NOT NULL, outcome TEXT, filled_at INTEGER, closed_at INTEGER,
                fill_observed_price REAL, exit_observed_price REAL, net_return_pct REAL,
                last_event_at INTEGER NOT NULL, last_trade_id INTEGER,
                paper_notional_try REAL NOT NULL, fee_bps REAL NOT NULL, slip_bps REAL NOT NULL,
                evidence TEXT NOT NULL DEFAULT 'trade_stream_touch'
            );
            CREATE UNIQUE INDEX IF NOT EXISTS one_open_signal_per_pair
                ON signals(pair) WHERE outcome IS NULL;
            CREATE INDEX IF NOT EXISTS signals_created ON signals(created_at DESC);
            CREATE TABLE IF NOT EXISTS events(
                event_id INTEGER PRIMARY KEY AUTOINCREMENT, signal_id TEXT NOT NULL,
                occurred_at INTEGER NOT NULL, kind TEXT NOT NULL, observed_price REAL,
                trade_id INTEGER, detail TEXT,
                UNIQUE(signal_id, kind, trade_id)
            );
            CREATE TABLE IF NOT EXISTS devices(
                token TEXT PRIMARY KEY, created_at INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS notifications(
                id INTEGER PRIMARY KEY AUTOINCREMENT, signal_id TEXT, pair TEXT,
                kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
                created_at INTEGER NOT NULL, sent_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS feed_state(
                pair TEXT PRIMARY KEY, trade_id INTEGER, trade_at INTEGER, received_at INTEGER
            );
            CREATE TABLE IF NOT EXISTS paper_positions(
                position_id TEXT PRIMARY KEY, pair TEXT NOT NULL,
                entry REAL NOT NULL, target REAL NOT NULL, stop REAL,
                created_at INTEGER NOT NULL, deadline_at INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'TRACKING', closed_at INTEGER,
                target_hit_at INTEGER, observed_price REAL, observed_at INTEGER,
                gross_return_pct REAL, fee_bps REAL NOT NULL,
                net_return_pct REAL, last_trade_id INTEGER,
                source TEXT NOT NULL DEFAULT 'USER_PAPER'
            );
            CREATE INDEX IF NOT EXISTS paper_open ON paper_positions(pair,state);
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def rows(self, sql, params=()):
        with self.lock, self.connect() as db:
            return [dict(r) for r in db.execute(sql, params).fetchall()]

    def opportunity(self, pair, meta, price, score, state, spread, scenario, features, version, ts):
        with self.lock, self.connect() as db:
            old = db.execute("SELECT state FROM opportunities WHERE pair=?", (pair,)).fetchone()
            db.execute("""INSERT INTO opportunities VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(pair) DO UPDATE SET price=excluded.price, rally_score=excluded.rally_score,
                state=excluded.state, spread_pct=excluded.spread_pct,
                scenario_json=excluded.scenario_json, features_json=excluded.features_json,
                observed_at=excluded.observed_at, model_version=excluded.model_version""",
                (pair, meta["coin"], meta["display"], price, score, state, spread,
                 json.dumps(scenario), json.dumps(features), ts, version))
            if state in ("SCOUT", "SETUP") and (not old or old["state"] != state):
                self.queue(db, None, pair, state, state + " • " + meta["display"],
                           f"Rally Score {score}/100. İzleme durumu; emir önerisi değildir.", ts)

    @staticmethod
    def queue(db, signal_id, pair, kind, title, body, ts):
        db.execute("""INSERT INTO notifications(signal_id,pair,kind,title,body,created_at)
                      VALUES(?,?,?,?,?,?)""", (signal_id, pair, kind, title, body, ts))

    def create_signal(self, pair, meta, price, score, scenario, features, regime, version,
                      notional, fee_bps, slip_bps, ts):
        with self.lock, self.connect() as db:
            recent = db.execute("SELECT created_at FROM signals WHERE pair=? ORDER BY created_at DESC LIMIT 1",
                                (pair,)).fetchone()
            if recent and ts - recent["created_at"] < 12 * 3600_000:
                return None
            signal_id = str(uuid.uuid4())
            try:
                db.execute("""INSERT INTO signals(
                    signal_id,pair,coin,created_at,initial_price,entry,entry_expires_at,target,stop,
                    rally_score,entry_probability,target_probability,expected_net_pct,features_json,
                    regime,model_version,state,last_event_at,paper_notional_try,fee_bps,slip_bps)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (signal_id, pair, meta["coin"], ts, price, scenario["entry"], ts + 8 * 3600_000,
                     scenario["target"], scenario["stop"], score,
                     scenario["entry_probabilities"]["6"], scenario["target_before_stop"],
                     scenario["expected_net_pct"], json.dumps(features), regime, version, "ENTRY",
                     ts, notional, fee_bps, slip_bps))
            except sqlite3.IntegrityError:
                return None
            self.event(db, signal_id, ts, "ENTRY", price, None, "paper level; not a placed order")
            self.queue(db, signal_id, pair, "ENTRY", "Yeni limit alış adayı • " + meta["display"],
                       f"Binance TR {price:g} TL • Alış {scenario['entry']:g} TL (8 saat) • "
                       f"Hedef {scenario['target']:g} TL • Stop {scenario['stop']:g} TL • "
                       f"48s hedef/stop öncesi tahmin %{scenario['target_before_stop']*100:.0f} "
                       "(ön kalibrasyon)", ts)
            return signal_id

    @staticmethod
    def event(db, signal_id, ts, kind, price, trade_id, detail=""):
        db.execute("""INSERT OR IGNORE INTO events(signal_id,occurred_at,kind,observed_price,trade_id,detail)
                      VALUES(?,?,?,?,?,?)""", (signal_id, ts, kind, price, trade_id, detail))

    @staticmethod
    def cost_pct(signal):
        features = json.loads(signal["features_json"])
        return (signal["fee_bps"] + signal["slip_bps"]) / 100 + features.get("spread_pct", 0) / 2

    def trade(self, pair, trade_id, trade_at, price, received_at=None):
        received_at = received_at or now_ms()
        with self.lock, self.connect() as db:
            last = db.execute("SELECT trade_id,trade_at FROM feed_state WHERE pair=?", (pair,)).fetchone()
            if last and trade_id <= last["trade_id"]:
                return
            db.execute("""INSERT INTO feed_state VALUES(?,?,?,?)
                ON CONFLICT(pair) DO UPDATE SET trade_id=excluded.trade_id,
                trade_at=excluded.trade_at,received_at=excluded.received_at""",
                (pair, trade_id, trade_at, received_at))
            for position in db.execute("SELECT * FROM paper_positions WHERE pair=? AND state='TRACKING'",
                                       (pair,)).fetchall():
                if trade_at < position["created_at"]:
                    continue
                state, exit_price = "TRACKING", None
                if trade_at >= position["deadline_at"]:
                    state, exit_price = "TIME_EXPIRED", price
                elif position["stop"] is not None and price <= position["stop"]:
                    state, exit_price = "STOPPED", price
                elif price >= position["target"]:
                    state, exit_price = "SUCCESS", position["target"]
                gross = (exit_price / position["entry"] - 1) * 100 if exit_price is not None else None
                db.execute("""UPDATE paper_positions SET state=?,observed_price=?,observed_at=?,
                    last_trade_id=?,closed_at=?,target_hit_at=?,gross_return_pct=?,net_return_pct=?
                    WHERE position_id=?""", (state, price, trade_at, trade_id,
                    trade_at if exit_price is not None else None,
                    trade_at if state == "SUCCESS" else None, gross,
                    gross - position["fee_bps"] / 100 if gross is not None else None,
                    position["position_id"]))
            active = db.execute("SELECT * FROM signals WHERE pair=? AND outcome IS NULL", (pair,)).fetchall()
            for signal in active:
                sid = signal["signal_id"]
                if trade_at < signal["created_at"]:
                    continue
                if signal["state"] == "ENTRY":
                    if trade_at > signal["entry_expires_at"]:
                        self.finish(db, signal, "ENTRY_NOT_FILLED", "EXPIRED ENTRY", trade_at,
                                    price, trade_id, None)
                    elif price <= signal["entry"]:
                        db.execute("""UPDATE signals SET state='TARGET ACTIVE',filled_at=?,
                            fill_observed_price=?,last_event_at=?,last_trade_id=? WHERE signal_id=?""",
                            (trade_at, price, trade_at, trade_id, sid))
                        self.event(db, sid, trade_at, "BOUGHT", price, trade_id,
                                   "Trade touched limit; paper fill assumed at proposed entry")
                        self.event(db, sid, trade_at, "TARGET ACTIVE", price, trade_id)
                        self.queue(db, sid, pair, "ENTRY_HIT", "Alış seviyesi görüldü",
                                   f"{pair} Binance TR işlemi {price:g} TL; sanal alış seviyesi "
                                   f"{signal['entry']:g} TL. Hedef {signal['target']:g} TL. "
                                   "48 saatlik takip başladı.", trade_at)
                elif signal["state"] == "TARGET ACTIVE" and trade_at > signal["filled_at"]:
                    if trade_at >= signal["filled_at"] + 48 * 3600_000:
                        net = (price / signal["entry"] - 1) * 100 - self.cost_pct(signal)
                        self.finish(db, signal, "TIME_EXPIRED", "EXPIRED TRADE", trade_at,
                                    price, trade_id, net)
                    elif price <= signal["stop"]:
                        # A gap below the stop exits at the first observed lower trade.
                        net = (min(price, signal["stop"]) / signal["entry"] - 1) * 100 - self.cost_pct(signal)
                        self.finish(db, signal, "STOPPED", "STOP HIT", trade_at, price, trade_id, net)
                    elif price >= signal["target"]:
                        net = (signal["target"] / signal["entry"] - 1) * 100 - self.cost_pct(signal)
                        self.finish(db, signal, "SUCCESS", "TARGET HIT", trade_at, price, trade_id, net)

    def finish(self, db, signal, outcome, state, ts, observed, trade_id, net):
        sid = signal["signal_id"]
        updated = db.execute("""UPDATE signals SET state=?,outcome=?,closed_at=?,
            exit_observed_price=?,net_return_pct=?,last_event_at=?,last_trade_id=?
            WHERE signal_id=? AND outcome IS NULL""",
            (state, outcome, ts, observed, round(net, 4) if net is not None else None,
             ts, trade_id, sid)).rowcount
        if not updated:
            return
        self.event(db, sid, ts, state, observed, trade_id, outcome)
        name = {"SUCCESS": "Hedef görüldü", "STOPPED": "Stop görüldü",
                "TIME_EXPIRED": "48 saat doldu", "ENTRY_NOT_FILLED": "Alış seviyesi görülmedi",
                "CANCELLED": "Sinyal iptal edildi"}[outcome]
        body = f"{signal['pair']} • Alış {signal['entry']:g} TL • Hedef {signal['target']:g} TL"
        if net is not None:
            body += f" • Tahmini net kağıt performansı %{net:+.2f}"
        self.queue(db, sid, signal["pair"], outcome, name, body, ts)

    def expire(self, ts, market_fresh):
        with self.lock, self.connect() as db:
            for signal in db.execute("""SELECT * FROM signals WHERE outcome IS NULL AND state='ENTRY'
                                      AND entry_expires_at<?""", (ts,)).fetchall():
                if market_fresh(signal["pair"], signal["entry_expires_at"]):
                    self.finish(db, signal, "ENTRY_NOT_FILLED", "EXPIRED ENTRY",
                                signal["entry_expires_at"], None, None, None)
            # Exit expiry needs a trade >= deadline to establish price. trade() performs it.

    def cancel_entry(self, pair, ts, reason):
        with self.lock, self.connect() as db:
            for signal in db.execute("SELECT * FROM signals WHERE pair=? AND state='ENTRY' AND outcome IS NULL",
                                     (pair,)).fetchall():
                self.finish(db, signal, "CANCELLED", "CANCELLED", ts, None, None, None)
                self.event(db, signal["signal_id"], ts, "CANCEL_REASON", None, None, reason)

    def feed_age(self, pair, ts):
        rows = self.rows("SELECT received_at FROM feed_state WHERE pair=?", (pair,))
        return ts - rows[0]["received_at"] if rows else None

    def register_device(self, token):
        with self.lock, self.connect() as db:
            db.execute("""INSERT INTO devices(token,created_at) VALUES(?,?)
                ON CONFLICT(token) DO UPDATE SET enabled=1""", (token, now_ms()))

    def create_paper_position(self, position_id, pair, entry, target, stop, fee_bps, ts):
        uuid.UUID(position_id)
        values = [entry, target, fee_bps] + ([stop] if stop is not None else [])
        if (not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
                    for v in values) or entry <= 0 or target <= entry or
            (stop is not None and not 0 < stop < entry) or not 0 <= fee_bps <= 100):
            raise ValueError("Geçerli alış, daha yüksek hedef ve daha düşük isteğe bağlı stop girin.")
        with self.lock, self.connect() as db:
            existing = db.execute("SELECT * FROM paper_positions WHERE position_id=?", (position_id,)).fetchone()
            if existing:
                if (existing["pair"], existing["entry"], existing["target"], existing["stop"]) != (pair, entry, target, stop):
                    raise ValueError("Bu kayıt kimliği farklı fiyatlarla zaten kullanılmış.")
                return dict(existing)
            db.execute("""INSERT INTO paper_positions(position_id,pair,entry,target,stop,created_at,
                deadline_at,fee_bps) VALUES(?,?,?,?,?,?,?,?)""",
                (position_id, pair, entry, target, stop, ts, ts + 48 * 3600_000, fee_bps))
            return dict(db.execute("SELECT * FROM paper_positions WHERE position_id=?", (position_id,)).fetchone())

    def snapshot(self, sql, params=()):
        records = self.rows(sql, params)
        for record in records:
            if "features_json" in record:
                record["features"] = json.loads(record.pop("features_json"))
            if "scenario_json" in record:
                record["scenario"] = json.loads(record.pop("scenario_json"))
        return records
