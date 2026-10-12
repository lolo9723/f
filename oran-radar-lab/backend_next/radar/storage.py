"""Immutable payloads + a fenced, atomic pointer. Failed work never replaces good data."""

import gzip
import json
import os
import time
import uuid
from contextlib import contextmanager
from pathlib import Path


class LeaseLost(RuntimeError):
    pass


def encode(value):
    return gzip.compress(
        json.dumps(value, allow_nan=False, separators=(",", ":")).encode()
    )


def decode(value):
    return json.loads(gzip.decompress(value))


class LocalStore:
    runtime = "LOCAL"

    def __init__(self, root="local-data"):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def locked(self):
        import fcntl

        with (self.root / ".lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield

    def _metadata(self, pair):
        path = self.root / f"{pair}.json"
        return json.loads(path.read_text()) if path.exists() else {"pair": pair}

    def metadata(self, pair):
        with self.locked():
            return self._metadata(pair)

    def heartbeat(self, now):
        with self.locked():
            self._write("SCHEDULER", {"success_at": now})

    def _write(self, pair, value):
        temporary = self.root / (uuid.uuid4().hex + ".tmp")
        temporary.write_text(json.dumps(value, allow_nan=False))
        os.replace(temporary, self.root / f"{pair}.json")

    def acquire(self, pair, job, now, lease_ms=950000):
        with self.locked():
            meta = self._metadata(pair)
            if meta.get("lease_until", 0) > now:
                return None
            token = uuid.uuid4().hex
            meta.update(
                lease=token,
                lease_until=now + lease_ms,
                job=job,
                attempted_at=now,
                stage="STARTED",
            )
            self._write(pair, meta)
            return token

    def read(self, pair, public=False):
        meta = self.metadata(pair)
        key = meta.get("public_key" if public else "key")
        return decode((self.root / key).read_bytes()) if key else None

    def commit(self, pair, token, state, public, now):
        revision = uuid.uuid4().hex
        key, public_key = (
            f"{pair}-{revision}.json.gz",
            f"{pair}-{revision}-public.json.gz",
        )
        (self.root / key).write_bytes(encode(state))
        (self.root / public_key).write_bytes(encode(public))
        with self.locked():
            meta = self._metadata(pair)
            if meta.get("lease") != token or meta.get("lease_until", 0) <= now:
                raise LeaseLost(pair)
            meta.update(
                key=key,
                public_key=public_key,
                revision=revision,
                success_at=now,
                last_closed_at=state["bars"][-1]["time"] if state["bars"] else None,
                stage="COMPLETED",
                error=None,
                lease_until=0,
                backfill_complete=state["backfill_complete"],
                trained_at=state.get("trained_at"),
                stop_trained_at=state.get("stop_trained_at"),
                entry_trained_at=state.get("entry_trained_at"),
                stages=state["stages"],
            )
            self._write(pair, meta)

    def fail(self, pair, token, error, now):
        with self.locked():
            meta = self._metadata(pair)
            if meta.get("lease") == token:
                meta.update(
                    error=str(error)[:1000],
                    stage="FAILED",
                    lease_until=0,
                    failed_at=now,
                )
                self._write(pair, meta)

    def positions(self):
        with self.locked():
            return [json.loads(p.read_text()) for p in self.root.glob("PAPER-*.json")]

    def create_position(self, position):
        with self.locked():
            key = "PAPER-" + position["position_id"]
            existing = self._metadata(key)
            if "position_id" in existing:
                if any(
                    existing[k] != position[k]
                    for k in ("pair", "entry", "target", "stop")
                ):
                    raise ValueError("Position UUID already used with different levels")
                return existing
            self._write(key, position)
            return position

    def save_position(self, position, revision):
        with self.locked():
            key = "PAPER-" + position["position_id"]
            if self._metadata(key).get("revision") != revision:
                raise LeaseLost(key)
            position["revision"] = revision + 1
            self._write(key, position)


class CloudStore:
    runtime = "AWS_SERVERLESS"

    def __init__(self):
        import boto3

        self.table = boto3.resource("dynamodb").Table(os.environ["STATE_TABLE"])
        self.s3 = boto3.client("s3")
        self.bucket = os.environ["DATA_BUCKET"]
        self.paper = boto3.resource("dynamodb").Table(os.environ["PAPER_TABLE"])

    def metadata(self, pair):
        item = self.table.get_item(Key={"pk": pair}, ConsistentRead=True).get(
            "Item", {"pk": pair}
        )
        # Store number fields as native integers, payload data in gzip S3 objects.
        return json.loads(json.dumps(item, default=lambda n: int(n)))

    def heartbeat(self, now):
        self.table.update_item(
            Key={"pk": "SCHEDULER"},
            UpdateExpression="SET success_at=:now",
            ExpressionAttributeValues={":now": now},
        )

    def acquire(self, pair, job, now, lease_ms=950000):
        from botocore.exceptions import ClientError

        token = uuid.uuid4().hex
        try:
            self.table.update_item(
                Key={"pk": pair},
                UpdateExpression="SET lease=:token, lease_until=:until, job=:job, attempted_at=:now, #stage=:stage",
                ConditionExpression="attribute_not_exists(lease_until) OR lease_until<=:now",
                ExpressionAttributeNames={"#stage": "stage"},
                ExpressionAttributeValues={
                    ":token": token,
                    ":until": now + lease_ms,
                    ":job": job,
                    ":now": now,
                    ":stage": "STARTED",
                },
            )
            return token
        except ClientError as exc:
            if exc.response["Error"]["Code"] == "ConditionalCheckFailedException":
                return None
            raise

    def read(self, pair, public=False):
        key = self.metadata(pair).get("public_key" if public else "key")
        if not key:
            return None
        return decode(self.s3.get_object(Bucket=self.bucket, Key=key)["Body"].read())

    def commit(self, pair, token, state, public, now):
        from botocore.exceptions import ClientError

        revision = uuid.uuid4().hex
        key, public_key = (
            f"{pair}/{revision}.json.gz",
            f"{pair}/{revision}-public.json.gz",
        )
        for object_key, value in ((key, state), (public_key, public)):
            self.s3.put_object(
                Bucket=self.bucket,
                Key=object_key,
                Body=encode(value),
                ContentType="application/json",
                ContentEncoding="gzip",
            )
        try:
            self.table.update_item(
                Key={"pk": pair},
                UpdateExpression="SET #key=:key, public_key=:public, revision=:rev, success_at=:now, "
                "last_closed_at=:closed, #stage=:stage, lease_until=:zero, backfill_complete=:backfill, "
                "trained_at=:trained, stop_trained_at=:stop_trained, entry_trained_at=:entry_trained, stages=:stages REMOVE #error",
                ConditionExpression="lease=:token AND lease_until>:now",
                ExpressionAttributeNames={
                    "#key": "key",
                    "#stage": "stage",
                    "#error": "error",
                },
                ExpressionAttributeValues={
                    ":key": key,
                    ":public": public_key,
                    ":rev": revision,
                    ":now": now,
                    ":closed": state["bars"][-1]["time"] if state["bars"] else 0,
                    ":stage": "COMPLETED",
                    ":zero": 0,
                    ":token": token,
                    ":backfill": state["backfill_complete"],
                    ":trained": state.get("trained_at", 0),
                    ":stop_trained": state.get("stop_trained_at", 0),
                    ":entry_trained": state.get("entry_trained_at", 0),
                    ":stages": state["stages"],
                },
            )
        except ClientError as exc:
            if exc.response["Error"]["Code"] == "ConditionalCheckFailedException":
                raise LeaseLost(pair) from exc
            raise

    def fail(self, pair, token, error, now):
        from botocore.exceptions import ClientError

        try:
            self.table.update_item(
                Key={"pk": pair},
                UpdateExpression="SET #error=:error, #stage=:stage, lease_until=:zero, failed_at=:now",
                ConditionExpression="lease=:token",
                ExpressionAttributeNames={"#error": "error", "#stage": "stage"},
                ExpressionAttributeValues={
                    ":error": str(error)[:1000],
                    ":stage": "FAILED",
                    ":zero": 0,
                    ":now": now,
                    ":token": token,
                },
            )
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ConditionalCheckFailedException":
                raise

    def positions(self):
        items, kwargs = [], {}
        while True:
            result = self.paper.scan(ConsistentRead=True, **kwargs)
            items.extend(json.loads(item["payload"]) for item in result["Items"])
            if not result.get("LastEvaluatedKey"):
                return items
            kwargs["ExclusiveStartKey"] = result["LastEvaluatedKey"]

    def create_position(self, position):
        from botocore.exceptions import ClientError

        try:
            self.paper.put_item(
                Item={
                    "pk": position["position_id"],
                    "revision": 0,
                    "payload": json.dumps(position, allow_nan=False),
                },
                ConditionExpression="attribute_not_exists(pk)",
            )
            return position
        except ClientError as exc:
            if exc.response["Error"]["Code"] != "ConditionalCheckFailedException":
                raise
            existing = json.loads(
                self.paper.get_item(
                    Key={"pk": position["position_id"]}, ConsistentRead=True
                )["Item"]["payload"]
            )
            if any(
                existing[k] != position[k] for k in ("pair", "entry", "target", "stop")
            ):
                raise ValueError("Position UUID already used with different levels")
            return existing

    def save_position(self, position, revision):
        position["revision"] = revision + 1
        self.paper.put_item(
            Item={
                "pk": position["position_id"],
                "revision": revision + 1,
                "payload": json.dumps(position, allow_nan=False),
            },
            ConditionExpression="revision=:revision",
            ExpressionAttributeValues={":revision": revision},
        )


def clock_ms():
    return int(time.time() * 1000)
