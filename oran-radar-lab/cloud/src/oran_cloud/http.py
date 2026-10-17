import email.utils
import random
import time
from urllib.parse import urlsplit
import requests

ALLOWED = {'data.binance.vision', 'data-api.binance.vision', 'www.binance.tr',
           'api.binance.me', 'cloudme-tr.2meta.app', 's3-ap-northeast-1.amazonaws.com'}


class AccessBlocked(RuntimeError):
    pass


class Http:
    """GET-only public data client. No proxy rotation, keys or access bypass."""
    def __init__(self, session=None, sleep=time.sleep, attempts=4, min_interval=0.25):
        self.session = session or requests.Session()
        self.sleep, self.attempts, self.min_interval = sleep, attempts, min_interval
        self.last_request = 0.0

    def get(self, url, params=None):
        if urlsplit(url).scheme != 'https' or urlsplit(url).hostname not in ALLOWED:
            raise ValueError('unapproved public-data host')
        for attempt in range(self.attempts):
            delay = self.min_interval - (time.monotonic() - self.last_request)
            if delay > 0:
                self.sleep(delay)
            self.last_request = time.monotonic()
            try:
                r = self.session.get(url, params=params, timeout=(10, 30), allow_redirects=False,
                                     headers={'User-Agent': 'OranRadarCloud/4.0 public-research'})
            except (requests.ConnectionError, requests.Timeout):
                if attempt + 1 == self.attempts:
                    raise
                self.sleep(min(30, 2 ** attempt) + random.random())
                continue
            if r.status_code in (403, 418, 451):
                raise AccessBlocked(f'HTTP {r.status_code}: {url}; access restriction respected')
            if r.status_code == 429 or 500 <= r.status_code < 600:
                if attempt + 1 == self.attempts:
                    r.raise_for_status()
                retry = r.headers.get('Retry-After', '')
                try:
                    wait = float(retry)
                except ValueError:
                    try:
                        wait = email.utils.parsedate_to_datetime(retry).timestamp() - time.time()
                    except (TypeError, ValueError):
                        wait = min(30, 2 ** attempt) + random.random()
                if wait > 60:
                    raise RuntimeError(f'rate limit: defer task for {wait:.0f}s')
                self.sleep(max(0, wait))
                continue
            if 300 <= r.status_code < 400:
                raise ValueError('redirect refused; verify updated official endpoint')
            r.raise_for_status()
            return r

    def json(self, url, params=None):
        data = self.get(url, params).json()
        if isinstance(data, dict) and 'code' in data and data['code'] != 0:
            raise ValueError(f'public API error: {data.get("code")} {data.get("msg")}')
        return data
