import json
import boto3
import pytest
from moto import mock_aws
from radar.storage import CloudStore, LeaseLost
from radar.paper import new_position
from radar import handlers


@pytest.fixture
def cloud(monkeypatch):
    with mock_aws():
        monkeypatch.setenv("AWS_DEFAULT_REGION", "eu-central-1")
        monkeypatch.setenv("STATE_TABLE", "state")
        monkeypatch.setenv("PAPER_TABLE", "paper")
        monkeypatch.setenv("DATA_BUCKET", "lab-data-test")
        monkeypatch.setenv("RADAR_API_TOKEN", "private-test-token")
        for name in ("state", "paper"):
            boto3.client("dynamodb").create_table(
                TableName=name,
                KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
                AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
                BillingMode="PAY_PER_REQUEST",
            )
        boto3.client("s3").create_bucket(
            Bucket="lab-data-test",
            CreateBucketConfiguration={"LocationConstraint": "eu-central-1"},
        )
        sqs = boto3.client("sqs")
        monkeypatch.setenv("WORK_QUEUE", sqs.create_queue(QueueName="work")["QueueUrl"])
        monkeypatch.setenv("DEAD_QUEUE", sqs.create_queue(QueueName="dead")["QueueUrl"])
        yield CloudStore()


def test_cloud_atomic_pointer_leases_and_last_good_data(cloud):
    state = {"bars": [], "stages": {}, "backfill_complete": False}
    token = cloud.acquire("BTCTRY", "one", 100, lease_ms=10)
    assert cloud.acquire("BTCTRY", "duplicate", 101) is None
    cloud.commit("BTCTRY", token, state, {"generation": 1}, 102)
    token2 = cloud.acquire("BTCTRY", "two", 103)
    cloud.fail("BTCTRY", token2, "source unavailable", 104)
    assert cloud.read("BTCTRY", public=True) == {"generation": 1}
    with pytest.raises(LeaseLost):
        cloud.commit("BTCTRY", token, state, {"generation": 0}, 105)
    assert cloud.read("BTCTRY", public=True) == {"generation": 1}


def test_cloud_position_revision_and_idempotency(cloud):
    position = new_position(
        {
            "position_id": "47ce5261-3251-4b17-85f5-d64c32c058f9",
            "pair": "BTCTRY",
            "entry": 100,
            "target": 103,
        },
        100,
        ("BTCTRY",),
    )
    cloud.create_position(position)
    assert cloud.create_position(position) == position
    cloud.save_position(position, 0)
    assert cloud.positions()[0]["revision"] == 1
    with pytest.raises(Exception):
        cloud.save_position(position, 0)


def test_cloud_stop_pipeline_persists_trials_and_audit_metadata(cloud):
    from conftest import FakeMarket, make_bars
    from radar.api import API, audit
    from radar.config import Config, HOUR
    from radar.pipeline import run_pair

    bars = make_bars(799)
    now = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,))
    run_pair(cloud, FakeMarket(bars), cfg, "BTCTRY", "stops", now, clock=lambda: now)
    cloud.heartbeat(now)
    assert cloud.metadata("BTCTRY")["stop_trained_at"] == now
    assert cloud.read("BTCTRY")["stop_sessions"]
    assert cloud.read("BTCTRY", public=True)["stop_loss"]["research"]["round"] == 1
    assert audit(cloud, cfg, now)["ok"]
    code, payload = API(cloud, cfg, lambda *args: None).handle(
        "GET",
        "/v2/stop-loss",
        "pair=BTCTRY",
        {"Authorization": "Bearer " + cfg.token},
        "",
        now,
    )
    assert code == 200
    json.dumps(payload, allow_nan=False)


def test_scheduler_enqueues_without_frontend_and_replays_dlq(cloud, monkeypatch):
    monkeypatch.setenv("RADAR_PAIRS", "BTCTRY,ETHTRY,SOLTRY")
    import os

    sqs = boto3.client("sqs")
    sqs.send_message(
        QueueUrl=os.environ["DEAD_QUEUE"],
        MessageBody=json.dumps({"pair": "BTCTRY", "job": "retry"}),
    )
    result = handlers.scheduled({}, None)
    assert result == {"queued": 3, "replayed": 1}
    messages = sqs.receive_message(
        QueueUrl=os.environ["WORK_QUEUE"], MaxNumberOfMessages=10
    )["Messages"]
    assert len(messages) == 4
    assert cloud.metadata("SCHEDULER")["success_at"] > 0


def test_lambda_auth_and_unhealthy_reconcile(cloud):
    event = {
        "rawPath": "/v2/control/reconcile",
        "body": "{}",
        "requestContext": {"http": {"method": "POST"}},
    }
    assert handlers.http(event, None)["statusCode"] == 401
    event["headers"] = {"authorization": "Bearer private-test-token"}
    result = handlers.http(event, None)
    assert result["statusCode"] == 202
    assert json.loads(result["body"])["status"] == "RECOVERY_QUEUED"


def test_worker_returns_partial_failure_and_keeps_retryable_message(cloud, monkeypatch):
    def fail(*args, **kwargs):
        raise RuntimeError("source unavailable")

    monkeypatch.setattr(handlers, "run_pair", fail)
    result = handlers.worker(
        {
            "Records": [
                {"messageId": "1", "body": json.dumps({"pair": "BTCTRY", "job": "x"})}
            ]
        },
        None,
    )
    assert result == {"batchItemFailures": [{"itemIdentifier": "1"}]}
