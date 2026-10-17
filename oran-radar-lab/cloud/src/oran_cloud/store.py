import hashlib
import os
import tempfile
from pathlib import Path, PurePosixPath


class QuotaExceeded(RuntimeError):
    pass


def checked_key(key):
    p = PurePosixPath(key)
    if p.is_absolute() or '..' in p.parts or not p.parts:
        raise ValueError('invalid object key')
    return key


class LocalStore:
    def __init__(self, root, budget_bytes=8_000_000_000):
        self.root, self.budget_bytes = Path(root), budget_bytes
        self.root.mkdir(parents=True, exist_ok=True)
        self._used = None

    def used_bytes(self):
        if self._used is None:
            self._used = sum(p.stat().st_size for p in self.root.rglob('*') if p.is_file())
        return self._used

    def get(self, key):
        return (self.root / checked_key(key)).read_bytes()

    def put(self, key, blob):
        p = self.root / checked_key(key)
        if p.exists():
            if p.read_bytes() != blob:
                raise ValueError('immutable object conflict')
            return
        if self.used_bytes() + len(blob) > self.budget_bytes:
            raise QuotaExceeded('object storage soft limit reached; ingestion paused')
        p.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=p.parent, prefix='.partial-')
        try:
            with os.fdopen(fd, 'wb') as f:
                f.write(blob); f.flush(); os.fsync(f.fileno())
            os.replace(tmp, p)
            self._used += len(blob)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)


class R2Store:
    """Only constructed on explicit R2 opt-in; no subscription/account creation."""
    def __init__(self, endpoint, bucket, budget_bytes=8_000_000_000, ledger=None):
        if os.environ.get('ORAN_R2_APPROVED') != 'true':
            raise ValueError('R2 billing/subscription approval required')
        from urllib.parse import urlsplit
        host = urlsplit(endpoint).hostname or ''
        if not endpoint.startswith('https://') or not host.endswith('.r2.cloudflarestorage.com'):
            raise ValueError('expected account R2 endpoint')
        import boto3
        self.client = boto3.client('s3', endpoint_url=endpoint, region_name='auto')
        self.bucket, self.budget_bytes = bucket, budget_bytes
        self._used, self.ledger = None, ledger

    def _reserve(self, resource):
        if self.ledger is not None:
            from .quota import reserve
            from datetime import datetime, timezone
            if not reserve(self.ledger, datetime.now(timezone.utc).strftime('%Y-%m'), resource, 1):
                raise QuotaExceeded('R2 operation soft limit reached')

    def used_bytes(self):
        if self._used is not None:
            return self._used
        total = 0
        token=None
        while True:
            self._reserve('r2_class_a_month')
            params={'Bucket':self.bucket}
            if token:params['ContinuationToken']=token
            page=self.client.list_objects_v2(**params)
            total += sum(x['Size'] for x in page.get('Contents', []))
            if not page.get('IsTruncated'):break
            token=page['NextContinuationToken']
        self._used = total
        return self._used

    def get(self, key):
        self._reserve('r2_class_b_month')
        return self.client.get_object(Bucket=self.bucket, Key=checked_key(key))['Body'].read()

    def put(self, key, blob):
        from botocore.exceptions import ClientError
        key = checked_key(key)
        try:
            existing = self.get(key)
        except ClientError as e:
            if e.response['Error']['Code'] not in ('NoSuchKey', '404'):
                raise
        else:
            if existing != blob:
                raise ValueError('immutable object conflict')
            return
        if self.used_bytes() + len(blob) > self.budget_bytes:
            raise QuotaExceeded('R2 soft storage limit reached')
        self._reserve('r2_class_a_month')
        self.client.put_object(Bucket=self.bucket, Key=key, Body=blob, IfNoneMatch='*',
                               Metadata={'sha256': hashlib.sha256(blob).hexdigest()})
        self._used += len(blob)
